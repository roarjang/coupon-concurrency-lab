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

Status: Planned

Goal:

Use version-based conflict detection.

Expected behavior:

Concurrent updates are detected through version mismatch. Some requests fail and retry handling.

Note:

Point does not have an `@Version` field yet. It should be added only when this phase is implemented.

## Phase 6. Atomic Update

Status: Planned

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

## Phase 7. Redis

Status: Planned

Goal:

Use Redis later for coupon issuance or traffic control experiments.

Redis is not actively used in the current Point implementation.

## Phase 8. Product, Coupon, IssuedCoupon, Order

Status: Planned

Goal:

Implement the remaining domains after the Point concurrency baseline and locking experiments are documented clearly.
