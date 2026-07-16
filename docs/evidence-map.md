# Portfolio Evidence Map

## Purpose

This document maps portfolio-facing technical claims to repository evidence. It is not a project explanation. Its job is to show which claims are supported by implementation, tests, SQL or schema evidence, configuration, and existing documents, and which claims must be narrowed or avoided.

Review date: 2026-06-26.

## Evidence Status Definitions

| Status | Meaning |
| --- | --- |
| Verified | The claim is directly supported by current source code, an active executable test, an entity mapping, a repository query, or checked-in configuration. |
| Partially Verified | The repository supports part of the claim, but the claim is broader than the implementation, test scope, schema evidence, or runtime path. |
| Historical Result | The claim is an observed result preserved in disabled tests or historical docs. It should not be described as current executable behavior. |
| General Technical Reasoning | The claim depends on generally accepted platform behavior, such as Redis command or Lua script atomicity, rather than a repository-specific proof. |
| Unsupported | The repository does not contain implementation, tests, measurements, SQL, fixtures, or captured output that support the claim. |
| Do Not Use | The claim contradicts the repository boundary or would mislead reviewers about the implementation guarantee. |

## Evidence Source Priority

1. Current source code and active tests under `src/main` and `src/test`.
2. Entity mappings, repository annotations, and repository queries.
3. Checked-in configuration such as `application.yml`, `build.gradle`, and `docker-compose.yml`.
4. Current docs under `README.md` and `docs/`.
5. Historical docs under `docs/history/`.
6. Generated build output is not used as portfolio evidence. The workspace contains generated test output from a previous failed local run caused by an unavailable PostgreSQL connection, but generated artifacts are not authoritative for portfolio claims.

There are no checked-in `.sql` migration files, no Flyway/Liquibase migrations, no standalone Redis `.lua` scripts, and no fixture directory in the tracked repository. SQL-like evidence is JPQL in repositories, documentation snippets, or manual verification instructions in the runbook.

## Repository Evidence Inventory

Runtime source:

- Point runtime strategies exist in `PointService`: transaction-only `deduct`, pessimistic lock, optimistic lock, and atomic update.
- Coupon runtime service currently contains only `CouponIssueService.issueTransactionOnly(...)`.
- Coupon pessimistic lock, optimistic lock, atomic update, Redis Counter, and Redis Lua strategy methods are implemented in `CouponIssueConcurrencyTest.TestCouponIssueService`, which is test fixture code.

Configuration:

- PostgreSQL is configured in `src/main/resources/application.yml` lines 1-6.
- Redis is configured in `src/main/resources/application.yml` lines 8-11.
- Hibernate `ddl-auto: update` and SQL init `mode: never` are configured in `src/main/resources/application.yml` lines 13-24.
- Docker Compose defines PostgreSQL 16 and Redis 7.4 services in `docker-compose.yml` lines 1-21.
- Gradle includes Spring Data JPA, Redis, Security, Web, PostgreSQL runtime, and JUnit test dependencies in `build.gradle` lines 21-37.

Schema and constraints:

- `IssuedCoupon` maps `uk_issued_coupon_user_coupon` on `(user_id, coupon_id)` in `IssuedCoupon.java` lines 10-19.
- `Point` maps `uk_points_user_id` on `user_id` in `Point.java` lines 10-16.
- `Coupon` has `@Version` in `Coupon.java` lines 32-33.
- `Point` has `@Version` in `Point.java` lines 37-38.

## Page 2 Evidence

### Point Lost Update Baseline

Claim: The project reproduced a point lost update baseline where concurrent point deductions produced an inconsistent final persisted balance.

Status: Historical Result.

Evidence:

- Runtime transaction-only point implementation: `PointService.deduct(...)` loads a `Point`, calls `point.deduct(amount)`, and returns the balance in `src/main/java/com/roar/coupon/domain/point/service/PointService.java` lines 29-36.
- Test fixture baseline path: `PointServiceConcurrencyTest.TestPointService.deductWithDelay(...)` reads the point, sleeps, and deducts in `src/test/java/com/roar/coupon/domain/point/service/PointServiceConcurrencyTest.java` lines 363-371.
- Repository query: `PointRepository.findByUserId(...)` in `src/main/java/com/roar/coupon/domain/point/repository/PointRepository.java` line 17.
- Active test method: `concurrentDeduct_transactionOnly_inconsistentBalance` in `PointServiceConcurrencyTest.java` line 47.
- Synchronization: that test uses `readyLatch`, `startLatch`, and `doneLatch` in `PointServiceConcurrencyTest.java` lines 68-70.
- Current assertions: the active test only asserts total request accounting and non-negative balance in `PointServiceConcurrencyTest.java` lines 110-111. It does not assert a specific lost-update value.
- Historical result documentation: `successCount = 15`, `failCount = 0`, `finalBalance = 8000`, and `expectedBalanceBySuccessCount = -5000` are documented in `docs/point-concurrency-strategy-comparison.md` lines 65-78 and `docs/history/implementation-roadmap.md` lines 86-101.

Evidence Location:

- Class: `PointService`.
- Method: `deduct(Long userId, long amount)`.
- Test class: `PointServiceConcurrencyTest`.
- Test method: `concurrentDeduct_transactionOnly_inconsistentBalance`.
- Repository query: `PointRepository.findByUserId`.
- Assertion: current active assertions are weak; historical result values are documented, not actively asserted.
- Executable or historical: historical for the exact lost-update result; executable only for the current baseline path.

Safe Portfolio Wording:

> I preserved the transaction-only point deduction path and historical lost-update result. The exact `successCount = 15` and `finalBalance = 8000` baseline is treated as pre-`@Version` evidence, while the current active test keeps the scenario shape.

Avoid Wording:

> The current point test always reproduces `successCount = 15` and `finalBalance = 8000`.

Notes:

- `Point.@Version` now exists, so the original no-version lost update is not the same as current entity behavior.
- The current active baseline test should be strengthened if the portfolio needs an executable current lost-update assertion.

### Coupon Overselling Baseline

Claim: The project reproduced coupon overselling with stock 100 and 1,000 concurrent distinct-user requests.

Status: Historical Result.

Evidence:

- Runtime transaction-only coupon implementation: `CouponIssueService.issueTransactionOnly(...)` reads a `Coupon`, checks duplicate issuance, calls `coupon.issue()`, and saves `IssuedCoupon` in `src/main/java/com/roar/coupon/domain/coupon/service/CouponIssueService.java` lines 19-31.
- Entity stock check and increment: `Coupon.issue()` in `src/main/java/com/roar/coupon/domain/coupon/entity/Coupon.java` lines 47-53.
- Repository queries: `IssuedCouponRepository.countByCouponId(...)` in `IssuedCouponRepository.java` line 10.
- Disabled historical test: `concurrentIssue_transactionOnly_canOversellCouponStock` is annotated with `@Disabled` in `src/test/java/com/roar/coupon/domain/coupon/service/CouponIssueConcurrencyTest.java` lines 87-90.
- Test assertions: total count, issued coupon count greater than stock, and final issued quantity less than or equal to initial stock are asserted in `CouponIssueConcurrencyTest.java` lines 123-125.
- Historical result documentation: `successCount = 1000`, `failCount = 0`, `issuedCouponCountByCoupon = 1000`, and `finalIssuedQuantity = 100` are documented in `docs/coupon-concurrency-strategy-comparison.md` lines 108-129 and `docs/history/coupon-concurrency-experiment-plan.md` lines 118-171.

Evidence Location:

- Class: `CouponIssueService`.
- Method: `issueTransactionOnly(Long userId, Long couponId)`.
- Test fixture method: `TestCouponIssueService.issueTransactionOnlyWithDelay(...)` in `CouponIssueConcurrencyTest.java` lines 707-725.
- Test method: `concurrentIssue_transactionOnly_canOversellCouponStock`.
- Repository query: `IssuedCouponRepository.countByCouponId`.
- Assertion: `issuedCouponCountByCoupon > OVERSELLING_INITIAL_STOCK`.
- Executable or historical: historical because the test is disabled and `Coupon.@Version` now changes the transaction-only stock path.

Safe Portfolio Wording:

> Before adding `Coupon.@Version`, the transaction-only coupon baseline was observed to create 1,000 issued records for stock 100. The disabled test and docs preserve this as historical overselling evidence.

Avoid Wording:

> The current active test suite reproduces coupon overselling on every run.

Notes:

- The current `Coupon` entity has `@Version` in `Coupon.java` lines 32-33.
- Do not classify this as current behavior.

### Coupon Duplicate Issuance Baseline

Claim: The project reproduced duplicate coupon issuance for the same user and coupon.

Status: Historical Result.

Evidence:

- Application-level duplicate pre-check: `issuedCouponRepository.existsByUserIdAndCouponId(...)` is called in `CouponIssueService.java` lines 25-27 and test fixture code in `CouponIssueConcurrencyTest.java` lines 716-718.
- Repository query: `IssuedCouponRepository.existsByUserIdAndCouponId(...)` line 8 and `countByUserIdAndCouponId(...)` line 12.
- Disabled historical test: `concurrentIssue_transactionOnly_allowsIssueDuplicateCouponToSameUser` is annotated with `@Disabled` in `CouponIssueConcurrencyTest.java` lines 128-131.
- Test assertions: total count equals 100 and duplicate count is greater than 1 in `CouponIssueConcurrencyTest.java` lines 170-171.
- Historical result documentation: `successCount = 10`, `failCount = 90`, and `issuedCouponCountByUserAndCoupon = 10` are documented in `docs/coupon-concurrency-strategy-comparison.md` lines 120-129 and `docs/history/coupon-concurrency-experiment-plan.md` lines 141-171.

Evidence Location:

- Class: `CouponIssueService`.
- Method: `issueTransactionOnly`.
- Test fixture method: `TestCouponIssueService.issueTransactionOnlyWithDelay`.
- Test method: `concurrentIssue_transactionOnly_allowsIssueDuplicateCouponToSameUser`.
- Repository query: `IssuedCouponRepository.countByUserIdAndCouponId`.
- Assertion: duplicate count greater than 1.
- Executable or historical: historical because the test is disabled and the current `IssuedCoupon` mapping contains a DB unique constraint.

Safe Portfolio Wording:

> Before adding the user-coupon unique constraint, the transaction-only duplicate baseline was observed to persist 10 rows for one user and one coupon under 100 concurrent requests.

Avoid Wording:

> The current schema allows duplicate issued coupons.

Notes:

- The current entity mapping prevents duplicates through `uk_issued_coupon_user_coupon` in `IssuedCoupon.java` lines 10-19.

### CountDownLatch Synchronization

Claim: The concurrency tests coordinate worker start with `CountDownLatch`.

Status: Verified.

Evidence:

- Coupon tests use a shared helper with `readyLatch`, `startLatch`, and `doneLatch` in `CouponIssueConcurrencyTest.java` lines 557-609.
- Coupon helper asserts that workers are ready and completed within timeout in `CouponIssueConcurrencyTest.java` lines 597 and 601.
- Point tests use `readyLatch`, `startLatch`, and `doneLatch` in each strategy test: transaction-only lines 68-70, pessimistic lines 137-139, optimistic lines 204-206, atomic update lines 283-285.

Evidence Location:

- Class: `CouponIssueConcurrencyTest`.
- Method: `runConcurrentIssueRequests`.
- Class: `PointServiceConcurrencyTest`.
- Test methods: all four point concurrency methods.
- Assertion: coupon helper asserts latch readiness and completion with timeout; point tests call `await()` without explicit timeout.
- Executable or historical: executable for active tests; disabled coupon baselines still contain the same helper path but are not active.

Safe Portfolio Wording:

> The concurrency tests use `CountDownLatch` to align worker readiness, release simultaneous starts, and wait for completion. The coupon helper also asserts latch timeouts.

Avoid Wording:

> All tests prove perfect simultaneous execution.

Notes:

- Latches improve contention reproducibility but do not prove identical scheduling.

### Final Persisted State Verification

Claim: Tests verify final persisted state, not only request counters.

Status: Partially Verified.

Evidence:

- Point pessimistic lock asserts `successCount = 10`, `failCount = 5`, and final balance `0` in `PointServiceConcurrencyTest.java` lines 176-178.
- Point optimistic lock asserts optimistic failure count and final balance equals `expectedBalanceBySuccessCount` in `PointServiceConcurrencyTest.java` lines 252-257.
- Point atomic update asserts `successCount = 10`, insufficient-balance failures `5`, final balance `0`, and balance equals expected by success count in `PointServiceConcurrencyTest.java` lines 331-335.
- Coupon pessimistic lock asserts final coupon quantity, issued-record count, equality between them, and stock ceiling in `CouponIssueConcurrencyTest.java` lines 252-258.
- Coupon optimistic lock asserts issued record count and final issued quantity equal success count and do not exceed stock in `CouponIssueConcurrencyTest.java` lines 301-308.
- Coupon atomic update asserts success/fail counts, issued record count, final issued quantity, equality between them, and stock ceiling in `CouponIssueConcurrencyTest.java` lines 352-359.
- Redis Counter and Redis Lua tests assert both PostgreSQL state and Redis state in `CouponIssueConcurrencyTest.java` lines 408-415, 472-482, and 545-554.
- Repository state queries: `IssuedCouponRepository.countByCouponId` and `countByUserIdAndCouponId` in `IssuedCouponRepository.java` lines 10-12.

Evidence Location:

- Classes: `PointServiceConcurrencyTest`, `CouponIssueConcurrencyTest`.
- Repository queries: `PointRepository.findByUserId`, `CouponRepository.findById`, `IssuedCouponRepository.countByCouponId`, `IssuedCouponRepository.countByUserIdAndCouponId`.
- Executable or historical: executable for active tests; historical for disabled baselines.

Safe Portfolio Wording:

> The active strategy tests assert final PostgreSQL state and, for Redis strategies, final Redis key state in addition to success/failure counters.

Avoid Wording:

> Every baseline failure is currently asserted by an active test.

Notes:

- The active point transaction-only baseline test does not assert the historical inconsistency value.

### Same-Condition Comparison

Claim: Strategies are compared under the same business conditions.

Status: Partially Verified.

Evidence:

- Point tests all use initial balance 10,000, 15 threads, and deduction amount 1,000 in `PointServiceConcurrencyTest.java` lines 61-65, 130-133, 197-200, and 276-279.
- Coupon stock-control constants use stock 100 and request count 1,000 for overselling, pessimistic, optimistic, atomic update, Redis Counter, and Redis Lua in `CouponIssueConcurrencyTest.java` lines 41-60.
- Coupon duplicate-control constants use stock 1,000 and request count 100 in `CouponIssueConcurrencyTest.java` lines 44-45 and 62-63.
- Coupon tests use different delays by strategy: baseline delay 100 ms, lock/atomic/optimistic delay 5 ms, Redis paths often 0 ms. These are visible in constants and test calls in `CouponIssueConcurrencyTest.java` lines 35-39, 108-109, 237-238, 286-287, 336-337, 392, 454-455, and 526-527.

Evidence Location:

- Classes: `PointServiceConcurrencyTest`, `CouponIssueConcurrencyTest`.
- Executable or historical: executable for active strategy tests; historical for disabled coupon baselines.

Safe Portfolio Wording:

> The tests reuse the same core business scenarios by domain, such as 15 point deductions against a 10,000 balance and 1,000 coupon requests against stock 100. Some timing delays differ by strategy to make contention observable.

Avoid Wording:

> Every strategy is benchmarked under identical execution conditions.

Notes:

- The repository contains comparison tests, not a controlled benchmark harness.

## Page 3 Evidence

### Transaction Only

Claim: `@Transactional` read-modify-write is used as the baseline and is insufficient under contention.

Status: Partially Verified.

Implementation Location:

- Point runtime: `PointService.deduct(...)` in `PointService.java` lines 29-36.
- Coupon runtime: `CouponIssueService.issueTransactionOnly(...)` in `CouponIssueService.java` lines 19-31.
- Coupon test fixture: `TestCouponIssueService.issueTransactionOnlyWithDelay(...)` in `CouponIssueConcurrencyTest.java` lines 707-725.

Repository Query:

- Point: `PointRepository.findByUserId(...)` line 17.
- Coupon: inherited `findById(...)` plus `IssuedCouponRepository.existsByUserIdAndCouponId(...)` line 8.

SQL:

- No raw SQL or migration file. The path relies on JPA entity loading and dirty checking.

Entity:

- `Point.deduct(...)` validates and subtracts balance in `Point.java` lines 50-58.
- `Coupon.issue()` validates and increments issued quantity in `Coupon.java` lines 47-53.
- Current entities include `@Version`, so current transaction-only behavior is not the original no-version baseline.

Tests:

- Point active baseline method: `concurrentDeduct_transactionOnly_inconsistentBalance`.
- Coupon overselling baseline: disabled historical test `concurrentIssue_transactionOnly_canOversellCouponStock`.
- Coupon duplicate baseline: disabled historical test `concurrentIssue_transactionOnly_allowsIssueDuplicateCouponToSameUser`.

Guarantee Scope:

- A single request's operations are committed or rolled back within a Spring transaction.

Limitation:

- Does not serialize concurrent transactions that read and write the same row.
- Current code contains `@Version`, so the original no-version failure shape is historical.

Unsupported Portfolio Claims:

- Current active tests prove the original exact point lost-update values.
- Current active tests reproduce coupon overselling and duplicate issuance.

Safe Portfolio Wording:

> The transaction-only path is preserved as a baseline. The original point lost-update and coupon overselling/duplicate results are historical evidence from before later versioning or constraint changes.

Avoid Wording:

> `@Transactional` currently reproduces all failures in active tests.

Notes:

- Use this claim to explain why transaction atomicity and concurrency control are different concerns.

### Pessimistic Lock

Claim: Pessimistic locking serializes same-row updates under contention.

Status: Verified.

Implementation Location:

- Point runtime: `PointService.deductWithPessimisticLock(...)` in `PointService.java` lines 48-56.
- Coupon test fixture: `TestCouponIssueService.issueWithPessimisticLock(...)` in `CouponIssueConcurrencyTest.java` lines 750-767.

Repository Query:

- Point: `PointRepository.findByUserIdForUpdate(...)` uses `@Lock(LockModeType.PESSIMISTIC_WRITE)` and JPQL select in `PointRepository.java` lines 19-21.
- Coupon: `CouponRepository.findByIdWithPessimisticLock(...)` uses `@Lock(LockModeType.PESSIMISTIC_WRITE)` and JPQL select in `CouponRepository.java` lines 15-17.

SQL:

- No raw SQL file. The source evidence is JPA `PESSIMISTIC_WRITE`; SQL such as `FOR UPDATE` is conceptual unless captured from Hibernate logs.

Entity:

- `Point` balance field and `deduct(...)` in `Point.java` lines 28-58.
- `Coupon` issued quantity and `issue()` in `Coupon.java` lines 26-53.

Constraint:

- Duplicate coupon issuance still depends on `IssuedCoupon` unique constraint in `IssuedCoupon.java` lines 10-19.

Tests:

- Point test: `concurrentDeduct_pessimisticLock_consistentBalance`, assertions in `PointServiceConcurrencyTest.java` lines 176-178.
- Coupon test: `concurrentIssue_pessimisticLock_preventsCouponStockOverselling`, assertions in `CouponIssueConcurrencyTest.java` lines 252-258.

Guarantee Scope:

- Serializes updates to the same locked row in the tested transaction scope.
- Prevents lost update for the locked Point row and coupon stock overselling for one Coupon row in the tested scenario.

Limitation:

- Does not itself enforce user-coupon uniqueness.
- Lock wait, latency, throughput, timeout, and deadlock behavior are discussed but not measured.
- Coupon strategy is test fixture code, not a production-facing coupon service method.

Unsupported Portfolio Claims:

- Pessimistic lock has measured throughput or measured lock wait in this repository.
- Coupon pessimistic lock is exposed as a runtime API.

Safe Portfolio Wording:

> Pessimistic locking is implemented with JPA `PESSIMISTIC_WRITE`; active tests verify consistent final state for point deduction and coupon stock-control scenarios.

Avoid Wording:

> Pessimistic locking was benchmarked for throughput and lock wait time.

Notes:

- The docs mention lock-wait trade-offs; those are design reasoning, not benchmark results.

### Optimistic Lock

Claim: Optimistic locking detects stale concurrent updates with `@Version`.

Status: Verified.

Implementation Location:

- Point runtime: `PointService.deductWithOptimisticLock(...)` in `PointService.java` lines 59-67.
- Coupon test fixture: `TestCouponIssueService.issueWithOptimisticLock(...)` in `CouponIssueConcurrencyTest.java` lines 769-786.

Repository Query:

- Point uses `PointRepository.findByUserId(...)` line 17.
- Coupon uses inherited `CouponRepository.findById(...)`.

SQL:

- No explicit SQL. JPA/Hibernate generates version-checked updates because `@Version` exists.

Entity:

- `Point.@Version` in `Point.java` lines 37-38.
- `Coupon.@Version` in `Coupon.java` lines 32-33.

Constraint:

- Duplicate coupon issuance remains guarded by `IssuedCoupon` unique constraint, not by optimistic locking.

Tests:

- Point test: `concurrentDeduct_optimisticLock_detectsConflict`, assertions in `PointServiceConcurrencyTest.java` lines 252-257.
- Coupon test: `concurrentIssue_optimisticLock_preventsCouponStockOverselling`, assertions in `CouponIssueConcurrencyTest.java` lines 301-308.

Guarantee Scope:

- Detects conflicting stale updates rather than silently overwriting them.
- Verifies final persisted state consistency in active tests.

Limitation:

- No retry policy is implemented.
- Docs explicitly state exact coupon stock exhaustion is an observed result, not a no-retry guarantee, in `docs/coupon-concurrency-strategy-comparison.md` lines 218-238 and 452-453.

Unsupported Portfolio Claims:

- Optimistic locking guarantees all stock will always be exhausted under high contention without retry.
- Optimistic locking improves user-facing success rate under high contention.

Safe Portfolio Wording:

> Optimistic locking uses `@Version` to reject stale updates. The active tests verify conflict detection and final database consistency, while retry behavior is intentionally out of scope.

Avoid Wording:

> Optimistic locking guarantees exactly 100 successes for every high-contention coupon schedule without retry.

Notes:

- The coupon test currently asserts `failCount = requestCount - stock` and persisted state equals success count, but docs correctly narrow the general guarantee.

### Atomic Update

Claim: Conditional database updates enforce simple balance or stock rules atomically.

Status: Verified.

Implementation Location:

- Point runtime: `PointService.deductWithAtomicUpdate(...)` in `PointService.java` lines 70-86.
- Coupon test fixture: `TestCouponIssueService.issueWithAtomicUpdate(...)` in `CouponIssueConcurrencyTest.java` lines 788-807.

Repository Query:

- Point: `PointRepository.deductIfEnoughBalance(...)` in `PointRepository.java` lines 23-34.
- Coupon: `CouponRepository.increaseIssuedQuantityIfStockAvailable(...)` in `CouponRepository.java` lines 19-28.

SQL:

- JPQL point update: decrement balance and increment version where `balance >= :amount`.
- JPQL coupon update: increment `issuedQuantity`, increment version, update timestamp where `issuedQuantity < totalQuantity`.
- No checked-in SQL migration or native SQL file.

Entity:

- `Point` has `balance` and `version`.
- `Coupon` has `issuedQuantity`, `totalQuantity`, and `version`.

Constraint:

- Coupon duplicate issuance still depends on `IssuedCoupon` unique constraint.

Tests:

- Point test: `concurrentDeduct_atomicUpdate_consistentBalance`, assertions in `PointServiceConcurrencyTest.java` lines 331-335.
- Coupon test: `concurrentIssue_atomicUpdate_preventsCouponStockOverselling`, assertions in `CouponIssueConcurrencyTest.java` lines 352-359.

Guarantee Scope:

- Strong for the specific single-row conditional update represented in the repository query.
- Prevents creating coupon records after stock exhaustion in the tested fixture because the `IssuedCoupon` save occurs only after update count is 1.

Limitation:

- Business logic is query-centered.
- Does not solve duplicate issuance without the unique constraint.
- Coupon atomic update is test fixture code, not a production-facing coupon service method.

Unsupported Portfolio Claims:

- Atomic update covers complex workflows beyond the one-row condition.
- Atomic update alone solves duplicate issuance.

Safe Portfolio Wording:

> Atomic update is implemented as JPQL conditional updates for point balance and coupon stock; active tests verify final state consistency for the simple rules under test.

Avoid Wording:

> Atomic update is a complete consistency solution for all coupon issuance invariants by itself.

Notes:

- For coupon, the stock update and issued-coupon insert run in the same transaction in the test fixture.

### DB UNIQUE Constraint

Claim: A database uniqueness constraint prevents duplicate issued coupons for the same user and coupon.

Status: Partially Verified.

Implementation Location:

- `IssuedCoupon` entity maps `@UniqueConstraint(name = "uk_issued_coupon_user_coupon", columnNames = {"user_id", "coupon_id"})` in `IssuedCoupon.java` lines 10-19.
- Duplicate handling fixture uses `saveAndFlush` and catches `DataIntegrityViolationException` in `CouponIssueConcurrencyTest.java` lines 728-748.

Repository Query:

- `IssuedCouponRepository.existsByUserIdAndCouponId(...)` line 8.
- `IssuedCouponRepository.countByUserIdAndCouponId(...)` line 12.

SQL:

- No migration file.
- `application.yml` sets `ddl-auto: update` and SQL init disabled in lines 13-24.
- Runbook instructs manual schema inspection with `\d issued_coupons` in `docs/runbook.md` lines 204-210.

Entity:

- `IssuedCoupon.userId` maps to `user_id` and `couponId` maps to `coupon_id` in `IssuedCoupon.java` lines 28-32.

Constraint:

- `uk_issued_coupon_user_coupon`.

Tests:

- Active test: `concurrentIssue_dbUniqueConstraint_preventsDuplicateCouponIssueToSameUser`.
- Assertions: total count 100 and persisted duplicate count 1 in `CouponIssueConcurrencyTest.java` lines 210-211.

Guarantee Scope:

- Final persistence-level uniqueness for `(user_id, coupon_id)` when the schema contains the mapped constraint.

Limitation:

- Does not prevent stock overselling.
- `ddl-auto=update` may not apply a new unique constraint to an existing table; docs call this out in `docs/coupon-concurrency-strategy-comparison.md` lines 170-172 and `docs/runbook.md` lines 95-96.

Unsupported Portfolio Claims:

- The repository has an explicit SQL migration for the unique constraint.
- The checked-in repo contains captured database DDL output proving the live schema.

Safe Portfolio Wording:

> `IssuedCoupon` maps a unique constraint for `(user_id, coupon_id)`, and an active concurrency test verifies only one row is persisted for 100 concurrent same-user requests when the schema contains that constraint.

Avoid Wording:

> A Flyway or Liquibase migration guarantees the unique constraint in every environment.

Notes:

- For portfolio verification, add a captured schema artifact or migration if the claim needs SQL-level evidence.

### Redis Counter

Claim: Redis Counter gates coupon stock before PostgreSQL persistence.

Status: Partially Verified.

Implementation Location:

- Test fixture method: `TestCouponIssueService.issueWithRedisCounterGate(...)` in `CouponIssueConcurrencyTest.java` lines 809-846.
- Redis key helper: `CouponRedisKeys.issueCounter(...)` in `CouponIssueConcurrencyTest.java` lines 911-914.

Repository Query:

- Redis-accepted requests use `CouponRepository.increaseIssuedQuantityIfStockAvailable(...)` in `CouponRepository.java` lines 19-28.
- Duplicate pre-check uses `IssuedCouponRepository.existsByUserIdAndCouponId(...)` line 8.

SQL:

- PostgreSQL stock persistence uses the JPQL conditional update in `CouponRepository.java` lines 19-28.
- No Redis script file. Redis Counter uses `StringRedisTemplate.opsForValue().increment(...)`.

Entity:

- `Coupon` for durable stock.
- `IssuedCoupon` for durable issued record.

Constraint:

- DB unique constraint remains the duplicate guard.

Tests:

- Active test: `concurrentIssue_redisCounter_preventsCouponStockOverselling`.
- Assertions: total requests, success/fail counts, issued record count, final issued quantity, Redis counter value, and stock ceiling in `CouponIssueConcurrencyTest.java` lines 408-415.

Guarantee Scope:

- Limits Redis-accepted stock slots in the tested distinct-user scenario.
- PostgreSQL conditional update still performs final durable stock persistence.
- Redis state is preliminary gate state.

Limitation:

- Does not prevent duplicate issuance by itself.
- Does not provide Redis/PostgreSQL atomic commit.
- Compensation exists in code at `CouponIssueConcurrencyTest.java` lines 842-844 and 901-903, but no test intentionally forces DB failure after Redis acceptance.
- Implemented only in the test fixture, not `CouponIssueService`.

Unsupported Portfolio Claims:

- Redis Counter is a production runtime service path.
- Redis Counter solves duplicate issuance.
- Redis Counter reduces measured DB load in this repository.
- Redis Counter and PostgreSQL commit atomically together.

Safe Portfolio Wording:

> The Redis Counter experiment uses Redis as a front-line stock gate in a test fixture, then persists accepted requests through PostgreSQL conditional update and verifies final DB and Redis state.

Avoid Wording:

> Redis Counter is the durable source of truth or participates in a distributed transaction with PostgreSQL.

Notes:

- Redis `INCR` atomicity itself is general Redis behavior; the repository verifies the resulting state in tests but does not formally prove Redis internals.

### Redis Lua Script

Claim: Redis Lua gates both stock and duplicate acceptance before PostgreSQL persistence.

Status: Partially Verified.

Implementation Location:

- Embedded Lua script: `REDIS_LUA_ISSUE_SCRIPT` in `CouponIssueConcurrencyTest.java` lines 674-693.
- `RedisScript.of(...)` wrapper in `CouponIssueConcurrencyTest.java` lines 694-695.
- Execution path: `TestCouponIssueService.issueWithRedisLua(...)` in `CouponIssueConcurrencyTest.java` lines 848-890.
- Redis key helper: `CouponRedisKeys.issueCounter(...)` and `issueUsers(...)` in `CouponIssueConcurrencyTest.java` lines 911-917.

Repository Query:

- PostgreSQL persistence uses `CouponRepository.increaseIssuedQuantityIfStockAvailable(...)`.
- Issued coupon persistence uses `issuedCouponRepository.saveAndFlush(...)` in `CouponIssueConcurrencyTest.java` line 885.

SQL:

- No standalone `.lua` file and no SQL migration.
- PostgreSQL stock persistence uses JPQL conditional update.

Entity:

- `Coupon` for durable stock.
- `IssuedCoupon` for durable ownership and duplicate constraint.

Constraint:

- DB unique constraint remains the final duplicate guard.

Tests:

- Stock test: `concurrentIssue_redisLua_preventsCouponStockOverselling`, assertions in `CouponIssueConcurrencyTest.java` lines 472-482.
- Duplicate test: `concurrentIssue_redisLua_preventsDuplicateIssue`, assertions in `CouponIssueConcurrencyTest.java` lines 545-554.

Guarantee Scope:

- Redis-side gate checks duplicate membership and stock count within one Redis script for the keys used by the fixture.
- PostgreSQL remains the durable source of truth.

Limitation:

- No distributed transaction with PostgreSQL.
- No production script deployment/versioning.
- No TTL, rebuild, idempotency token, reconciliation, monitoring, or compensation retry.
- Compensation code exists in `CouponIssueConcurrencyTest.java` lines 886-888 and 905-908, but compensation failure scenarios are not tested.
- Implemented only in the test fixture, not `CouponIssueService`.

Unsupported Portfolio Claims:

- Redis Lua replaces the database unique constraint.
- Redis Lua provides durable issuance state by itself.
- Redis Lua compensation is a distributed commit protocol.
- Redis Lua implementation is production-facing runtime code.

Safe Portfolio Wording:

> The Redis Lua experiment embeds one Lua script in the concurrency test fixture to atomically gate stock and duplicate acceptance inside Redis, then confirms durable issuance through PostgreSQL.

Avoid Wording:

> Redis Lua and PostgreSQL share one atomic transaction.

Notes:

- Docs correctly state the boundary in `docs/redis-consistency-boundary.md` lines 182-198 and `docs/coupon-concurrency-strategy-comparison.md` lines 380-405.

## Redis Evidence

### Redis Atomicity

Claim: Redis makes the front-line gate decision atomically.

Status: General Technical Reasoning.

Evidence:

- Redis Counter code calls `StringRedisTemplate.opsForValue().increment(key)` in `CouponIssueConcurrencyTest.java` line 816.
- Redis Lua code sends one script with `stringRedisTemplate.execute(...)` in `CouponIssueConcurrencyTest.java` lines 858-863.
- The embedded Lua script checks `SISMEMBER`, reads the count, conditionally calls `INCR` and `SADD`, then returns in `CouponIssueConcurrencyTest.java` lines 674-693.
- Existing docs describe Redis atomicity boundaries in `docs/redis-consistency-boundary.md` lines 155-168.

Safe Portfolio Wording:

> Redis is used as an atomic front-line gate through `INCR` or one Lua script, while final durable consistency is still verified in PostgreSQL.

Avoid Wording:

> Redis atomicity proves database durability.

Notes:

- Redis command/script atomicity is a platform property. The repository demonstrates use of those operations and verifies final state, but it does not include external Redis documentation or a formal Redis proof.

### PostgreSQL Persistence

Claim: Redis-accepted coupon issuance is persisted in PostgreSQL.

Status: Verified.

Evidence:

- Redis Counter calls `CouponRepository.increaseIssuedQuantityIfStockAvailable(...)` and saves `IssuedCoupon` after Redis acceptance in `CouponIssueConcurrencyTest.java` lines 831-841.
- Redis Lua calls `CouponRepository.increaseIssuedQuantityIfStockAvailable(...)` and `issuedCouponRepository.saveAndFlush(...)` after Redis acceptance in `CouponIssueConcurrencyTest.java` lines 879-885.
- Tests assert `Coupon.issuedQuantity` and issued record counts in `CouponIssueConcurrencyTest.java` lines 408-415, 472-482, and 545-554.

Safe Portfolio Wording:

> Redis-accepted requests still pass through PostgreSQL conditional stock update and issued-coupon persistence.

Avoid Wording:

> Redis acceptance alone means the coupon is durably issued.

Notes:

- PostgreSQL is the durable source of truth in docs and architecture: `docs/architecture.md` lines 21-30 and `docs/redis-consistency-boundary.md` lines 56-83.

### Runtime Implementation vs Test Fixture Implementation

Claim: Redis strategies are part of the production coupon service.

Status: Unsupported.

Evidence:

- `CouponIssueService` contains only `issueTransactionOnly(...)` in `CouponIssueService.java` lines 19-31.
- Redis Counter and Redis Lua methods are inside `CouponIssueConcurrencyTest.TestCouponIssueService` in `CouponIssueConcurrencyTest.java` lines 668-909.
- Current docs explicitly say Redis strategies are experiment paths, not production-facing service methods, in `docs/redis-consistency-boundary.md` lines 30-38 and `docs/architecture.md` lines 69-87.

Safe Portfolio Wording:

> Redis Counter and Redis Lua are implemented as concurrency experiment paths in the test fixture.

Avoid Wording:

> The production coupon service exposes Redis Counter and Redis Lua issuance methods.

Notes:

- This is one of the most important portfolio wording boundaries.

### Distributed Transaction Boundary

Claim: Redis and PostgreSQL share an atomic distributed transaction.

Status: Do Not Use.

Evidence:

- No transaction manager or two-phase commit implementation exists.
- Redis and PostgreSQL are separate services in `docker-compose.yml` lines 1-18.
- Docs state this boundary directly in `docs/redis-consistency-boundary.md` lines 182-198 and `docs/coupon-concurrency-strategy-comparison.md` lines 380-405.

Safe Portfolio Wording:

> The implementation uses best-effort compensation after Redis acceptance when a runtime failure occurs inside the tested database persistence block.

Avoid Wording:

> Redis and PostgreSQL commit atomically.

Notes:

- Compensation code exists, but there are no tests that inject compensation failure or process crash scenarios.

## Performance Evidence

Claim: The repository measures throughput, latency, database load, lock wait, CPU, or memory.

Status: Unsupported.

Evidence:

- `docs/architecture.md` states tests validate correctness rather than benchmark performance characteristics in lines 242-264 and lists detailed performance benchmarking as out of scope in lines 267-285.
- Search results show performance-related language in docs, but no benchmark harness, no metrics collection, no latency histograms, no database load measurement, no lock wait measurement, and no CPU/memory measurement.
- One documented `test duration: about 10 seconds` for pessimistic lock appears in docs such as `docs/coupon-concurrency-strategy-comparison.md` line 196 and `docs/history/coupon-concurrency-experiment-plan.md` lines 273 and 288. This is a historical observation, not a benchmark suite.

Safe Portfolio Wording:

> The project compares correctness scope and qualitative trade-offs. It does not contain benchmark measurements.

Avoid Wording:

> Redis reduced database load by a measured percentage.
> Pessimistic locking had measured lock wait latency.
> Atomic update had measured throughput gains.
> CPU and memory usage were profiled.

Notes:

- Any performance portfolio slide should be rewritten as design reasoning unless new benchmark code and captured results are added.

## Unsupported Claims

The following claims are not supported by this repository as checked in:

- Redis and PostgreSQL share a distributed transaction.
- Redis is the durable source of truth for coupon issuance.
- Redis compensation is a guaranteed distributed commit or recovery protocol.
- Redis Counter prevents same-user duplicate issuance by itself.
- Redis Lua replaces the database unique constraint.
- Redis Counter or Redis Lua are production-facing runtime coupon service methods.
- The repository includes Flyway, Liquibase, or raw SQL migrations.
- The repository includes standalone Redis Lua script files.
- The repository includes fixtures for deterministic concurrency schedules.
- The current active test suite reproduces the original coupon overselling baseline.
- The current active test suite reproduces the original coupon duplicate-issuance baseline.
- The current active point baseline test asserts the exact historical lost-update values.
- Throughput, latency, database load, lock wait, CPU, or memory were measured.
- Product, order, payment, coupon usage, coupon expiration, coupon usage concurrency, or payment integration are implemented.
- Coupon issuance is exposed through a controller/API endpoint.

## Historical Baselines

The following baseline results must be marked `Historical Result`:

| Baseline | Historical reason | Evidence |
| --- | --- | --- |
| Point lost update: `successCount = 15`, `finalBalance = 8000` | Observed before `Point.@Version`; current entity has version checking. | `docs/point-concurrency-strategy-comparison.md` lines 65-78; `docs/runbook.md` lines 171-179. |
| Coupon overselling: stock 100, 1,000 requests, `issuedCouponCountByCoupon = 1000`, `finalIssuedQuantity = 100` | Observed before `Coupon.@Version`; current disabled test preserves it. | `CouponIssueConcurrencyTest.java` lines 87-126; `docs/coupon-concurrency-strategy-comparison.md` lines 108-129. |
| Coupon duplicate issuance: same user 100 requests, `issuedCouponCountByUserAndCoupon = 10` | Observed before DB unique constraint; current disabled test preserves it. | `CouponIssueConcurrencyTest.java` lines 128-172; `docs/coupon-concurrency-strategy-comparison.md` lines 120-129. |
| Transaction-only implementation as current runtime path | The implementation path still exists, but the original failure outcomes are historical because version and constraints now affect behavior. | `PointService.java` lines 29-36; `CouponIssueService.java` lines 19-31; `docs/runbook.md` lines 171-188. |

## Verification Tasks

Use these tasks before publishing portfolio claims:

1. Run local services with `docker compose up -d`.
2. Run `./gradlew test`.
3. Run targeted tests when collecting evidence:
   - `./gradlew test --tests "com.roar.coupon.domain.point.service.PointServiceConcurrencyTest"`
   - `./gradlew test --tests "com.roar.coupon.domain.coupon.service.CouponIssueConcurrencyTest"`
4. Verify the live `issued_coupons` schema if making a DB-constraint claim:
   - `docker exec -it flash-coupon-postgres psql -U coupon_user -d flash_coupon`
   - `\d issued_coupons`
5. Save test output or schema output as a portfolio artifact if the separate portfolio repository requires reproducible evidence.
6. Add explicit tests before claiming Redis compensation correctness:
   - DB stock update fails after Redis acceptance.
   - IssuedCoupon insert fails after Redis acceptance.
   - Redis compensation command fails.
7. Add a benchmark harness before making performance claims:
   - throughput
   - latency
   - database load
   - lock wait
   - CPU
   - memory
8. Add migrations if claiming SQL-managed schema:
   - Flyway or Liquibase migration for `uk_issued_coupon_user_coupon`.
   - Captured schema verification artifact.

## Portfolio Release Checklist

- Mark point lost-update exact values as historical.
- Mark coupon overselling and duplicate baselines as historical.
- State that current coupon Redis strategies are test fixture implementations.
- State that PostgreSQL is the durable source of truth.
- State that Redis is a front-line gate, not a distributed transaction participant.
- Use `Partially Verified` for DB unique claims unless the portfolio includes captured schema verification or a migration.
- Remove or rewrite all benchmark/performance claims unless new benchmark evidence is added.
- Avoid saying "production API" for coupon issuance unless a controller/runtime path is added.
- Avoid saying "same exact conditions" when timing delays differ.
- Keep optimistic-lock wording scoped to conflict detection and final consistency without retry.

## Portfolio Claims Requiring Rewrite

Rewrite these claims because they are broader than implementation or tests:

| Original-style claim | Status | Rewrite |
| --- | --- | --- |
| The current test suite reproduces point lost update with `finalBalance = 8000`. | Historical Result | The exact point lost-update values are historical pre-`@Version` evidence; the current test preserves the baseline scenario but does not assert that value. |
| The current coupon tests reproduce overselling. | Historical Result | The overselling test is disabled and preserved as pre-`Coupon.@Version` historical evidence. |
| The current coupon tests reproduce duplicate issuance. | Historical Result | The duplicate baseline is disabled and preserved as pre-DB-unique historical evidence. |
| Redis Counter is implemented in the production coupon service. | Unsupported | Redis Counter is implemented in `CouponIssueConcurrencyTest.TestCouponIssueService` as a concurrency experiment path. |
| Redis Lua is implemented in the production coupon service. | Unsupported | Redis Lua is embedded and executed in the coupon concurrency test fixture. |
| Redis and PostgreSQL commit atomically together. | Do Not Use | Redis is a front-line gate; PostgreSQL is the durable source of truth; compensation is best-effort test-fixture code. |
| Redis Counter prevents duplicate issuance. | Unsupported | Redis Counter gates stock only; duplicate prevention remains the DB unique constraint unless Redis user tracking is added. |
| Redis Lua removes the need for a DB unique constraint. | Do Not Use | Redis Lua rejects duplicates in Redis, but the DB unique constraint remains the final duplicate guard. |
| DB UNIQUE is managed by SQL migration. | Unsupported | The unique constraint is mapped by JPA annotation; no migration file exists. |
| The project measured throughput or latency improvements. | Unsupported | The project documents qualitative trade-offs and correctness tests, not benchmark measurements. |
| Redis reduced DB load in measured results. | Unsupported | The repository contains reasoning about reducing DB write-path entry, not DB load metrics. |
| Lock wait, CPU, and memory were measured. | Unsupported | No such metrics are collected. |
| Coupon issuance is exposed as an API endpoint. | Unsupported | There is a coupon service, but no coupon controller endpoint in the current source. |
| Product/order/payment/coupon usage flows are implemented. | Unsupported | README and docs list those as planned or out of scope. |

## Safe Technical Statements

These statements are safe to use without modification:

- The project uses Java 21, Spring Boot, Spring Data JPA, PostgreSQL, Redis, Gradle, Docker Compose, Spring Security, and JUnit tests.
- PostgreSQL and Redis are configured as local Docker Compose services.
- Point deduction has runtime implementations for transaction-only, pessimistic lock, optimistic lock, and atomic update strategies.
- `Point` has a `@Version` field for optimistic locking.
- `PointRepository.findByUserIdForUpdate(...)` uses JPA `PESSIMISTIC_WRITE`.
- `PointRepository.deductIfEnoughBalance(...)` performs a conditional JPQL update.
- Coupon transaction-only issuance exists in `CouponIssueService.issueTransactionOnly(...)`.
- `Coupon` has a `@Version` field.
- `CouponRepository.findByIdWithPessimisticLock(...)` uses JPA `PESSIMISTIC_WRITE`.
- `CouponRepository.increaseIssuedQuantityIfStockAvailable(...)` performs a conditional JPQL stock update.
- `IssuedCoupon` maps a unique constraint named `uk_issued_coupon_user_coupon` on `(user_id, coupon_id)`.
- Active coupon tests verify DB unique constraint behavior, pessimistic lock stock control, optimistic lock stock control, atomic update stock control, Redis Counter stock gating, and Redis Lua stock and duplicate gating.
- Redis Counter and Redis Lua are implemented in the coupon concurrency test fixture.
- Redis Counter uses a Redis count key shaped like `coupon:issue:count:{couponId}`.
- Redis Lua uses a Redis count key and user set key shaped like `coupon:issue:count:{couponId}` and `coupon:issue:users:{couponId}`.
- Redis Lua returns `1` for accepted, `-1` for sold out, and `-2` for duplicate in the embedded script.
- Redis-accepted requests are still persisted through PostgreSQL in the test fixture.
- PostgreSQL remains the durable source of truth for coupon inventory and issued coupon records.
- Redis and PostgreSQL do not share an atomic commit boundary in this repository.
- Performance benchmarking is out of scope for the current tests.

## Summary Table

| Area | Status | Notes |
| --- | --- | --- |
| Page 2 Point lost-update baseline | Historical Result | Exact lost-update values are documented pre-`Point.@Version`; current active test does not assert them. |
| Page 2 Coupon overselling baseline | Historical Result | Disabled pre-`Coupon.@Version` test and docs preserve the result. |
| Page 2 Coupon duplicate baseline | Historical Result | Disabled pre-unique-constraint test and docs preserve the result. |
| CountDownLatch synchronization | Verified | Coupon helper uses timeout assertions; point tests use latches without timeout. |
| Final persisted state verification | Partially Verified | Active strategy tests assert final PostgreSQL state; Redis tests also assert Redis state; historical/weak baselines are narrower. |
| Same-condition comparison | Partially Verified | Core business scenarios are reused, but delays and fixture details differ. |
| Transaction Only | Historical Result | Implementation exists, but original failure outcomes are historical. |
| Pessimistic Lock | Verified | Point runtime and coupon test fixture; no performance measurements. |
| Optimistic Lock | Verified | Active tests and `@Version` support conflict detection and final consistency; no retry or deterministic all-schedules stock exhaustion guarantee. |
| Atomic Update | Verified | Conditional JPQL updates and active tests support simple balance and stock claims. |
| DB UNIQUE Constraint | Partially Verified | JPA mapping and active test exist; no SQL migration or captured schema output. |
| Redis Counter | Partially Verified | Verified as test fixture; not production runtime and not duplicate control. |
| Redis Lua Script | Partially Verified | Verified as test fixture; Redis atomicity is general reasoning; no distributed transaction. |
| Redis/PostgreSQL boundary | Verified | Docs and code show Redis gate plus PostgreSQL persistence; distributed transaction is not implemented. |
| Performance | Unsupported | No throughput, latency, DB load, lock wait, CPU, or memory measurements. |
| SQL migrations | Unsupported | No checked-in SQL/Flyway/Liquibase migrations. |
| Redis scripts | Partially Verified | Lua exists embedded in test Java, not as a standalone script file. |
| Fixtures | Unsupported | No fixture directory or deterministic scheduling fixture is checked in. |
