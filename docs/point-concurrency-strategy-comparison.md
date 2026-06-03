# Point Concurrency Strategy Comparison

## 1. Problem Statement

Concurrent point deduction is a data consistency problem because multiple requests can try to read and update the same user's point balance at the same time.

The basic business rule is simple:

- A user cannot spend more points than they own.
- A successful deduction must be reflected in the final balance.
- Failed deductions must not change the balance.
- The final balance must be consistent with the number of successful deductions.

The difficulty appears when the implementation uses a simple read-modify-write flow:

1. Read the current point balance.
2. Check whether the balance is enough.
3. Subtract the requested amount.
4. Save the updated balance.

Under concurrent requests, several transactions can read the same old balance before any of them commits. Each transaction then calculates a new balance from stale data and writes it back. Later writes can overwrite earlier writes.

This is called a lost update.

Lost update means that multiple successful operations were logically accepted, but some of their effects were lost in the final persisted state. The system may report many successful deductions while the database balance reflects only a few of them.

This project intentionally reproduced that failure first, then compared several strategies for preventing or detecting it.

## 2. Test Scenario

All point deduction strategies were compared using the same high-contention scenario.

- Initial balance: 10,000
- Concurrent requests: 15
- Deduct amount per request: 1,000

The correct result under proper consistency control is:

- successCount = 10
- failCount = 5
- finalBalance = 0

Only 10 requests should succeed because the user has enough points for exactly 10 deductions of 1,000. The remaining 5 requests should fail because the balance is already exhausted.

## 3. Strategy Comparison Table

| Strategy | How it works | Consistency guarantee | Lost update prevention | Advantages | Disadvantages | Recommended use cases |
| --- | --- | --- | --- | --- | --- | --- |
| Transaction-only Baseline | Uses service-level `@Transactional` with read-modify-write entity mutation | Guarantees one request's operations are committed or rolled back together | No | Simple, easy to implement, useful as a failure baseline | Does not serialize concurrent transactions, can lose updates | Demonstrating the problem; simple workflows without shared-row contention |
| Pessimistic Lock | Reads the point row with a database write lock before deduction | Strong consistency by serializing access to the same row | Yes | Easy to reason about, preserves domain method logic, deterministic under high contention | Requests wait for the lock, lower throughput, possible lock timeout or deadlock in complex flows | High-contention updates where correctness is more important than throughput |
| Optimistic Lock | Uses `@Version` to detect concurrent modification at update time | Detects conflicts instead of silently overwriting data | Yes, by rejecting conflicting updates | No long lock wait, good when conflicts are rare, makes lost update visible | Many failures under high contention, usually needs retry handling for user-facing flows | Low-contention systems where occasional retries are acceptable |
| Atomic Update | Performs balance check and deduction in one conditional `UPDATE` query | Strong consistency for the specific update condition | Yes | Efficient, short database operation, produces correct success/fail count for simple balance deduction | Query-centered logic, bypasses domain method, less flexible for complex business rules | Simple conditional state changes such as balance deduction or stock decrement |

## 4. Transaction-only Baseline

The baseline implementation used service-level `@Transactional` and a normal JPA entity update.

The deduction flow was:

1. Find the Point row by user id.
2. Validate that the balance is greater than or equal to the deduction amount.
3. Subtract the amount from the entity field.
4. Let JPA dirty checking flush the update.

Observed result:

- successCount = 15
- failCount = 0
- finalBalance = 8000
- expectedBalanceBySuccessCount = -5000

This result is inconsistent.

If all 15 requests succeeded, the implied balance would be -5,000. However, the persisted balance was 8,000. This means the application accepted all requests, but the database stored the effect of only part of the updates.

`@Transactional` was not enough because it only defines the unit of work for a single request. It does not automatically serialize different transactions that read and update the same row. Under the default isolation behavior, multiple transactions can read the same balance and overwrite each other's changes.

Note: after `@Version` was added for the optimistic lock experiment, the same Point entity became subject to optimistic version checking. Therefore, the transaction-only lost update result is preserved as the baseline result observed before `@Version` was added.

## 5. Pessimistic Lock

The pessimistic lock strategy reads the Point row with a database write lock before deducting points.

Conceptually, the query behaves like:

- Select the Point row.
- Lock the row for update.
- Allow only one transaction at a time to modify that row.
- Release the lock when the transaction commits or rolls back.

Observed result:

- successCount = 10
- failCount = 5
- finalBalance = 0

Consistency was restored because requests for the same Point row were serialized. Each request saw the latest committed balance before performing the deduction. After 10 successful deductions, the balance reached 0. The remaining 5 requests acquired the lock later, saw insufficient balance, and failed.

The trade-off is lock waiting.

Under high contention, many requests may wait for the same row lock. This increases latency and can reduce throughput. In more complex flows involving multiple locked rows, lock ordering and timeout handling become important to avoid deadlocks.

Pessimistic locking is a strong and explainable choice when conflicts are expected to be frequent and correctness is more important than parallel throughput.

## 6. Optimistic Lock

The optimistic lock strategy adds a version field to the Point entity.

JPA uses the `@Version` value to detect whether another transaction has already updated the same row. When a transaction tries to flush its update, the database update includes the expected version. If the row version has already changed, the update fails and JPA raises an optimistic locking exception.

Observed example:

- successCount = 3
- failCount = 12
- finalBalance = 7000
- expectedBalanceBySuccessCount = 7000
- observed failure type: `ObjectOptimisticLockingFailureException`

The exact success count can vary depending on thread scheduling. The important property is that the final balance matches the number of successful deductions. No successful update is silently overwritten.

Optimistic locking detects conflicts. It does not prevent conflicts from happening.

This distinction matters:

- Pessimistic lock prevents concurrent modification by making transactions wait.
- Optimistic lock allows concurrent work but rejects updates when a version conflict is detected.

Without retry handling, optimistic locking can produce many failed requests under high contention. That is acceptable for this experiment because the goal was to verify conflict detection. A production user-facing flow would usually need retry logic or a different strategy if high contention is expected.

## 7. Atomic Update

The atomic update strategy avoids the read-modify-write pattern.

Instead of loading the Point entity, checking the balance in Java, and then saving a changed entity, the database performs the condition check and update in a single statement.

The query concept is:

- Deduct the amount only if the current balance is greater than or equal to the amount.
- Return the number of rows updated.
- Treat update count 1 as success.
- Treat update count 0 as failure.

Observed result:

- successCount = 10
- failCount = 5
- finalBalance = 0
- expectedBalanceBySuccessCount = 0

This works because the database evaluates the balance condition and applies the update atomically. Once the balance reaches 0, later requests no longer satisfy the condition and update no rows.

This approach is efficient for simple balance deduction because it minimizes the amount of work done in the application and avoids long lock holding at the service level. It is also deterministic for this scenario: exactly 10 deductions can succeed.

The trade-off is that business logic moves into the query. The domain method is bypassed, so validation and invariants must be carefully reflected in the query and service method. If the deduction rule becomes more complex, the query can become harder to maintain.

## 8. Lessons Learned

The experiments show that transaction boundaries and concurrency control are different concerns.

Key insights:

- `@Transactional` is necessary for atomicity within one request, but it does not automatically prevent lost update between concurrent requests.
- Pessimistic locking restores consistency by serializing access to the same row.
- Optimistic locking makes lost update visible by detecting version conflicts.
- Atomic update avoids read-modify-write and lets the database enforce the condition and update together.
- The best strategy depends on contention level, business complexity, and failure handling requirements.

Strategy choice by situation:

| Situation | Suitable strategy |
| --- | --- |
| Need to demonstrate the problem | Transaction-only baseline |
| High contention and correctness must be deterministic | Pessimistic Lock |
| Low contention and retry is acceptable | Optimistic Lock |
| Simple conditional decrement such as point balance or stock | Atomic Update |
| Complex domain rules spanning multiple entities | Pessimistic Lock or explicit transaction design |

The implementation also showed an important modeling detail: adding `@Version` affects all updates of the same entity. It is not limited to one service method. This is why the original transaction-only baseline result is treated as a historical baseline observed before the optimistic locking experiment.

## 9. Final Conclusion

Concurrent point deduction is a small domain problem with a real consistency risk.

The transaction-only baseline reproduced lost update and showed why simple read-modify-write logic is unsafe under concurrent access. Pessimistic locking restored consistency by serializing row access. Optimistic locking detected concurrent modification through version conflicts. Atomic update produced the expected business result efficiently by performing the balance condition and deduction in one database statement.

For this specific point deduction scenario, atomic update is the most efficient and direct solution when the rule is simple: deduct only if the balance is sufficient. Pessimistic locking remains the easiest to reason about when business logic is more complex. Optimistic locking is useful when conflicts are expected to be rare and retry handling is acceptable.

Together, these experiments demonstrate not only how to fix a concurrency bug, but also how to evaluate multiple consistency strategies based on correctness, performance, complexity, and production suitability.