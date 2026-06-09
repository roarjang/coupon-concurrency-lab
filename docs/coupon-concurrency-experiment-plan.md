# Coupon Concurrency Experiment Plan

## 1. Purpose

This document defines the concurrency experiment roadmap for first-come coupon issuance.

The goal is to reproduce and solve overselling and duplicate issuance problems under concurrent requests. The structure follows the Point domain experiment style: start with a simple transaction-only baseline, observe the failure, then compare multiple consistency strategies.

This document started as a design proposal. The transaction-only coupon baseline, DB unique constraint duplicate-prevention experiment, pessimistic-lock stock-control experiment, optimistic-lock stock-control experiment, and atomic-update stock-control experiment have now been implemented, and the observed results are recorded below. Later Redis-based stock-control strategies remain planned experiments.

## 2. Target Scenario

The main target is first-come coupon issuance.

A coupon campaign has limited stock. Many users request issuance at the same time. The system must issue coupons only up to the available quantity and reject the rest.

Common test scenario:

- Coupon stock: 100
- Concurrent requests: 1,000
- Request type: issue the same coupon
- User condition: preferably 1,000 different users for overselling tests

Expected correct result:

- successCount = 100
- failCount = 900
- finalIssuedQuantity = 100
- issuedCouponCount = 100
- finalIssuedQuantity must not exceed totalQuantity

Duplicate issuance scenario:

- Coupon stock: enough to avoid stock exhaustion as the main failure
- Concurrent requests: multiple requests from the same user for the same coupon
- Expected issued count for that user and coupon: 1

Both scenarios matter. Overselling protects total stock. Duplicate prevention protects the user-coupon uniqueness rule.

## 3. Correctness Criteria

Each strategy should be tested using final database state, not only request success/failure counters.

Core verification points:

- Success count must not exceed coupon stock.
- Final issued quantity must not exceed total quantity.
- Number of IssuedCoupon records must match successful issuance count.
- A user must not have duplicate IssuedCoupon records for the same Coupon.
- Failure count must explain rejected requests.
- Inventory and issued records must remain consistent after the test.

For the common stock test:

- successCount = 100
- failCount = 900
- finalIssuedQuantity = 100
- issuedCouponCount = 100

## 4. Strategy Roadmap

| Strategy | Goal | Expected conclusion |
| --- | --- | --- |
| Transaction-only Baseline | Reproduce overselling and duplicate issuance | Completed: `@Transactional` alone is not enough under high contention |
| DB Unique Constraint | Prevent duplicate issuance for the same user and coupon | Completed: database uniqueness is the final duplicate guard |
| Pessimistic Lock | Serialize coupon row updates | Completed: stock remains consistent with lock wait cost |
| Optimistic Lock | Detect concurrent version conflicts | Completed: stock remains consistent by rejecting conflicting updates through `Coupon.version` |
| Atomic Update | Use conditional database update | Completed: stock remains consistent by letting PostgreSQL atomically apply the stock condition and increment |
| Redis Counter | Use Redis as a fast front-line stock gate | Planned: efficient traffic control, but DB reconciliation must be considered |
| Redis Lua Script | Atomically check stock and duplicate state in Redis | Planned: stronger Redis-side atomicity, more operational complexity |

## 5. Strategy 1: Transaction-only Baseline

How it works:

- Start a service-level transaction.
- Read Coupon by id.
- Check whether `issuedQuantity < totalQuantity`.
- Check whether the user already has the coupon.
- Increase `issuedQuantity`.
- Save IssuedCoupon.
- Commit transaction.

Why it may not solve overselling:

Multiple transactions can read the same `issuedQuantity` before any of them commits. Each transaction can decide that stock remains, then issue the coupon. This can cause overselling or lost updates.

Why it may not solve duplicate issuance:

Two concurrent requests from the same user can both pass an application-level duplicate check before either inserts the IssuedCoupon record. A database unique constraint is needed as the final guard.

Advantages:

- Simple implementation.
- Useful for reproducing the failure.
- Good baseline for portfolio explanation.

Disadvantages:

- Does not serialize concurrent access.
- Can oversell.
- Can allow duplicate issuance without database constraints.
- Final inventory and issued records can become inconsistent.

What should be verified:

- Whether success count exceeds stock.
- Whether `issuedQuantity` exceeds `totalQuantity`.
- Whether IssuedCoupon count exceeds stock.
- Whether duplicate records are created for the same user and coupon.
- Whether `issuedQuantity` and IssuedCoupon count diverge.

Observed baseline result:

The transaction-only baseline has reproduced both target failures.

Overselling experiment:

- Test scenario:
  - Coupon stock: 100
  - Concurrent requests: 1,000
  - Users: 1,000 distinct users
- Expected result:
  - successCount = 100
  - failCount = 900
  - issuedCouponCountByCoupon = 100
  - finalIssuedQuantity = 100
- Observed result:
  - successCount = 1000
  - failCount = 0
  - issuedCouponCountByCoupon = 1000
  - finalIssuedQuantity = 100

This is a concurrency failure because 1,000 issued coupon records were created for a coupon with stock 100. The final `issuedQuantity` stayed at 100, so the Coupon aggregate state and the IssuedCoupon records also diverged. `@Transactional` did not serialize the stock check and increment across concurrent requests.

Duplicate issuance experiment:

- Test scenario:
  - Coupon stock: 1,000
  - Concurrent requests: 100
  - Users: same user repeated 100 times for the same coupon
- Expected result:
  - successCount = 1
  - failCount = 99
  - issuedCouponCountByUserAndCoupon = 1
- Observed result:
  - successCount = 10
  - failCount = 90
  - issuedCouponCountByUserAndCoupon = 10

This is a concurrency failure because the same user received the same coupon 10 times. Multiple requests passed the application-level duplicate check before a committed insert became visible, showing that application checks alone are insufficient.

Observed conclusion:

- `@Transactional` alone does not serialize access to the Coupon row.
- The stock check and `issuedQuantity` increment can race.
- The duplicate check and IssuedCoupon insert can race.
- Final database assertions are required because request counters alone can hide inventory-record divergence.
- A database unique constraint on `(userId, couponId)` is required as the final guard for duplicate issuance.

The baseline reproduction tests are now disabled in the current test suite and preserved for history/docs:

- The duplicate-issuance reproduction test is disabled because the DB unique constraint intentionally prevents that failure in the current schema.
- The overselling reproduction test is disabled because adding `Coupon.version` for the optimistic-lock experiment changes the transaction-only stock path through JPA version checking.

The baseline results above are historical observations from before those model/schema changes.

## 6. Strategy 2: DB Unique Constraint

Status: Completed.

How it works:

- Keep the application-level duplicate check as a fast pre-check.
- Add a database UNIQUE constraint on `(userId, couponId)`.
- Attempt to save the IssuedCoupon record inside the transaction.
- If a concurrent insert already committed the same user-coupon pair, the database rejects the duplicate insert.
- Convert the duplicate-key persistence failure into a duplicate issuance failure.

Why it solves duplicate issuance:

The application-level check can still race, but the database constraint is evaluated at insert time. Only one row for the same user and coupon can commit.

Schema verification note:

`spring.jpa.hibernate.ddl-auto=update` may not automatically add a new UNIQUE constraint to an already existing table.
The actual database schema was therefore checked with `psql` using `\d issued_coupons` to confirm that the unique constraint existed before relying on the experiment result.

Observed result:

- Test scenario:
  - Coupon stock: 1,000
  - Concurrent requests: 100
  - Users: same user repeated 100 times for the same coupon
- Expected result:
  - successCount = 1
  - failCount = 99
  - issuedCouponCountByUserAndCoupon = 1
- Observed result:
  - successCount = 1
  - failCount = 99
  - issuedCouponCountByUserAndCoupon = 1

Why the result occurred:

Multiple requests can still pass the duplicate pre-check before the first insert is committed. The difference is that the database now rejects every duplicate `(userId, couponId)` insert after the first successful one. This makes duplicate prevention deterministic at the persistence boundary.

Scope limitation:

This strategy solves duplicate issuance for the same user and coupon. It does not solve overselling or inventory-record mismatch for many distinct users requesting the same limited-stock coupon.

## 7. Strategy 3: Pessimistic Lock

Status: Completed.

How it works:

- Read the Coupon row with a database write lock.
- Only one transaction can update the same Coupon row at a time.
- Check stock while holding the lock.
- Increase `issuedQuantity`.
- Create IssuedCoupon.
- Commit transaction and release the lock.

Why it can solve overselling:

Concurrent issuance requests for the same Coupon are serialized. Each request sees the latest committed `issuedQuantity` before deciding whether stock remains.

Advantages:

- Strong consistency.
- Easy to reason about.
- Preserves domain-level validation logic.
- Produces deterministic stock results under high contention.

Disadvantages:

- Requests wait for the same Coupon row lock.
- Throughput can drop under high contention.
- Lock timeout or deadlock risk must be considered in larger workflows.

What should be verified:

- successCount = coupon stock.
- failCount = concurrent requests minus stock.
- finalIssuedQuantity = totalQuantity.
- issuedCouponCount = totalQuantity.
- No duplicate IssuedCoupon records.
- Lock strategy does not create inventory-record mismatch.

Observed result:

- Test scenario:
  - Coupon stock: 100
  - Concurrent requests: 1,000
  - Users: 1,000 distinct users
  - Lock hold delay for contention observation: `PESSIMISTIC_LOCK_HOLD_MILLIS = 5L`
- Expected result:
  - successCount = 100
  - failCount = 900
  - issuedCouponCountByCoupon = 100
  - finalIssuedQuantity = 100
- Observed result:
  - successCount = 100
  - failCount = 900
  - issuedCouponCountByCoupon = 100
  - finalIssuedQuantity = 100
  - test duration: about 10 seconds

Why the result occurred:

The Coupon row is locked before checking stock and increasing `issuedQuantity`.
Concurrent requests for the same Coupon wait for the lock, so each transaction observes the latest committed issued count.
After 100 successful issuances, later transactions see exhausted stock and fail without creating additional IssuedCoupon records.

Scope limitation:

This strategy solves stock overselling and inventory-record mismatch for one Coupon row under the tested scenario.
It does not replace duplicate issuance control. A same-user duplicate request still needs the DB unique constraint on `(userId, couponId)` as the final guard.

Comparison point for later strategies:

The result is deterministic and easy to reason about, but the measured test duration was about 10 seconds with a small 5 ms lock-hold delay under 1,000 concurrent requests.
Optimistic lock, atomic update, Redis counter, and Redis Lua experiments should be compared against this result in terms of correctness, throughput, retry behavior, and operational complexity.

## 8. Strategy 4: Optimistic Lock

Status: Completed.

How it works:

- Add a `@Version` field to Coupon.
- Requests read Coupon without locking.
- Each transaction tries to update Coupon using the version it read.
- If another transaction already changed the row, the version check fails.
- Failed requests can either stop or retry, depending on the experiment phase.

Why it can solve overselling:

Optimistic lock prevents silent overwrites of `issuedQuantity`. Conflicting updates are rejected rather than lost.

Why retry still matters:

Under high contention, many requests can read the same version. Conflicting commits fail with optimistic locking exceptions. In the current test run, enough sequential commits succeeded to exhaust stock, but the phase still does not implement retry. A production-facing first-come issuance flow should define whether failed conflicts are returned to the user or retried.

Advantages:

- No long database lock wait.
- Good when conflicts are rare.
- Makes lost update visible.
- Useful for comparing conflict detection versus conflict prevention.

Disadvantages:

- High contention can cause many failures.
- Retry logic is usually needed for user-facing first-come issuance.
- Adding version affects all updates of the Coupon entity.

What should be verified:

- Optimistic lock exceptions occur under contention.
- No silent lost update occurs.
- Final issued quantity matches successful issuance count.
- Final issued quantity does not exceed total quantity.
- Behavior with and without retry should be documented separately.

Observed result:

- Test scenario:
  - Coupon stock: 100
  - Concurrent requests: 1,000
  - Users: 1,000 distinct users
  - Delay for contention observation: `LOCK_HOLD_MILLIS = 5L`
- Expected result:
  - successCount = 100
  - failCount = 900
  - issuedCouponCountByCoupon = 100
  - finalIssuedQuantity = 100
- Observed result:
  - successCount = 100
  - failCount = 900
  - issuedCouponCountByCoupon = 100
  - finalIssuedQuantity = 100

Why the result occurred:

`Coupon.version` makes concurrent updates visible. Requests that try to commit with a stale version fail instead of overwriting another transaction's `issuedQuantity` update.
Only committed transactions create durable issued coupon records, so the Coupon row and IssuedCoupon records stay aligned.

Current scope:

This phase verifies optimistic-lock conflict detection and final database consistency without retry handling.
Adding `@Version` affects all Coupon update paths, so the original transaction-only overselling baseline is treated as a historical pre-version result and its test is disabled in the current suite.

## 9. Strategy 5: Atomic Update

Status: Completed.

How it works:

- Use one conditional database update to increase `issuedQuantity`.
- The update succeeds only when `issuedQuantity < totalQuantity`.
- The update count determines success or failure.
- Create IssuedCoupon only after the inventory update succeeds.
- Update `updatedAt` directly in the bulk update query.

Why it can solve overselling:

The stock condition and inventory update happen together in the database. Requests that arrive after stock is exhausted update zero rows and fail.

Advantages:

- Efficient for simple stock decrement or issued count increment.
- Avoids read-modify-write in application code.
- Short database operation.
- Produces deterministic stock-limited success count.

Disadvantages:

- Business logic moves into a query.
- More difficult to express complex domain rules.
- Must carefully coordinate inventory update and IssuedCoupon insert in one transaction.
- Duplicate issuance still requires a unique constraint or separate atomic duplicate control.

What should be verified:

- successCount = coupon stock.
- failCount = concurrent requests minus stock.
- finalIssuedQuantity = totalQuantity.
- issuedCouponCount = totalQuantity.
- No duplicate records exist.
- If IssuedCoupon insert fails, inventory consistency is still handled correctly.

Important design point:

Atomic stock update alone does not fully solve duplicate issuance. The design still needs a unique constraint on `(userId, couponId)` and clear transaction behavior when the insert fails.

Observed result:

- Test scenario:
  - Coupon stock: 100
  - Concurrent requests: 1,000
  - Users: 1,000 distinct users
  - Delay for contention observation: `LOCK_HOLD_MILLIS = 5L`
- Expected result:
  - successCount = 100
  - failCount = 900
  - issuedCouponCountByCoupon = 100
  - finalIssuedQuantity = 100
- Observed result:
  - successCount = 100
  - failCount = 900
  - issuedCouponCountByCoupon = 100
  - finalIssuedQuantity = 100

Why the result occurred:

PostgreSQL evaluates `issuedQuantity < totalQuantity` and increments `issuedQuantity` as one atomic update. Under 1,000 concurrent requests for stock 100, only 100 update statements affect a row. The remaining requests update zero rows and fail before creating IssuedCoupon records.

The atomic update differs from pessimistic lock because it does not first read the Coupon row with a write lock and hold that lock through domain-level stock validation. It also differs from optimistic lock because it does not load a Coupon entity and then rely on JPA version-conflict detection during flush. The database update count is the stock decision.

Bulk update note:

The main point of this experiment is stock consistency. `updatedAt` is a secondary detail, but it is still documented because JPQL bulk updates bypass entity lifecycle callbacks such as `@PreUpdate`. The query therefore updates `updatedAt` directly with `CURRENT_TIMESTAMP`. The query also increments `version` directly because the Coupon entity has a version column from the optimistic-lock phase.

Scope limitation:

This phase controls aggregate stock for distinct-user issuance. It does not solve duplicate issuance by itself. Duplicate issuance remains the responsibility of the DB unique constraint on `(userId, couponId)`.

The stock-control test uses different users, so duplicate-key contention is not the behavior under test. In this phase, saving the IssuedCoupon record with `save` is enough; `saveAndFlush` is not required to force duplicate detection during the stock-control scenario.

## 10. Strategy 6: Redis Counter

Status: Planned.

How it works:

- Redis keeps a counter for issued coupon requests.
- Each request increments the Redis counter.
- If the counter is within stock, the request is allowed to proceed to DB persistence.
- If the counter exceeds stock, the request is rejected quickly.
- PostgreSQL remains the final source of truth for persisted Coupon and IssuedCoupon state.

Why it can solve overselling at the front line:

Redis atomic increment can limit the number of accepted requests before they reach the database. This reduces database load during high traffic.

Why it may not fully solve consistency:

Redis and the database are separate systems. A request can be accepted by Redis but fail during DB persistence. Without compensation, Redis count and DB issued records can diverge.

Advantages:

- Very fast.
- Reduces DB contention.
- Good for large first-come events.
- Simple mental model for stock gating.

Disadvantages:

- Requires Redis operational reliability.
- Redis and DB consistency must be handled.
- Duplicate issuance may still require DB unique constraint or Redis-side user tracking.
- Compensation or reconciliation may be needed.

What should be verified:

- Redis accepted count does not exceed stock.
- DB issued count matches accepted successful persistence.
- Final `Coupon.issuedQuantity` matches DB issued count.
- Failed DB persistence is handled or documented.
- Requests after stock exhaustion are rejected quickly.
- Duplicate requests are not accepted if duplicate control is included.

Planned experiment scope:

- Test scenario:
  - Coupon stock: 100
  - Concurrent requests: 1,000
  - Users: 1,000 distinct users
- Redis role:
  - Use Redis as a fast stock gate before database persistence.
  - Count accepted stock slots with an atomic Redis operation.
  - Reject requests whose Redis sequence is greater than coupon stock.
- Database role:
  - Persist successful issuance records in PostgreSQL.
  - Keep the DB unique constraint on `(userId, couponId)` as the duplicate issuance guard.
  - Keep database-side stock consistency as the final verification target.
- Expected result:
  - successCount = 100
  - failCount = 900
  - issuedCouponCountByCoupon = 100
  - finalIssuedQuantity = 100
  - Redis accepted count = 100

Initial implementation direction:

The first Redis Counter phase should focus on stock gating for distinct-user issuance, not Redis-side duplicate tracking.
The service can use Redis to decide whether a request is allowed to reach DB persistence, while PostgreSQL remains responsible for durable state.
If DB persistence fails after Redis accepts a request, the phase should either document the mismatch risk or add an explicit compensation path. That decision should be recorded with the observed result.

Duplicate issuance scope:

Redis Counter alone does not solve duplicate issuance. It only limits the number of accepted stock slots.
Same-user duplicate requests still require the DB unique constraint on `(userId, couponId)` unless Redis user tracking is added in a later strategy.
Redis Lua Script is the planned follow-up for atomically checking stock and duplicate state together in Redis.

Recommended role:

Redis Counter should be treated as a front-line traffic control strategy, not the only source of truth, unless the design explicitly handles reconciliation.

## 11. Strategy 7: Redis Lua Script

How it works:

Redis Lua executes multiple Redis operations atomically in a single script.

The script can check:

- Whether stock remains.
- Whether the user has already received the coupon.
- Whether to mark the user as issued.
- Whether to increment the issued count.

Why it can solve overselling and duplicate issuance in Redis:

The check and update happen atomically inside Redis. No other Redis command can interleave while the script is executing.

Advantages:

- Strong Redis-side atomicity.
- Can handle stock and duplicate checks together.
- Very fast under high traffic.
- Reduces DB write pressure during request spikes.

Disadvantages:

- More complex than a simple Redis counter.
- Business logic moves into Lua.
- Redis and DB consistency still needs design.
- Script versioning, testing, and observability require care.

What should be verified:

- Redis accepted count does not exceed stock.
- Same user cannot be accepted twice.
- DB persistence matches Redis accepted users.
- Failure after Redis success is handled or explicitly documented.
- Reconciliation strategy is clear.

Recommended role:

Redis Lua Script is suitable for high-traffic first-come issuance where stock and duplicate checks must be fast and atomic before database persistence.

## 12. Test Data Design

Overselling test:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users
- Expected success: 100
- Expected failure: 900

Duplicate issuance test:

- Coupon stock: high enough to avoid stock exhaustion as the main problem
- Concurrent requests: 100 or more
- User: same user
- Coupon: same coupon
- Expected issued records for that user and coupon: 1

Combined test:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: mixture of repeated and distinct users
- Expected behavior: stock is not exceeded and duplicate issuance is not allowed

The first implementation started with separate tests for overselling and duplicate issuance. The transaction-only baseline reproduced both failures.
The DB unique constraint experiment then verified duplicate issuance prevention. Pessimistic lock and optimistic lock then verified stock-control behavior for distinct-user issuance. Combined tests remain a later comparison point after each stock-control strategy is evaluated.

## 13. Suggested Experiment Order

Recommended order:

1. Transaction-only baseline for overselling. Completed.
2. Transaction-only baseline for duplicate issuance. Completed.
3. Add database unique constraint for duplicate issuance. Completed.
4. Pessimistic lock for stock control. Completed.
5. Optimistic lock for stock control without retry. Completed.
6. Atomic update for stock control. Completed.
7. Redis counter for traffic gating. Planned.
8. Redis Lua script for stock and duplicate checks. Planned.

This order keeps the learning path clear. It first shows what breaks, then adds one consistency mechanism at a time.
Steps 1 through 6 have been completed. The experiment order remains unchanged for the remaining Redis-based strategy comparisons.

## 14. Expected Portfolio Narrative

The Coupon experiment should demonstrate that first-come issuance is not only a CRUD problem.

The portfolio explanation should show:

- How overselling happens under concurrent requests.
- Why duplicate issuance needs both application logic and database constraints.
- Why `@Transactional` alone is insufficient.
- How database locks, optimistic versioning, conditional updates, and Redis atomic operations differ.
- Why Redis can reduce traffic but introduces DB synchronization concerns.
- How each strategy changes correctness, throughput, complexity, and operational risk.

The final implementation should make it easy for an interviewer to compare strategies by reading the tests and final database assertions.
