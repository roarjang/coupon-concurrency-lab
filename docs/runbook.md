# Concurrency Experiment Runbook

## 1. Purpose

This runbook describes how to reproduce and verify the local concurrency experiments in this repository.

The goal is to make the documented Point and Coupon concurrency results executable from a local checkout. This document covers environment setup, local services, test commands, verification targets, and common troubleshooting steps.

Detailed strategy behavior, observed results, and trade-off discussions are documented separately in the strategy comparison documents.

## 2. Prerequisites

Required tools:

- Java 21
- Docker
- Docker Compose
- Gradle Wrapper from this repository: `./gradlew`

The project uses the Java toolchain setting in `build.gradle`, so the test runtime must be able to provide Java 21.

The repository includes `gradlew`, so a separate local Gradle installation is not required for normal execution. The first Gradle run may download dependencies and Spring Boot snapshot artifacts.

## 3. Repository Layout

Important files and directories:

| Path | Purpose |
| --- | --- |
| `README.md` | Project summary and documentation entry point |
| `docs/architecture.md` | Current implemented system architecture |
| `docs/point-concurrency-strategy-comparison.md` | Detailed Point concurrency experiment results and trade-offs |
| `docs/coupon-concurrency-strategy-comparison.md` | Detailed Coupon concurrency experiment results and trade-offs |
| `docs/coupon-domain-design.md` | Coupon and IssuedCoupon domain model and consistency constraints |
| `docs/redis-consistency-boundary.md` | Redis and PostgreSQL consistency boundary for coupon issuance |
| `docs/history/` | Historical planning and development records |
| `docker-compose.yml` | Local PostgreSQL and Redis services |
| `src/main/resources/application.yml` | Main Spring Boot configuration used by local tests |
| `src/test/java/com/roar/coupon/domain/point/service/PointServiceConcurrencyTest.java` | Point concurrency tests |
| `src/test/java/com/roar/coupon/domain/coupon/service/CouponIssueConcurrencyTest.java` | Coupon concurrency tests |

There is no separate `application-test.yml` in the current repository. Tests use the configured local PostgreSQL and Redis services.

## 4. Local Services

The concurrency tests use real PostgreSQL and Redis services from Docker Compose.

Start local services:

```bash
docker compose up -d
```

Check service status:

```bash
docker compose ps
```

Stop services:

```bash
docker compose down
```

Stop services and remove persisted PostgreSQL data:

```bash
docker compose down -v
```

Expected local ports:

| Service | Container | Port |
| --- | --- | --- |
| PostgreSQL | `flash-coupon-postgres` | `localhost:5432` |
| Redis | `flash-coupon-redis` | `localhost:6379` |

PostgreSQL uses a Docker volume named `postgres_data`, so schema and data can persist across service restarts unless the volume is removed.

## 5. Application Configuration

The main runtime configuration is in `src/main/resources/application.yml`.

High-level configuration:

- PostgreSQL is configured as the relational database.
- Redis is configured on `localhost:6379`.
- JPA uses `ddl-auto=update`.
- SQL initialization is disabled.
- Hibernate SQL logging is enabled.

The tests rely on PostgreSQL for durable Point, Coupon, and IssuedCoupon state. Redis is used by the Redis Counter and Redis Lua coupon issuance experiments as a front-line gate before database persistence.

Because `ddl-auto=update` is used, verify schema constraints explicitly when they are part of the experiment result. This is especially relevant for the IssuedCoupon unique constraint on `(user_id, coupon_id)`.

## 6. Running the Test Suite

Start PostgreSQL and Redis before running tests:

```bash
docker compose up -d
```

Run the full test suite:

```bash
./gradlew test
```

Run only the Point concurrency tests:

```bash
./gradlew test --tests "com.roar.coupon.domain.point.service.PointServiceConcurrencyTest"
```

Run only the Coupon concurrency tests:

```bash
./gradlew test --tests "com.roar.coupon.domain.coupon.service.CouponIssueConcurrencyTest"
```

The coupon tests create high-contention scenarios with up to 1,000 concurrent tasks. Runtime can vary by machine, Docker resource allocation, and database state.

## 7. Experiment Coverage Map

| Domain | Strategy | Test Class | Status |
| --- | --- | --- | --- |
| Point | Transaction-only baseline | `PointServiceConcurrencyTest` | Active |
| Point | Pessimistic Lock | `PointServiceConcurrencyTest` | Active |
| Point | Optimistic Lock without retry | `PointServiceConcurrencyTest` | Active |
| Point | Atomic Update | `PointServiceConcurrencyTest` | Active |
| Coupon | Transaction-only overselling baseline | `CouponIssueConcurrencyTest` | Historical |
| Coupon | Transaction-only duplicate issuance baseline | `CouponIssueConcurrencyTest` | Historical |
| Coupon | DB Unique Constraint | `CouponIssueConcurrencyTest` | Active |
| Coupon | Pessimistic Lock | `CouponIssueConcurrencyTest` | Active |
| Coupon | Optimistic Lock without retry | `CouponIssueConcurrencyTest` | Active |
| Coupon | Atomic Update | `CouponIssueConcurrencyTest` | Active |
| Coupon | Redis Counter | `CouponIssueConcurrencyTest` | Active |
| Coupon | Redis Lua Script | `CouponIssueConcurrencyTest` | Active |

Historical tests are preserved as disabled scenarios in the current test class. Their observed results are documented in the comparison documents.

The Point transaction-only test remains active, but the exact documented lost-update baseline values were observed before `@Version` was added to the Point entity. The current active test preserves the baseline path, while the detailed historical interpretation is documented in the Point comparison document.

## 8. Expected Verification Targets

The tests should be evaluated by final persisted state, not only by request counters.

Point verification targets:

- Transaction-only baseline preserves the read-modify-write path used for the original lost-update observation.
- Pessimistic Lock and Atomic Update should produce a consistent final balance for the fixed test scenario.
- Optimistic Lock should detect conflicts, and the final balance should match the number of successful deductions.
- The Point balance should not become negative.

Coupon verification targets:

- Stock-control strategies should not create more IssuedCoupon records than coupon stock.
- `Coupon.issuedQuantity` should not exceed `Coupon.totalQuantity`.
- `Coupon.issuedQuantity` should match the successful issued record count for the tested synchronous scenarios.
- Duplicate-control strategies should allow at most one IssuedCoupon for the same `(user_id, coupon_id)`.
- Redis Counter should keep the Redis accepted count aligned with successful database persistence in the tested scenario.
- Redis Lua should keep both the Redis count key and Redis issued-user set aligned with successful database persistence in the tested scenarios.

For detailed observed values and strategy-specific interpretation, use:

- `docs/point-concurrency-strategy-comparison.md`
- `docs/coupon-concurrency-strategy-comparison.md`

## 9. Historical Baseline Tests

Some baseline observations are historical because later implementation changes intentionally affect the original failure mode.

The Point lost-update baseline was observed before `Point.version` was added for the optimistic-lock experiment. After `@Version` is present, the same entity is subject to optimistic version checking. The active transaction-only test still exercises the baseline path, but the exact documented lost-update values are treated as historical evidence.

The transaction-only coupon overselling baseline was observed before `Coupon.version` was added for the optimistic-lock experiment. After `@Version` is present, the transaction-only stock path is affected by JPA optimistic version checking, so it no longer represents the original no-version baseline behavior.

The transaction-only duplicate issuance baseline was observed before the database unique constraint on `(user_id, coupon_id)` became part of the current schema. Once the unique constraint exists, the database intentionally blocks the original duplicate-insert failure.

These historical results are preserved in:

- `docs/point-concurrency-strategy-comparison.md`
- `docs/coupon-concurrency-strategy-comparison.md`
- `docs/history/coupon-concurrency-experiment-plan.md`
- `docs/history/implementation-roadmap.md`

The active test suite focuses on verifying the current implemented strategies and final consistency invariants.

## 10. Database Verification

Open a PostgreSQL shell inside the container:

```bash
docker exec -it flash-coupon-postgres psql -U coupon_user -d flash_coupon
```

List tables:

```sql
\dt
```

Inspect the IssuedCoupon table and verify the unique constraint:

```sql
\d issued_coupons
```

The schema should include a unique constraint for `(user_id, coupon_id)`, named `uk_issued_coupon_user_coupon` in the JPA mapping.

Example inspection queries:

```sql
select id, total_quantity, issued_quantity, version
from coupons
order by id desc
limit 10;
```

```sql
select coupon_id, count(*) as issued_count
from issued_coupons
group by coupon_id
order by coupon_id desc;
```

```sql
select user_id, coupon_id, count(*) as duplicate_count
from issued_coupons
group by user_id, coupon_id
having count(*) > 1;
```

The last query should return no rows when the unique constraint is present and the active duplicate-control tests pass.

## 11. Redis Verification

Open a Redis CLI session inside the container:

```bash
docker exec -it flash-coupon-redis redis-cli
```

Relevant Redis key patterns:

| Strategy | Key | Meaning |
| --- | --- | --- |
| Redis Counter | `coupon:issue:count:{couponId}` | Accepted stock slot count |
| Redis Lua Script | `coupon:issue:count:{couponId}` | Accepted stock slot count |
| Redis Lua Script | `coupon:issue:users:{couponId}` | Accepted user ids for a coupon |

Example commands:

```redis
KEYS coupon:issue:*
```

```redis
GET coupon:issue:count:{couponId}
```

```redis
SCARD coupon:issue:users:{couponId}
```

```redis
SMEMBERS coupon:issue:users:{couponId}
```

Replace `{couponId}` with the coupon id created during the test run. The tests also assert Redis state directly for Redis Counter and Redis Lua scenarios.

## 12. Troubleshooting

PostgreSQL connection failure:

- Confirm Docker Compose services are running with `docker compose ps`.
- Check that port `5432` is not already occupied by another local PostgreSQL process.
- Restart services with `docker compose down` and `docker compose up -d`.

Redis connection failure:

- Confirm the Redis container is running.
- Check that port `6379` is not already occupied by another local Redis process.

Unexpected schema or constraint behavior:

- Inspect the schema with `\d issued_coupons`.
- If stale schema state is suspected, stop services and remove the PostgreSQL volume with `docker compose down -v`, then restart services and rerun tests.
- Be careful when removing the volume because it deletes local database state.

Slow or timing-sensitive tests:

- Coupon tests create high-contention scenarios and may take longer on machines with limited CPU or Docker resources.
- The tests use latch timeouts to avoid waiting indefinitely.
- Rerun a targeted test class when diagnosing a single domain.

Gradle dependency resolution issues:

- The project uses Spring Boot snapshot artifacts and includes the Spring snapshot repository in Gradle configuration.
- If dependency resolution fails, retry after confirming network access and Gradle cache state.

Redis keys from previous runs:

- Redis tests delete the keys they use for each created coupon id.
- If manual cleanup is needed during inspection, delete matching keys from `redis-cli`.

```redis
DEL coupon:issue:count:{couponId}
DEL coupon:issue:users:{couponId}
```

## 13. Related Documents

- [README](../README.md)
- [Architecture](architecture.md)
- [Point Concurrency Strategy Comparison](point-concurrency-strategy-comparison.md)
- [Coupon Concurrency Strategy Comparison](coupon-concurrency-strategy-comparison.md)
- [Coupon Domain Design](coupon-domain-design.md)
- [Redis Consistency Boundary](redis-consistency-boundary.md)
- [Implementation Roadmap](history/implementation-roadmap.md)
