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

## Phase 7. Coupon Domain - Transaction-Only Baseline

Status: Done

Goal:

Implement coupon issuance with service-level `@Transactional`, but without explicit stock or duplicate consistency control.

Implemented features:

- Coupon entity
- IssuedCoupon entity
- Coupon repository
- IssuedCoupon repository
- Transaction-only coupon issuance service path
- Concurrency test for overselling reproduction
- Concurrency test for duplicate issuance reproduction

Overselling scenario:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users

Expected result with correct concurrency control:

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

Observed result:

- successCount = 1000
- failCount = 0
- issuedCouponCountByCoupon = 1000
- finalIssuedQuantity = 100

Conclusion:

`@Transactional` alone does not protect the stock check and increment under concurrent requests.
IssuedCoupon records exceeded the configured stock, and `Coupon.issuedQuantity` diverged from the actual issued record count.

Note:

This overselling baseline result was observed before adding `@Version` to the Coupon entity.
After `@Version` is added, the transaction-only coupon stock path is also affected by optimistic lock version checking.
The overselling reproduction test is therefore disabled in the current test suite and preserved as a historical baseline for docs and git history.

Duplicate issuance scenario:

- Coupon stock: 1,000
- Concurrent requests: 100
- User: same user requesting the same coupon

Expected result with correct duplicate control:

- successCount = 1
- failCount = 99
- issuedCouponCountByUserAndCoupon = 1

Observed baseline result before applying the DB unique constraint:

- successCount = 10
- failCount = 90
- issuedCouponCountByUserAndCoupon = 10

Conclusion:

The application-level duplicate check can race.
Multiple transactions can read "not issued yet" before any committed insert is visible.

## Phase 8. Coupon Duplicate Issuance - DB Unique Constraint

Status: Done

Goal:

Use a database unique constraint as the final guard against duplicate issuance for the same user and coupon.

Implemented approach:

- Add UNIQUE constraint for `(userId, couponId)` on IssuedCoupon
- Keep application-level duplicate check as a fast pre-check
- Catch duplicate-key persistence failure and treat it as duplicate issuance failure
- Verify the result with a dedicated concurrency test

Schema verification note:

`spring.jpa.hibernate.ddl-auto=update` may not automatically add a new UNIQUE constraint to an already existing table.
For this experiment, the actual database schema was checked with `psql` using `\d issued_coupons` to confirm that the unique constraint existed.

Test scenario:

- Coupon stock: 1,000
- Concurrent requests: 100
- User: same user requesting the same coupon

Expected result:

- successCount = 1
- failCount = 99
- issuedCouponCountByUserAndCoupon = 1

Observed result:

- successCount = 1
- failCount = 99
- issuedCouponCountByUserAndCoupon = 1

Conclusion:

The database constraint prevents duplicate rows even when concurrent requests pass the application-level duplicate check.
This solves duplicate issuance for one user and one coupon, but it does not solve coupon stock overselling.

## Phase 9. Coupon Stock Control - Pessimistic Lock

Status: Done

Goal:

Serialize updates to the same Coupon row with a database write lock.

Implemented approach:

- Add a repository query that reads the Coupon row with `PESSIMISTIC_WRITE`
- Keep the transaction-only coupon issuance path as the baseline
- Add a separate pessimistic-lock issuance path for stock-control comparison
- Check stock while holding the lock
- Increase `issuedQuantity` and create IssuedCoupon in the same transaction
- Verify the result with a dedicated concurrency test

Test scenario:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users
- Lock hold delay for contention observation: `PESSIMISTIC_LOCK_HOLD_MILLIS = 5L`

Expected result:

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

Observed result:

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100
- test duration: about 10 seconds

Conclusion:

The pessimistic lock serializes concurrent stock checks and increments for the same Coupon row.
IssuedCoupon records no longer exceed the configured stock, and `Coupon.issuedQuantity` stays consistent with the actual issued record count.

This solves coupon stock overselling under the tested scenario.
Duplicate issuance remains a separate consistency concern and is guarded by the DB UNIQUE constraint on `(userId, couponId)`.

## Phase 10. Coupon Stock Control - Optimistic Lock

Status: Done

Goal:

Use Coupon versioning to detect concurrent stock update conflicts.

Implemented approach:

- Add `@Version` to the Coupon entity
- Read Coupon normally without a database write lock
- Let JPA detect version mismatch when concurrent transactions update the same Coupon row
- Keep retry handling out of this phase
- Verify final Coupon inventory and IssuedCoupon records with a dedicated concurrency test

Test scenario:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users
- Delay for contention observation: `LOCK_HOLD_MILLIS = 5L`

Expected result:

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

Observed result:

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

Conclusion:

Optimistic locking prevents silent lost update by rejecting conflicting Coupon row updates through version mismatch.
Under the tested scenario, successful issued records and `Coupon.issuedQuantity` both stayed at 100.
This phase verifies conflict detection without retry handling.

Side effect:

Adding `@Version` changes the behavior of every update path for Coupon.
The earlier transaction-only overselling baseline is no longer an active reproducible test in the current model and is kept disabled for historical documentation.

## Phase 11. Coupon Stock Control - Atomic Update

Status: Done

Goal:

Use a conditional database update to check and increment stock atomically.

Implemented approach:

- Add a conditional bulk update query to CouponRepository
- Increment `issuedQuantity` only when `issuedQuantity < totalQuantity`
- Use the update count as the stock availability decision
- Create IssuedCoupon only after the stock update succeeds
- Update `updatedAt` directly in the query because bulk updates bypass `@PreUpdate`
- Keep duplicate issuance protection delegated to the DB UNIQUE constraint on `(userId, couponId)`

Test scenario:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users
- Delay for contention observation: `LOCK_HOLD_MILLIS = 5L`

Expected result:

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

Observed result:

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

Conclusion:

The conditional update lets PostgreSQL check stock and increment `issuedQuantity` as one atomic operation.
Only 100 requests updated the Coupon row, and the remaining 900 failed before creating IssuedCoupon records.
This solves coupon stock overselling under the tested distinct-user scenario.

This differs from pessimistic locking because requests do not hold a row lock across an entity read and domain method call.
It differs from optimistic locking because stock control is decided by the conditional update count, not by loading a Coupon entity and handling version conflicts during flush.

Duplicate issuance remains a separate concern and is still guarded by the DB UNIQUE constraint on `(userId, couponId)`.
Because this stock-control test uses different users, `save` is enough for IssuedCoupon persistence in this phase; `saveAndFlush` is not required to force duplicate-key detection.

## Phase 12. Coupon Traffic Control - Redis Counter

Status: Done

Goal:

Use Redis as a fast front-line stock gate before database persistence.

Implemented approach:

- Add a Redis-based coupon stock gate as a separate issuance strategy
- Use an atomic Redis counter operation to reserve only the first stock-sized request slots
- Reject requests whose Redis counter value exceeds coupon stock before they perform DB persistence
- Persist accepted requests to PostgreSQL as the durable source of truth
- Keep the DB UNIQUE constraint on `(userId, couponId)` as the duplicate issuance guard
- Use the conditional database update for final `Coupon.issuedQuantity` persistence
- Compensate Redis by decrementing the counter when database persistence fails after Redis acceptance

Test scenario:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users

Observed result:

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100
- redisCounterValue = 100

Conclusion:

Redis Counter works as a front-line stock gate in the tested distinct-user scenario.
It should be understood as traffic control before database persistence, not as a replacement for PostgreSQL consistency.
In small local tests, Atomic Update can still be faster because Redis Counter adds one Redis round trip to every request while the database conditional update is already cheap.

Scope limitation:

This phase is for Redis front-line stock gating only.
It does not solve duplicate issuance by itself; duplicate issuance remains the responsibility of the DB UNIQUE constraint on `(userId, couponId)`.
Redis-side duplicate tracking is deferred to the Redis Lua Script phase.

## Phase 13. Coupon Traffic Control - Redis Lua Script

Status: Planned

Goal:

Use a Redis Lua script to atomically check stock and duplicate state in Redis.

Expected behavior:

- Redis should atomically reject requests after stock exhaustion.
- Redis should reject repeated user-coupon requests when duplicate control is included.
- DB consistency after Redis acceptance still needs explicit handling.

## Phase 14. Product and Order Domains

Status: Planned

Goal:

Implement product lookup, order creation, coupon application to payment, and coupon usage consistency experiments after coupon issuance strategies are documented clearly.
