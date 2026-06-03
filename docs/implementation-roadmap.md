# Implementation Roadmap

## Phase 1. User and Authentication

Status: Done

- User entity
- Signup API
- Password encoding
- JWT login
- JWT authentication filter
- Security configuration

## Phase 2. Point Domain - Transaction-Only Read-Modify-Write Baseline

Status: Done

Goal: 

Implement a simple Point domain with service-level `@Transactional`, but without explicit concurrency control.

Implemented features:

- Point entity
- Point repository
- Create point wallet when user signs up
- Charge point
- Deduct point
- Get point balance
- Point charge API
- Point deduct API
- Point balance query API

Current classification:

This is an `@Transactional`-based naive read-modify-write implementation.
Each request is wrapped in a transaction, but concurrent requests are not serialized.

Not implemented yet:

- Basic API tests
- Basic service tests

Important: Do not apply concurrency control yet.

Do not use:

- Pessimistic lock
- Optimistic lock
- Redis
- Distributed lock
- Atomic update query
- synchronized

## Phase 3. Naive Concurrency Test

Status: Done

Goal:

Reproduce lost update under concurrent point deduction using the transaction-only read-modify-write baseline.

Test scenario:

- Initial balance: 10,000
- Concurrent requests: 15
- Deduct amount: 1,000

Expected result with correct concurrency control:

- Success: 10
- Failure: 5
- Final balance: 0

Possible result with naive implementation:

- Success: more than 10
- Failure: fewer than 5
- Final balance is not consistent with the number of successful deductions

Observed result:

- successCount = 15
- failCount = 0
- finalBalance = 8000
- expectedBalanceBySuccessCount = -5000

Conclusion:

`@Transactional` groups operations inside a single request, but it does not automatically serialize concurrent requests.
Lost update can still occur under concurrent point deduction.

Note:

This baseline result was observed before adding `@Version` to the Point entity.
After `@Version` is added, the transaction-only path is also affected by optimistic lock version checking.

## Phase 4. Pessimistic Lock

Status: Done

Goal: 

Use database row-level locking to prevent concurrent modification of the same Point row.

Expected behavior:

Only one transaction can modify the Point row at a time.

Implemented approach:

- Add a locked repository query using `PESSIMISTIC_WRITE`
- Keep the existing transaction-only `deduct()` baseline unchanged
- Add `PointService.deductWithPessimisticLock()` as a separate comparison method
- Verify the result with a dedicated concurrency test

Observed result:

- successCount = 10
- failCount = 5
- finalBalance = 0

## Phase 5. Optimistic Lock

Status: Done

Goal:

Use version-based conflict detection.

Expected behavior:

Concurrent updates are detected through version mismatch. Some requests fail and retry handling.

Implemented approach:

- Add `@Version` to the Point entity
- Add `PointService.deductWithOptimisticLock()` as a separate comparison method
- Keep retry handling out of this phase
- Verify that optimistic lock conflicts are detected in a dedicated concurrency test

Observed example:

- successCount = 3
- failCount = 12
- finalBalance = 7000
- expectedBalanceBySuccessCount = 7000

Conclusion:

Optimistic lock prevents silent lost update by detecting version conflicts.
The exact success count may vary under concurrent execution, so the test verifies conflict failures and consistency between successful deduction count and final balance.

## Phase 6. Atomic Update

Status: Done

Goal:

Use a conditional update query to deduct points atomically.

Example idea:

```sql
UPDATE points
SET balance = balance - :amount
WHERE user_id = :userId
AND balance >= :amount
```

Expected behavior:

The database handles the condition and update as a single atomic operation.

Implemented approach:

- Add a conditional update query to PointRepository
- Deduct only when `balance >= amount`
- Increase `version` together with balance update
- Add `PointService.deductWithAtomicUpdate()` as a separate comparison method
- Verify consistency with a dedicated concurrency test

Observed result:

- successCount = 10
- failCount = 5
- finalBalance = 0
- expectedBalanceBySuccessCount = 0

## Phase 7. Redis

Status: Planned

Goal:

Use Redis later for coupon issuance or traffic control experiments.

Redis is not actively used in the current Point implementation.

## Phase 8. Product, Coupon, IssuedCoupon, Order

Status: Planned

Goal:

Implement the remaining domains after the Point concurrency baseline and locking experiments are documented clearly.
