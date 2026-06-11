# Coupon Concurrency Strategy Comparison

## 1. Problem Statement

First-come coupon issuance is a high-contention consistency problem because many users can request the same limited-stock coupon at the same time.

The business rules are simple:

- A coupon campaign must not issue more coupons than its configured stock.
- A user must not receive the same coupon more than once.
- A successful issuance must create a durable IssuedCoupon record.
- The Coupon inventory value and IssuedCoupon records must remain consistent.
- Failed requests must not leave partial issuance state behind.

The difficulty appears when the implementation uses a simple read-modify-write flow:

1. Read the Coupon row.
2. Check whether `issuedQuantity < totalQuantity`.
3. Check whether the user already has the coupon.
4. Increase `issuedQuantity`.
5. Save an IssuedCoupon record.
6. Commit the transaction.

Under concurrent requests, several transactions can read the same old coupon state before any of them commits. Each transaction can decide that stock remains. Multiple requests from the same user can also pass the duplicate check before the first insert becomes visible.

This project intentionally reproduced those failures first, then compared database constraints, database locking, conditional updates, and Redis-based front-line gating strategies.

## 2. Common Test Scenarios

The coupon experiments use two primary scenarios because stock consistency and duplicate consistency are different problems.

### 2.1 Stock-Control Scenario

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users

The correct result under proper stock control is:

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

Only 100 requests should succeed because the coupon has exactly 100 available units. The remaining 900 requests should fail before creating durable issued coupon records.

### 2.2 Duplicate-Control Scenario

- Coupon stock: 1,000
- Concurrent requests: 100
- User condition: the same user requests the same coupon 100 times concurrently

The correct result under proper duplicate control is:

- successCount = 1
- failCount = 99
- issuedCouponCountByUserAndCoupon = 1

Only one request should succeed because a user can own at most one IssuedCoupon for the same Coupon.

### 2.3 Verification Criteria

Each strategy is evaluated using final persisted state, not only request counters.

The important checks are:

- Success count must not exceed coupon stock.
- `finalIssuedQuantity` must not exceed `totalQuantity`.
- IssuedCoupon record count must match the successful issuance count.
- A user must not have duplicate IssuedCoupon records for the same Coupon.
- Redis-side counters or sets must match the expected accepted state when Redis is part of the strategy.
- PostgreSQL remains the durable source of truth for Coupon and IssuedCoupon records.

## 3. Observed Results Summary

| Strategy | Scenario | successCount | failCount | DB issued records | Coupon issued quantity | Redis state | Result |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| Transaction-only Baseline | Stock: 100, requests: 1,000, distinct users | 1000 | 0 | 1000 | 100 | Not used | Failed: overselling and inventory-record mismatch |
| Transaction-only Baseline | Stock: 1,000, requests: 100, same user | 10 | 90 | 10 for same user-coupon | Not the main check | Not used | Failed: duplicate issuance |
| DB Unique Constraint | Stock: 1,000, requests: 100, same user | 1 | 99 | 1 for same user-coupon | Not the main check | Not used | Passed duplicate control |
| Pessimistic Lock | Stock: 100, requests: 1,000, distinct users | 100 | 900 | 100 | 100 | Not used | Passed stock control |
| Optimistic Lock | Stock: 100, requests: 1,000, distinct users | 100 | 900 | 100 | 100 | Not used | Observed no overselling; exact success count is not guaranteed without retry |
| Atomic Update | Stock: 100, requests: 1,000, distinct users | 100 | 900 | 100 | 100 | Not used | Passed stock control |
| Redis Counter | Stock: 100, requests: 1,000, distinct users | 100 | 900 | 100 | 100 | counter = 100 | Passed stock gate plus DB persistence |
| Redis Lua Script | Stock: 100, requests: 1,000, distinct users | 100 | 900 | 100 | 100 | counter = 100, issued users = 100 | Passed stock gate plus DB persistence |
| Redis Lua Script | Stock: 1,000, requests: 100, same user | 1 | 99 | 1 for same user-coupon | 1 | counter = 1, issued users = 1 | Passed duplicate gate plus DB persistence |

The transaction-only baseline results are historical observations from before the current model/schema changes. After adding `Coupon.version`, the same transaction-only stock path is affected by JPA version checking. After adding the database unique constraint, the duplicate-failure baseline is intentionally blocked by the current schema. Those baseline tests are therefore preserved as disabled historical tests and documented as the original failure evidence.

## 4. Strategy Comparison Table

| Strategy | How it works | Protects | Does not protect | Advantages | Trade-offs | Recommended use cases |
| --- | --- | --- | --- | --- | --- | --- |
| Transaction-only Baseline | Uses service-level `@Transactional` with entity read-modify-write and application-level duplicate check | Atomicity inside one request | Concurrent stock updates, duplicate issuance, inventory-record consistency under contention | Simple, useful failure baseline, easy to explain | Unsafe under shared-row contention | Demonstrating why transaction boundaries are not enough |
| DB Unique Constraint | Enforces uniqueness on `(userId, couponId)` at the database insert boundary | Duplicate issuance for the same user and coupon | Coupon stock overselling, inventory increments, Redis/DB mismatch | Deterministic final guard, simple and durable | Duplicate failures happen at persistence time; schema must really contain the constraint | Required final guard for user-coupon uniqueness |
| Pessimistic Lock | Reads the Coupon row with a database write lock before checking and incrementing stock | Stock overselling and inventory-record mismatch for the locked Coupon row | Duplicate issuance without unique constraint, high throughput under contention | Strong consistency, easy reasoning, preserves domain logic | Lock waiting, lower throughput, possible timeout/deadlock concerns in larger workflows | High-contention workflows where deterministic correctness matters |
| Optimistic Lock | Uses `Coupon.version` to reject stale concurrent updates at flush time | Silent lost update on Coupon inventory | Duplicate issuance without unique constraint, full stock exhaustion without retry, user-friendly success rate under heavy contention | No long lock wait, makes conflicts visible | Many conflicts under high contention, usually needs retry policy, version affects all updates of the entity | Low to moderate contention where retry or conflict response is acceptable |
| Atomic Update | Lets PostgreSQL check stock and increment issued quantity in one conditional update | Stock overselling for simple inventory increments | Duplicate issuance without unique constraint, complex domain workflows | Efficient, short DB operation, deterministic stock-limited success count | Business rule moves into query, must coordinate IssuedCoupon insert transactionally | Simple counters such as stock increment or balance deduction |
| Redis Counter | Uses Redis atomic counter as a front-line stock gate before DB persistence | Limits accepted stock slots before the DB write path | Duplicate issuance, durable consistency by itself, Redis/DB atomicity | Reduces DB pressure during traffic spikes, simple Redis model | Adds Redis round trip, requires compensation or reconciliation, DB remains source of truth | High-traffic events where stock gating before PostgreSQL is valuable |
| Redis Lua Script | Uses one Redis script to atomically check stock and duplicate state before DB persistence | Redis-side stock gate and duplicate gate | Durable consistency by itself, Redis/DB atomicity, final DB uniqueness requirement | Handles stock and duplicate checks together, reduces DB write pressure | More complex, Lua logic must be tested and observed, compensation still required | High-traffic first-come issuance with Redis-side duplicate rejection |

## 5. Transaction-only Baseline

The baseline implementation uses service-level transactions and a normal JPA entity mutation flow. It checks stock and duplicate status in application code, updates the Coupon entity, and saves an IssuedCoupon record in the same transaction.

This is useful as a baseline because it shows the difference between transaction atomicity and concurrency control. `@Transactional` defines the unit of work for one request, but it does not serialize competing transactions that read and update the same Coupon row.

Observed stock-control failure:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users
- successCount = 1000
- failCount = 0
- issuedCouponCountByCoupon = 1000
- finalIssuedQuantity = 100

This is an overselling failure. The system created 1,000 issued coupon records for a coupon with stock 100. The Coupon row still reported `issuedQuantity = 100`, so the aggregate inventory value and issued records diverged.

Observed duplicate-control failure:

- Coupon stock: 1,000
- Concurrent requests: 100
- User condition: same user and same coupon
- successCount = 10
- failCount = 90
- issuedCouponCountByUserAndCoupon = 10

This is a duplicate issuance failure. Several concurrent requests passed the application-level duplicate check before any committed insert became visible.

What this strategy protects:

- Commit or rollback of one request's local unit of work.
- Basic sequential correctness when there is no meaningful contention.

What this strategy does not protect:

- Stock consistency under concurrent requests.
- User-coupon uniqueness under concurrent duplicate requests.
- Alignment between Coupon inventory and IssuedCoupon records under high contention.

The main lesson is that a transaction boundary is necessary, but not sufficient, for shared-state concurrency control.

## 6. DB Unique Constraint

The database unique constraint enforces one IssuedCoupon row per `(userId, couponId)` pair.

The application-level duplicate check can still race. Multiple concurrent requests may all observe that the user does not yet have the coupon. The difference is that the database evaluates uniqueness at insert time, so only one row for the same user-coupon pair can commit.

Observed duplicate-control result:

- Coupon stock: 1,000
- Concurrent requests: 100
- User condition: same user and same coupon
- successCount = 1
- failCount = 99
- issuedCouponCountByUserAndCoupon = 1

What this strategy protects:

- Duplicate issuance for the same user and coupon.
- Final persistence-level uniqueness even when application checks race.

What this strategy does not protect:

- Total coupon stock.
- Lost updates on `Coupon.issuedQuantity`.
- Inventory-record mismatch when many distinct users request a limited-stock coupon.

The important portfolio point is that some invariants belong at the database boundary. User-coupon uniqueness is one of them. An application pre-check can improve error handling or avoid unnecessary work, but the database constraint is the final guard.

The experiment also exposed an operational detail: in a `ddl-auto=update` environment, adding a unique constraint to an existing table may not be applied automatically. The schema must be verified before relying on the result.

## 7. Pessimistic Lock

The pessimistic lock strategy reads the Coupon row with a database write lock before checking stock and increasing `issuedQuantity`.

Conceptually, requests for the same Coupon are serialized:

1. One transaction locks the Coupon row.
2. It checks the latest committed stock state.
3. It increments issued quantity and creates an IssuedCoupon record.
4. It commits or rolls back, then releases the lock.
5. The next waiting transaction repeats the same process using the updated state.

Observed stock-control result:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users
- Lock hold delay for contention observation: `PESSIMISTIC_LOCK_HOLD_MILLIS = 5L`
- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100
- Test duration: about 10 seconds

What this strategy protects:

- Stock overselling for the locked Coupon row.
- Inventory-record mismatch in the tested stock-control scenario.
- Silent lost update on Coupon inventory.

What this strategy does not protect:

- Duplicate issuance by itself. The DB unique constraint is still required.
- Throughput under very high contention.
- Larger workflows from lock-ordering, timeout, or deadlock concerns.

Pessimistic locking is the easiest strategy to reason about when contention is expected and correctness is more important than parallel throughput. The trade-off is latency: many requests may wait for the same row lock.

## 8. Optimistic Lock

The optimistic lock strategy adds a version field to Coupon and lets JPA detect concurrent modification through `Coupon.version`.

Requests do not wait for a write lock before doing work. Instead, they read the Coupon row and attempt to commit an update using the version they read. If another transaction has already updated the row, the version check fails and the stale update is rejected.

Observed stock-control result in the documented test run:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users
- Delay for contention observation: `LOCK_HOLD_MILLIS = 5L`
- observed successCount = 100
- observed failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

This result shows that optimistic locking prevented overselling in the observed run. However, without retry, optimistic locking does not guarantee that exactly all 100 stock units will be issued under every scheduling condition. It guarantees that stale concurrent updates are rejected rather than silently overwriting `issuedQuantity`.

The core correctness property for this no-retry experiment is:

- successCount must not exceed stock.
- issuedCouponCountByCoupon must equal successCount.
- finalIssuedQuantity must equal successCount.
- finalIssuedQuantity must not exceed totalQuantity.

If the product requirement is to maximize issuance until stock is fully exhausted, optimistic locking needs a retry policy or a different strategy such as pessimistic locking, atomic update, or Redis-based gating.

What this strategy protects:

- Silent lost update on Coupon inventory.
- Inventory-record mismatch caused by stale Coupon updates.

What this strategy does not protect:

- Duplicate issuance by itself. The DB unique constraint is still required.
- Full stock exhaustion under every high-contention schedule without retry.
- A user-friendly success rate under high contention unless retry behavior is designed.
- The original transaction-only behavior after `@Version` is added, because version checking affects all updates of the entity.

Optimistic locking detects conflicts rather than preventing them. That distinction matters in first-come coupon issuance. Under heavy contention, many requests can conflict. Without retry, those requests fail. With retry, the system must define retry limits, latency expectations, and fairness behavior.

The current experiment verifies conflict detection and final database consistency without implementing retry handling.

## 9. Atomic Update

The atomic update strategy avoids the application-level read-modify-write inventory flow.

Instead of loading a Coupon entity, checking stock in Java, and relying on dirty checking, PostgreSQL checks stock availability and increments issued quantity in one conditional update. The affected row count becomes the stock decision:

- Updated row count 1 means stock was reserved.
- Updated row count 0 means stock was exhausted.

Observed stock-control result:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users
- Delay for contention observation: `LOCK_HOLD_MILLIS = 5L`
- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

What this strategy protects:

- Stock overselling for a simple issued-count increment.
- Lost update on the stock counter.
- Creation of IssuedCoupon records after stock is already exhausted, when the insert is performed only after the update succeeds.

What this strategy does not protect:

- Duplicate issuance by itself. The DB unique constraint is still required.
- Complex business rules that are hard to express in one update.
- Cross-store consistency when Redis is introduced.

Atomic update is the most direct database-centered solution for this specific stock rule. It is efficient because it keeps the critical operation short and lets the database evaluate the condition atomically.

The trade-off is that some business logic moves into the query. That is acceptable for simple counters, but the service must ensure that the conditional update and IssuedCoupon insert are coordinated as one logical operation.

## 10. Redis Counter

The Redis Counter strategy uses Redis as a front-line stock gate before database persistence.

Each request increments a Redis counter for the coupon. Requests whose counter value is within stock are allowed to continue to PostgreSQL. Requests whose counter value exceeds stock are rejected before entering the database write path.

Observed stock-control result:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users
- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100
- redisCounterValue = 100

What this strategy protects:

- Front-line stock slot acceptance in Redis.
- Database write path from receiving every request during a traffic spike.
- Final stock consistency when accepted requests are still persisted through PostgreSQL conditional update.

What this strategy does not protect:

- Duplicate issuance by itself.
- Durable issuance state by itself.
- Atomic consistency between Redis and PostgreSQL.

Redis Counter should not be understood as replacing the database consistency model. It is a traffic gate. PostgreSQL remains the durable source of truth for Coupon and IssuedCoupon records, and the DB unique constraint remains the final duplicate guard.

This distinction is important because Redis can accept a request and PostgreSQL persistence can still fail. The implementation compensates by decrementing the Redis counter when DB persistence fails after Redis acceptance. That keeps the tested state aligned, but it is not the same as one atomic transaction across Redis and PostgreSQL.

In a small local test, Redis Counter can be slower than Atomic Update because every request pays an additional Redis round trip while the database conditional update is already cheap. Its value appears when request volume is high enough that rejecting excess traffic before the database write path matters.

## 11. Redis Lua Script

The Redis Lua strategy uses one Redis script to atomically check both stock state and duplicate state before database persistence.

The Redis-side state has two responsibilities:

- A count key tracks accepted stock slots for the coupon.
- A user set tracks which users have already been accepted for the coupon.

The script checks duplicate state first, checks stock second, and only writes Redis reservation state when both checks pass. Because the script runs atomically inside Redis, no other Redis command can interleave between the checks and writes.

Observed stock-control result:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users
- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100
- redisCounterValue = 100
- redisIssuedUserCount = 100

Observed duplicate-control result:

- Coupon stock: 1,000
- Concurrent requests: 100
- User condition: same user and same coupon
- successCount = 1
- failCount = 99
- issuedCouponCountByCoupon = 1
- issuedCouponCountByUserAndCoupon = 1
- finalIssuedQuantity = 1
- redisCountValue = 1
- redisIssuedUserCount = 1

What this strategy protects:

- Redis-side stock acceptance.
- Redis-side duplicate acceptance for the same user and coupon.
- Database write path from most sold-out or duplicate requests.

What this strategy does not protect:

- Durable issuance state by itself.
- Atomic consistency between Redis and PostgreSQL.
- The need for DB unique constraint as the final duplicate guard.
- Operational concerns such as Lua script testing, script deployment, Redis failure handling, and observability.

Redis Lua is stronger than Redis Counter for this domain because coupon issuance has two front-line decisions: stock availability and user-coupon uniqueness. Redis Counter only understands stock slots. Redis Lua can reject repeated user requests before they reach the database.

The trade-off is complexity. Business logic now exists in Redis Lua as well as in the database-backed persistence flow. The script must be tested, versioned, and monitored like application logic.

## 12. Redis and PostgreSQL Consistency Boundary

The Redis-based strategies introduce an explicit consistency boundary.

Redis can make fast atomic decisions inside Redis:

- `INCR` gives Redis Counter atomic stock slot numbers.
- Lua can atomically check and update multiple Redis keys.

However, Redis and PostgreSQL do not share one commit boundary. A request can pass Redis and fail while saving to PostgreSQL. The opposite failure shape is also possible in production if the database commit succeeds but a later Redis cleanup, acknowledgement, or reconciliation step fails.

The implemented experiments handle the main tested mismatch case with compensation:

- Redis Counter: if DB persistence fails after Redis acceptance, decrement the Redis counter.
- Redis Lua Script: if DB persistence fails after Redis acceptance, decrement the Redis counter and remove the user id from the Redis issued-user set.

These compensations are useful, but they are not equivalent to a distributed transaction. A production-grade design would still need clear answers for:

- How to detect Redis and DB divergence.
- How to reconcile Redis counters and user sets from PostgreSQL.
- How to make retry behavior idempotent.
- How long Redis keys should live.
- Whether issuance state should be rebuilt from DB after Redis loss.
- Which metrics and alerts indicate compensation or persistence failures.

For this project, the key architectural position is clear: Redis is a front-line gate, and PostgreSQL is the durable source of truth.

## 13. Lessons Learned

The experiments show that coupon issuance has two separate consistency dimensions.

Stock consistency answers:

- Did the system issue more coupons than available?
- Does `Coupon.issuedQuantity` match the number of issued records?

Duplicate consistency answers:

- Did the same user receive the same coupon more than once?
- Is `(userId, couponId)` protected at the persistence boundary?

Key insights:

- `@Transactional` alone is not enough under concurrent shared-row updates.
- Application-level duplicate checks are useful but cannot be the final uniqueness guarantee.
- DB unique constraints are mandatory for invariants that must never be violated.
- Pessimistic locking is simple and deterministic, but lock waiting is expensive under high contention.
- Optimistic locking makes lost updates visible, but high-contention first-come flows need explicit retry or failure policy.
- Atomic update is a strong database-centered fit for simple stock counters.
- Redis Counter is traffic gating, not durable consistency.
- Redis Lua can atomically combine stock and duplicate checks inside Redis, but PostgreSQL must still remain the final durable source of truth.

Strategy choice by situation:

| Situation | Suitable strategy |
| --- | --- |
| Need to demonstrate the problem | Transaction-only Baseline |
| Need final user-coupon uniqueness | DB Unique Constraint |
| High contention and simple reasoning matters most | Pessimistic Lock |
| Lower contention and retry is acceptable | Optimistic Lock |
| Simple stock counter update | Atomic Update |
| Large traffic spike where excess requests should not reach DB writes | Redis Counter plus DB persistence |
| Large traffic spike with both stock and duplicate checks before DB writes | Redis Lua Script plus DB persistence |

## 14. Final Conclusion

Coupon issuance is a compact domain with real concurrency risk.

The transaction-only baseline reproduced two important failures: overselling and duplicate issuance. It showed that a transaction boundary does not automatically serialize concurrent requests or guarantee uniqueness across racing inserts.

The DB unique constraint solved duplicate issuance at the only boundary that can be trusted as the final guard: the database. Pessimistic locking, optimistic locking, and atomic update each addressed stock consistency through different mechanisms. Pessimistic locking serialized access, optimistic locking rejected stale updates, and atomic update let PostgreSQL enforce the stock condition directly.

Optimistic locking deserves a careful interpretation. In the documented run, it reached the stock limit without overselling. That is an observed result, not a no-retry guarantee. Without retry, optimistic locking guarantees conflict detection and stale-update rejection, but it does not guarantee that every stock unit will be issued under all high-contention schedules.

Redis Counter and Redis Lua moved part of the decision-making before the database write path. Redis Counter limited stock slots, while Redis Lua added atomic Redis-side duplicate rejection. Both strategies reduce database pressure during high-traffic issuance, but neither removes the need for PostgreSQL durability, database constraints, and reconciliation thinking.

For this specific coupon stock problem, Atomic Update is the most direct database-centered strategy when the rule is simple. For high-traffic first-come events, Redis Lua is the stronger front-line strategy because it handles both stock and duplicate checks before database persistence. In both cases, the durable consistency model still depends on PostgreSQL and the DB unique constraint.

Together, these experiments demonstrate not just how to fix one concurrency bug, but how to evaluate consistency strategies by correctness scope, failure behavior, performance trade-off, and operational complexity.
