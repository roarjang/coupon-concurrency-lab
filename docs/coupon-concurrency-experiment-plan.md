# Coupon Concurrency Experiment Plan

## 1. Purpose

This document defines the concurrency experiment roadmap for first-come coupon issuance.

The goal is to reproduce and solve overselling and duplicate issuance problems under concurrent requests. The structure follows the Point domain experiment style: start with a simple transaction-only baseline, observe the failure, then compare multiple consistency strategies.

This document is a design proposal. It does not define implementation code.

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
| Transaction-only Baseline | Reproduce overselling or lost update | `@Transactional` alone is not enough under high contention |
| Pessimistic Lock | Serialize coupon row updates | Strong consistency with lock wait cost |
| Optimistic Lock | Detect concurrent version conflicts | Prevents silent lost update, but may fail many requests without retry |
| Atomic Update | Use conditional database update | Efficient for stock decrement or issued count increment |
| Redis Counter | Use Redis as a fast front-line stock gate | Efficient traffic control, but DB reconciliation must be considered |
| Redis Lua Script | Atomically check stock and duplicate state in Redis | Stronger Redis-side atomicity, more operational complexity |

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

## 6. Strategy 2: Pessimistic Lock

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

## 7. Strategy 3: Optimistic Lock

How it works:

- Add a version field to Coupon.
- Requests read Coupon without locking.
- Each transaction tries to update Coupon using the version it read.
- If another transaction already changed the row, the version check fails.
- Failed requests can either stop or retry, depending on the experiment phase.

Why it can solve overselling:

Optimistic lock prevents silent overwrites of `issuedQuantity`. Conflicting updates are rejected rather than lost.

Why it may not produce 100 success without retry:

Under high contention, many requests can read the same version. Only one or a few may commit successfully; the others can fail with optimistic locking exceptions. Without retry, success count may be lower than stock.

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

Recommended experiment split:

- First test: optimistic lock without retry, focused on conflict detection.
- Later test: optimistic lock with bounded retry, focused on reaching stock-limited success count.

## 8. Strategy 4: Atomic Update

How it works:

- Use one conditional database update to increase `issuedQuantity`.
- The update succeeds only when `issuedQuantity < totalQuantity`.
- The update count determines success or failure.
- Create IssuedCoupon only after the inventory update succeeds.

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

## 9. Strategy 5: Redis Counter

How it works:

- Redis keeps a counter for issued coupon requests.
- Each request increments the Redis counter.
- If the counter is within stock, the request is allowed to proceed to DB persistence.
- If the counter exceeds stock, the request is rejected quickly.

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
- Failed DB persistence is handled or documented.
- Requests after stock exhaustion are rejected quickly.
- Duplicate requests are not accepted if duplicate control is included.

Recommended role:

Redis Counter should be treated as a front-line traffic control strategy, not the only source of truth, unless the design explicitly handles reconciliation.

## 10. Strategy 6: Redis Lua Script

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

## 11. Test Data Design

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

The first implementation should start with separate tests for overselling and duplicate issuance. Combined tests can be added after each problem is understood independently.

## 12. Suggested Experiment Order

Recommended order:

1. Transaction-only baseline for overselling.
2. Transaction-only baseline for duplicate issuance.
3. Add database unique constraint for duplicate issuance.
4. Pessimistic lock for stock control.
5. Optimistic lock for stock control without retry.
6. Atomic update for stock control.
7. Redis counter for traffic gating.
8. Redis Lua script for stock and duplicate checks.

This order keeps the learning path clear. It first shows what breaks, then adds one consistency mechanism at a time.

## 13. Expected Portfolio Narrative

The Coupon experiment should demonstrate that first-come issuance is not only a CRUD problem.

The portfolio explanation should show:

- How overselling happens under concurrent requests.
- Why duplicate issuance needs both application logic and database constraints.
- Why `@Transactional` alone is insufficient.
- How database locks, optimistic versioning, conditional updates, and Redis atomic operations differ.
- Why Redis can reduce traffic but introduces DB synchronization concerns.
- How each strategy changes correctness, throughput, complexity, and operational risk.

The final implementation should make it easy for an interviewer to compare strategies by reading the tests and final database assertions.
