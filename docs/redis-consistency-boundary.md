# Redis and PostgreSQL Consistency Boundary

## 1. Purpose

This document explains the consistency boundary between Redis and PostgreSQL in the coupon issuance experiments.

Redis is used as a front-line acceptance gate. It can reject sold-out or duplicate requests before those requests reach the database persistence path. PostgreSQL remains the durable source of truth for coupon inventory and issued coupon records.

This document focuses on:

- where Redis decisions happen before PostgreSQL writes
- where Redis and PostgreSQL can diverge
- what compensation behavior is currently implemented
- which guarantees are delegated to PostgreSQL
- which recovery and reconciliation behaviors are not implemented yet

Detailed strategy comparison and observed result tables remain in `docs/coupon-concurrency-strategy-comparison.md`.

## 2. Why Redis Was Introduced

The database-only strategies already prevent the main consistency failures in the tested scenarios:

- DB Unique Constraint prevents duplicate issuance for the same `(user_id, coupon_id)`.
- Pessimistic Lock, Optimistic Lock, and Atomic Update prevent stock overselling under their respective consistency models.

Redis was introduced for front-line traffic control. The goal is to reject requests before they enter the database write path when the request can be rejected using Redis-side state.

Redis improves request filtering under high contention, but it does not replace PostgreSQL consistency guarantees. A request accepted by Redis must still be persisted successfully in PostgreSQL before it becomes a durable coupon issuance.

## 3. Current Implementation Scope

The Redis Counter and Redis Lua strategies are implemented in the coupon concurrency test fixture:

- `CouponIssueConcurrencyTest.TestCouponIssueService.issueWithRedisCounterGate(...)`
- `CouponIssueConcurrencyTest.TestCouponIssueService.issueWithRedisLua(...)`

The main application service, `CouponIssueService`, currently contains the transaction-only coupon issuance path. The Redis strategies are experiment paths used by concurrency tests, not production-facing service methods.

Implemented Redis experiment scope:

- Redis Counter stock gate for distinct-user coupon issuance.
- Redis Lua stock and duplicate gate for coupon issuance.
- PostgreSQL persistence after Redis acceptance.
- Compensation when Redis accepts a request and a runtime failure occurs inside the tested database persistence block.
- Final verification through database state and Redis state assertions in tests.

Intentionally out of scope in the current implementation:

- Redis key TTL policy.
- Redis rebuild from PostgreSQL.
- Scheduled reconciliation between Redis and PostgreSQL.
- Retry or idempotency token design.
- Monitoring and alerting for compensation failures.
- Production script deployment or script versioning.

## 4. Consistency Model

PostgreSQL owns durable coupon issuance state.

The durable state is represented by:

- `coupons.issued_quantity`
- `coupons.total_quantity`
- `issued_coupons`
- the unique constraint on `issued_coupons(user_id, coupon_id)`

The final duplicate guard is the database unique constraint named `uk_issued_coupon_user_coupon`.

The final stock-control operation used by Redis strategies is the conditional database update:

```sql
update coupons
set issued_quantity = issued_quantity + 1,
    version = version + 1,
    updated_at = current_timestamp
where id = :couponId
and issued_quantity < total_quantity
```

In code, this is represented by `CouponRepository.increaseIssuedQuantityIfStockAvailable(...)`.

Redis acceptance decisions are preliminary. A Redis-accepted request must still pass PostgreSQL stock persistence and issued coupon persistence. Redis state is used as a front-line acceptance gate, but PostgreSQL remains the durable issuance record and final source of truth.

## 5. Redis State Used By This Project

The Redis experiments use coupon-specific keys.

| Key | Used by | Meaning |
| --- | --- | --- |
| `coupon:issue:count:{couponId}` | Redis Counter, Redis Lua | Number of Redis-accepted stock slots for the coupon |
| `coupon:issue:users:{couponId}` | Redis Lua | Set of user ids accepted by Redis for the coupon |

Redis Counter uses only the count key. It does not track which users have already been accepted.

Redis Lua uses both keys. The count key tracks accepted stock slots, and the user set tracks Redis-side duplicate acceptance.

Current limitations:

- No TTL is configured for these keys.
- No rebuild process exists if Redis state is lost.
- No reconciliation job compares Redis state with PostgreSQL state.
- Redis state is scoped to the tested coupon id and is deleted by tests before each Redis scenario.

## 6. Redis Counter Flow

Redis Counter uses Redis as a stock-slot gate.

Acceptance behavior:

- The request increments `coupon:issue:count:{couponId}`.
- If the returned counter value is within the configured stock, the request proceeds to PostgreSQL.
- PostgreSQL then performs duplicate pre-check, conditional stock update, and `IssuedCoupon` persistence.

Rejection behavior:

- If the returned counter value is greater than stock, the counter is decremented and the request is rejected before database persistence.

Compensation trigger:

- If Redis accepts the request but a `RuntimeException` occurs inside the tested database persistence block, the Redis counter is decremented.

Scope limitation:

- Redis Counter does not prevent duplicate issuance by itself.
- The database unique constraint remains responsible for final duplicate prevention.
- In the current Redis Counter test, requests use distinct users, so duplicate contention is not the behavior under test.

## 7. Redis Lua Flow

Redis Lua uses one Redis-side script to check stock and duplicate state before database persistence.

Redis-side behavior:

- The script checks whether the user id already exists in `coupon:issue:users:{couponId}`.
- The script checks whether `coupon:issue:count:{couponId}` has reached stock.
- If both checks pass, the script increments the count key and adds the user id to the user set.

Return behavior:

| Return value | Meaning | Application behavior |
| ---: | --- | --- |
| `1` | Accepted by Redis | Continue to PostgreSQL persistence |
| `-1` | Sold out in Redis | Reject without DB persistence |
| `-2` | Duplicate user in Redis | Reject without DB persistence |

Compensation trigger:

- If Redis accepts the request but a `RuntimeException` occurs inside the tested database persistence block, the implementation decrements the count key and removes the user id from the Redis user set.

Scope limitation:

- Redis Lua prevents duplicate acceptance inside Redis, but it does not replace the database unique constraint.
- PostgreSQL is still required to persist the durable issuance record.

## 8. Guarantees Provided

Redis Counter provides:

- Atomic Redis-side stock slot numbering through the Redis counter operation.
- Early rejection for requests whose counter value exceeds stock.
- Reduced database write-path entry for sold-out requests in the tested distinct-user scenario.

Redis Lua provides:

- Atomic Redis-side stock acceptance and duplicate acceptance for the Redis keys used by the script.
- Early rejection for sold-out requests.
- Early rejection for repeated user-coupon requests already present in the Redis user set.

PostgreSQL provides:

- Durable coupon inventory state.
- Durable issued coupon records.
- Final duplicate protection through `uk_issued_coupon_user_coupon`.
- Final stock protection through the conditional stock update used after Redis acceptance.

Current tests verify:

- Redis Counter stock-control state: successful database issuances match coupon stock, and Redis count matches accepted stock.
- Redis Lua stock-control state: successful database issuances match coupon stock, Redis count matches accepted stock, and Redis issued-user set size matches accepted users.
- Redis Lua duplicate-control state: only one issued coupon is persisted for the repeated user, Redis count is `1`, and Redis issued-user set size is `1`.

## 9. Guarantees Not Provided

The current implementation does not provide:

- An atomic transaction across Redis and PostgreSQL.
- Redis as durable coupon issuance truth.
- Compensation as a distributed commit protocol.
- Redis Counter duplicate prevention.
- Redis Lua as a replacement for the database unique constraint.
- A reconciliation job between Redis and PostgreSQL.
- A Redis rebuild process from PostgreSQL.
- A Redis key TTL strategy.
- An idempotency token model for client retries.
- Recovery handling for compensation failure.
- Monitoring or alerting for Redis/PostgreSQL divergence.

Redis acceptance means only that a request passed the front-line gate. It does not mean the issuance is durable.

## 10. Failure Windows

| Window | Example | Current behavior | Remaining risk |
| --- | --- | --- | --- |
| Redis rejects before DB | Counter exceeds stock, Lua returns sold out, or Lua returns duplicate | Request is rejected before PostgreSQL persistence | None for database state because no DB write is attempted |
| Redis accepts but DB stock update fails | Conditional update affects 0 rows after Redis acceptance | Runtime failure is raised and Redis compensation is attempted | Compensation may fail; Redis and DB can diverge |
| Redis accepts but insert fails | `IssuedCoupon` persistence fails after Redis acceptance | Runtime failure is caught inside the tested persistence block and Redis compensation is attempted | Compensation may not occur for failures after the local persistence block completes; compensation itself may also fail. |
| DB commit succeeds but response fails | PostgreSQL commit succeeds, but the client does not receive the response | PostgreSQL remains the durable source of truth | Client retries may result in duplicate requests because no idempotency mechanism is currently implemented. |
| Redis data loss | Redis keys are deleted or Redis loses in-memory state | No rebuild process is implemented | Redis state may diverge from PostgreSQL. No rebuild mechanism is currently implemented.|
| Compensation failure | Redis decrement or set removal fails during compensation | No retry, queue, or reconciliation process is implemented | Redis can remain ahead of PostgreSQL |

The tested compensation path covers the main local failure shape: Redis accepts the request, then database persistence fails before the method completes normally. It does not cover every process crash, network failure, transaction commit failure, or Redis outage scenario.

## 11. Compensation Behavior

Redis Counter compensation:

- Trigger: runtime failure after Redis counter acceptance inside the tested database persistence block.
- Action: decrement `coupon:issue:count:{couponId}`.
- Purpose: return the Redis accepted count to match the failed database persistence.

Redis Lua compensation:

- Trigger: runtime failure after Redis Lua acceptance inside the tested database persistence block.
- Action: decrement `coupon:issue:count:{couponId}` and remove the user id from `coupon:issue:users:{couponId}`.
- Purpose: remove both Redis-side stock reservation and Redis-side duplicate reservation for the failed persistence.

What compensation covers:

- Synchronous runtime failures inside the implemented persistence block.
- Stock update failure represented by conditional update count `0`.
- Insert failures that are raised before the method exits the compensation block.

What compensation does not cover:

- Failure of the compensation command itself.
- Process crash before compensation runs.
- Redis outage during compensation.
- Transaction commit failure that occurs after the local try/catch block has completed.
- Reconciliation after Redis state has already diverged from PostgreSQL.

## 12. Recovery And Reconciliation Gaps

The following areas are future considerations and are not implemented behavior:

- Reconciliation job that compares `coupons` and `issued_coupons` against Redis keys.
- Redis rebuild process from PostgreSQL source-of-truth records.
- TTL strategy for `coupon:issue:count:{couponId}` and `coupon:issue:users:{couponId}`.
- Compensation retry mechanism.
- Dead-letter or audit record for failed compensation.
- Metrics for compensation attempts, compensation failures, Redis accepted count, and DB persisted count.
- Alerts for Redis/PostgreSQL divergence.
- Idempotency token model for client retries after ambiguous responses.
- Defined behavior for Redis restart or data loss during an active issuance event.

These gaps do not invalidate the current experiments. They define the boundary between the implemented local consistency experiment and a production recovery design.

## 13. Verification In Current Tests

The Redis/PostgreSQL boundary is verified by the coupon concurrency tests.

Relevant tests:

| Test method | Boundary behavior verified |
| --- | --- |
| `concurrentIssue_redisCounter_preventsCouponStockOverselling` | Redis Counter accepts only stock-sized request slots, PostgreSQL persists only stock-sized issued records, Redis count matches stock |
| `concurrentIssue_redisLua_preventsCouponStockOverselling` | Redis Lua accepts only stock-sized distinct users, PostgreSQL persists only stock-sized issued records, Redis count and user set size match stock |
| `concurrentIssue_redisLua_preventsDuplicateIssue` | Redis Lua rejects duplicate user requests before DB persistence, PostgreSQL persists one issued coupon, Redis count and user set size are `1` |

The tests validate final database state, not only request counters:

- `issuedCouponRepository.countByCouponId(...)`
- `issuedCouponRepository.countByUserIdAndCouponId(...)`
- `Coupon.issuedQuantity`
- Redis count key value
- Redis user set size

Use `docs/runbook.md` for local execution commands and optional PostgreSQL/Redis inspection commands.

## 14. Related Documents

- [README](../README.md)
- [Concurrency Experiment Runbook](runbook.md)
- [Coupon Concurrency Strategy Comparison](coupon-concurrency-strategy-comparison.md)
- [Coupon Domain Design](coupon-domain-design.md)
