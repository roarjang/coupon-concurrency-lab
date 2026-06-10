# Coupon Domain Design

## 1. Problem Statement

First-come coupon issuance is a high-contention consistency problem.

The business rule is simple: a coupon campaign has a limited quantity, and only that number of users should receive the coupon. Under concurrent requests, however, many users can try to issue the same coupon at the same time.

If the system reads the current issued count, checks availability, and then inserts an issued coupon without proper concurrency control, several requests can observe the same available stock. This can lead to overselling, where more coupons are issued than the campaign quantity allows.

The Coupon domain must also prevent duplicate issuance to the same user. Even if total stock is protected, one user should not receive the same coupon more than once.

The main consistency goals are:

- Do not issue more coupons than the configured coupon quantity.
- Do not issue the same coupon more than once to the same user.
- Keep coupon inventory and issued coupon records consistent.
- Make failure cases explicit and explainable.

## 2. Business Requirements

The first implementation should focus on coupon issuance, not full payment integration.

Core requirements:

- An administrator or test setup can create a coupon campaign.
- A coupon has a limited total quantity.
- A user can request coupon issuance.
- A user can receive the coupon only if stock remains.
- A user can receive the same coupon only once.
- The system records successful coupon issuance.
- The system rejects requests when stock is exhausted.
- The system rejects duplicate issuance attempts for the same user and coupon.

Out of scope for the first Coupon phase:

- Coupon application to orders.
- Coupon expiration handling beyond basic field design.
- Discount calculation in payment.
- Redis-based production traffic control.
- Distributed lock implementation.

## 3. Coupon Entity Design

The Coupon entity represents a coupon campaign or coupon definition.

Implemented fields:

| Field | Purpose |
| --- | --- |
| id | Primary key |
| name | Coupon campaign name |
| discountAmount | Fixed discount amount |
| totalQuantity | Maximum number of coupons that can be issued |
| issuedQuantity | Number of coupons already issued |
| version | Version field for optimistic lock experiments |
| createdAt | Creation timestamp |
| updatedAt | Update timestamp |

Planned extension fields:

| Field | Purpose |
| --- | --- |
| status | Coupon campaign status such as ACTIVE, INACTIVE, ENDED |
| issueStartAt | Start time for issuance |
| issueEndAt | End time for issuance |
| expiredAt | Expiration time for issued coupons |

Important invariants:

- `totalQuantity` must be greater than or equal to 0.
- `issuedQuantity` must be greater than or equal to 0.
- `issuedQuantity` must not exceed `totalQuantity`.
- Only active coupons within the issue period can be issued.

The first implementation keeps status and time validation out of scope. Those fields remain planned so later payment and expiration workflows can build on them. Optimistic locking is now represented by the implemented `version` field.

## 4. IssuedCoupon Entity Design

The IssuedCoupon entity represents the coupon owned by a specific user.

Implemented fields:

| Field | Purpose |
| --- | --- |
| id | Primary key |
| userId | Owner user id |
| couponId | Coupon campaign id |
| status | Issued coupon status such as ISSUED, USED, EXPIRED |
| issuedAt | Issuance timestamp |
| usedAt | Usage timestamp |

Important constraints:

- `(userId, couponId)` is unique.
- A user can have at most one IssuedCoupon for the same Coupon.
- Status starts as ISSUED.
- USED and EXPIRED statuses are planned for payment and lifecycle phases.

The unique constraint is important because application-level duplicate checks alone can fail under concurrent requests. Even if two requests pass a duplicate check at the same time, the database rejects all but one committed row for the same user and coupon.

## 5. Relationship Between User, Coupon, and IssuedCoupon

The relationship is:

- User can own many IssuedCoupons.
- Coupon can be issued to many users.
- IssuedCoupon connects one User and one Coupon.

Conceptually:

| Relationship | Meaning |
| --- | --- |
| User to IssuedCoupon | One user can receive multiple different coupons |
| Coupon to IssuedCoupon | One coupon campaign can be issued to many users |
| User and Coupon | Many-to-many through IssuedCoupon |

The implementation can initially store `userId` and `couponId` as scalar ids rather than object relationships. This keeps the experiment focused on concurrency behavior and avoids unnecessary object graph complexity.

## 6. Coupon Inventory Management Approach

The Coupon entity should track inventory using:

- `totalQuantity`
- `issuedQuantity`

The basic issuance decision is:

- If `issuedQuantity < totalQuantity`, issuance can proceed.
- If `issuedQuantity >= totalQuantity`, issuance must fail.

The concurrency challenge is that this check and the increment must be protected.

Several strategies will be compared:

- Transaction-only read-modify-write baseline. Completed for failure reproduction.
- DB unique constraint on `(userId, couponId)`. Completed for duplicate issuance prevention.
- Pessimistic lock on the Coupon row. Completed for stock control.
- Optimistic lock using Coupon version. Completed for stock control.
- Atomic update using a conditional update query. Completed for stock control.
- Redis counter for front-line traffic control. Completed.
- Redis Lua script for atomic stock and duplicate control.

The first database-centered strategies should update `issuedQuantity` and create `IssuedCoupon` inside a transaction. The Redis Counter strategy has now been evaluated as a high-throughput front-line gate, while still persisting final issuance records in the database.

Observed pessimistic-lock stock-control result:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users
- Lock hold delay for contention observation: `PESSIMISTIC_LOCK_HOLD_MILLIS = 5L`
- Expected result: successCount = 100, failCount = 900, issuedCouponCountByCoupon = 100, finalIssuedQuantity = 100
- Observed result: successCount = 100, failCount = 900, issuedCouponCountByCoupon = 100, finalIssuedQuantity = 100
- Test duration: about 10 seconds

The pessimistic lock strategy solves stock overselling by serializing concurrent updates to the same Coupon row.
It also keeps `Coupon.issuedQuantity` aligned with the number of IssuedCoupon records for the tested stock scenario.
It does not replace the duplicate-issuance guard, because duplicate issuance is a user-coupon uniqueness problem. The DB unique constraint remains the final protection for `(userId, couponId)`.

Observed optimistic-lock stock-control result:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users
- Delay for contention observation: `LOCK_HOLD_MILLIS = 5L`
- Expected result: successCount = 100, failCount = 900, issuedCouponCountByCoupon = 100, finalIssuedQuantity = 100
- Observed result: successCount = 100, failCount = 900, issuedCouponCountByCoupon = 100, finalIssuedQuantity = 100

The optimistic lock strategy uses `Coupon.version` to reject stale concurrent updates instead of allowing silent lost update.
It keeps `Coupon.issuedQuantity` aligned with IssuedCoupon records in the tested stock scenario.
This phase does not implement retry handling; it verifies conflict detection and final database consistency.

Observed atomic-update stock-control result:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users
- Delay for contention observation: `LOCK_HOLD_MILLIS = 5L`
- Expected result: successCount = 100, failCount = 900, issuedCouponCountByCoupon = 100, finalIssuedQuantity = 100
- Observed result: successCount = 100, failCount = 900, issuedCouponCountByCoupon = 100, finalIssuedQuantity = 100

The atomic update strategy uses a conditional bulk update so PostgreSQL increments `issuedQuantity` only when `issuedQuantity < totalQuantity`.
The affected row count becomes the stock availability decision, so requests that arrive after stock exhaustion fail without creating IssuedCoupon records.
This differs from pessimistic locking because the service does not hold a row lock across a read-modify-write flow.
It differs from optimistic locking because the service does not load a Coupon entity and wait for JPA version conflict detection at flush time.

The main purpose of this phase is stock consistency. `updatedAt` is secondary, but the implementation updates it directly in the query because bulk updates bypass `@PreUpdate`.
The phase does not solve duplicate issuance by itself. The DB unique constraint on `(userId, couponId)` remains responsible for same-user duplicate prevention.
Because the stock-control test uses different users, IssuedCoupon `save` is enough in this phase; `saveAndFlush` is not required to force duplicate-key detection.

## 7. Assumptions and Constraints

Assumptions:

- User signup and authentication already exist.
- The Point domain concurrency experiments are complete.
- Coupon issuance transaction-only baseline is implemented.
- Overselling and duplicate issuance have been reproduced with the transaction-only baseline.
- DB unique constraint duplicate-prevention experiment is implemented and verified.
- Pessimistic lock stock-control experiment is implemented and verified.
- Optimistic lock stock-control experiment is implemented and verified.
- Atomic update stock-control experiment is implemented and verified.
- Redis Counter stock-gate experiment is implemented and verified.
- Redis Lua Script remains a later planned strategy for Redis-side stock and duplicate checks.
- Product, Order, and payment coupon usage are planned but not part of the first Coupon issuance phase.
- PostgreSQL is the main consistency store.
- Redis is configured for the Redis Counter coupon strategy.

Constraints:

- Do not issue more coupons than `totalQuantity`.
- Do not allow duplicate issuance for the same user and coupon.
- Keep strategy implementations separable for comparison.
- Prefer simple, explainable designs over early abstraction.
- Tests should verify final database state, not just request results.

## 8. API Candidates

These are high-level API candidates only. Exact DTOs and controllers should be designed during implementation.

| API | Purpose |
| --- | --- |
| Create coupon | Create test/admin coupon data |
| Get coupon | Retrieve coupon campaign information |
| Issue coupon | Request first-come coupon issuance |
| Get my issued coupons | List coupons owned by the authenticated user |

The first concurrency experiment can focus on service-level tests before exposing all APIs.

Suggested first implementation priority:

1. Coupon creation for test setup.
2. Coupon issuance service method.
3. IssuedCoupon persistence.
4. Concurrency tests for overselling and duplicate issuance.

## 9. Data Consistency Concerns

Coupon issuance has two main consistency dimensions.

Inventory consistency:

- Total issued count must not exceed total quantity.
- `Coupon.issuedQuantity` must match the number of successful issued coupon records, or at least be explainably reconciled if asynchronous processing is introduced later.

Duplicate consistency:

- A user must not receive the same coupon more than once.
- The database enforces uniqueness with `(userId, couponId)`.
- The observed DB unique constraint result was successCount = 1, failCount = 99, and issuedCouponCountByUserAndCoupon = 1 under 100 concurrent duplicate requests.
- In a `ddl-auto=update` environment, an added UNIQUE constraint may not be applied automatically to an existing table, so the actual `issued_coupons` schema was verified with `psql` using `\d issued_coupons`.

Transaction consistency:

- Updating coupon inventory and creating IssuedCoupon should happen as one logical operation.
- If IssuedCoupon creation fails, inventory should not be permanently increased.
- If inventory update fails, IssuedCoupon should not be created.

Redis consistency:

- Redis may accept or reject requests faster than the database.
- The design must consider what happens if Redis succeeds but DB persistence fails.
- Compensation or reconciliation may be needed for production-grade designs.

Redis Counter result:

- Redis is used as a front-line stock gate for first-come issuance.
- The first Redis phase uses distinct users and focuses on stock control, matching the database-centered stock tests.
- Observed scenario: coupon stock 100, concurrent requests 1,000, successCount 100, failCount 900, issuedCouponCountByCoupon 100, finalIssuedQuantity 100, redisCounterValue 100.
- PostgreSQL remains the durable source of truth for Coupon and IssuedCoupon records.
- The DB unique constraint on `(userId, couponId)` remains responsible for duplicate issuance prevention.
- Redis Counter does not track per-user issuance in this phase.
- If Redis accepts a request but DB persistence fails, the implementation compensates by decrementing the Redis counter.
- The DB persistence step uses the conditional update query instead of `coupon.issue()` because the Coupon entity has `@Version` from the optimistic-lock experiment.
- Redis Lua Script is the planned follow-up when stock and duplicate checks need to be atomic inside Redis.

## 10. Potential Concurrency Problems

Overselling:

Multiple requests read the same `issuedQuantity`, all see available stock, and all create issued coupons. Final issued count can exceed `totalQuantity`.
This has been reproduced with the transaction-only baseline: with stock 100 and 1,000 concurrent requests from distinct users, the observed result was successCount = 1000, failCount = 0, issuedCouponCountByCoupon = 1000, and finalIssuedQuantity = 100.
This is a historical baseline from before `Coupon.version` was added. The current overselling reproduction test is disabled because JPA version checking changes the transaction-only stock path.

Lost update:

Several transactions increment `issuedQuantity` from the same old value. Some increments are overwritten, causing inventory count and issued records to diverge.

Duplicate issuance:

The same user sends concurrent requests for the same coupon. Both requests pass the application-level duplicate check before either inserts the IssuedCoupon record.
This has been reproduced with the transaction-only baseline: with stock 1,000 and 100 concurrent requests from the same user, the observed result was successCount = 10, failCount = 90, and issuedCouponCountByUserAndCoupon = 10.

Duplicate issuance prevention:

The DB unique constraint prevents more than one IssuedCoupon row for the same user and coupon. With stock 1,000 and 100 concurrent requests from the same user, the observed result after applying the constraint was successCount = 1, failCount = 99, and issuedCouponCountByUserAndCoupon = 1.

Stock control with pessimistic lock:

The pessimistic lock prevents multiple transactions from checking and incrementing the same Coupon row at the same time. With stock 100 and 1,000 concurrent requests from distinct users, the observed result after applying the lock was successCount = 100, failCount = 900, issuedCouponCountByCoupon = 100, and finalIssuedQuantity = 100.

This is different from duplicate issuance prevention. Pessimistic lock protects the aggregate stock count for one Coupon row. The DB unique constraint protects the uniqueness of one user's IssuedCoupon for the same Coupon.

Stock control with optimistic lock:

The optimistic lock uses `Coupon.version` to detect concurrent updates to the same Coupon row. With stock 100 and 1,000 concurrent requests from distinct users, the observed result after applying optimistic locking was successCount = 100, failCount = 900, issuedCouponCountByCoupon = 100, and finalIssuedQuantity = 100.

This protects the aggregate stock count by rejecting stale updates rather than making transactions wait for a write lock. Duplicate issuance still remains the responsibility of the DB unique constraint on `(userId, couponId)`.

Inventory and issued record mismatch:

The system increments `issuedQuantity` but fails to create the IssuedCoupon record, or creates an IssuedCoupon without correctly updating inventory.

Redis and DB mismatch:

Redis may count a request as accepted, but database persistence may later fail. The Redis Counter phase compensates by decrementing the Redis counter when database persistence fails after Redis acceptance.
This keeps the tested result aligned, but it is not the same as one atomic commit boundary across Redis and PostgreSQL.

## 11. Design Direction

The Coupon domain should follow the same learning pattern as the Point domain:

1. Implement a transaction-only baseline and reproduce the problem.
2. Add database unique constraint for duplicate issuance.
3. Apply database locking and compare results.
4. Apply optimistic versioning and observe conflict behavior.
5. Apply conditional update for efficient database-side control.
6. Introduce Redis counter and Lua script strategies for first-come traffic control.

The first step has been completed for overselling and duplicate issuance. The second step has been completed for duplicate issuance prevention. The third step has been completed for stock control with a pessimistic lock. The fourth step has been completed for stock control with optimistic locking. The fifth step has been completed for stock control with atomic update. The Redis Counter part of the sixth step has also been completed. Redis Lua remains in the same experiment order and should be compared against the observed baseline, pessimistic-lock, optimistic-lock, atomic-update, and Redis-counter results.
