# Project Context

## Project Goal

This project is a backend portfolio project focused on concurrency control and data consistency.

The goal is to reproduce and solve real-world concurrency issues in a first-come coupon and point payment system.

This is not a typical CRUD project. The main purpose is to demonstrate how data consistency can break under concurrent requests and how different strategies can solve the problem.

## Tech Stack

- Java
- Spring Boot
- Spring Data JPA
- PostgreSQL
- Redis dependency included, planned for later experiments
- Gradle
- Docker Compose
- JWT Authentication

## Implemented So Far

### User Domain

- User entity
- Email-based signup
- Unique email constraint
- Password encoding with BCrypt

### Authentication

- JWT-based login
- JwtProvider
- JwtAuthenticationFilter
- SecurityConfig
- Stateless authentication
- Protected APIs using `Authorization: Bearer <token>`

### Point Domain

- Point entity
- Point repository
- Point wallet creation during signup
- Point charge API
- Point deduct API
- Point balance query API
- Point concurrency test for concurrent deduction
- Pessimistic lock point deduction experiment
- Optimistic lock point deduction experiment
- Atomic update point deduction experiment

The current Point implementation keeps multiple strategies for comparison:

- Transaction-only read-modify-write baseline
- Pessimistic lock deduction using `PESSIMISTIC_WRITE`
- Optimistic lock deduction using `@Version`
- Atomic update deduction using a conditional update query

Not applied yet:

- Redis
- Distributed lock
- `synchronized`

### Coupon Domain

- Coupon entity
- IssuedCoupon entity
- Coupon repository
- IssuedCoupon repository
- Transaction-only coupon issuance baseline
- Overselling concurrency reproduction
- Duplicate issuance concurrency reproduction
- DB unique constraint on `(userId, couponId)`
- Duplicate issuance prevention test using the DB unique constraint

The current Coupon implementation keeps the experiment scope focused on coupon issuance, not payment integration.

Completed coupon experiments:

- Transaction-only baseline for overselling reproduction
- Transaction-only baseline for duplicate issuance reproduction
- DB unique constraint experiment for duplicate issuance prevention

Planned coupon experiments:

- Pessimistic lock for stock control
- Optimistic lock for stock control
- Atomic update for stock control
- Redis counter for traffic gating
- Redis Lua script for stock and duplicate checks

## Current Focus

The current focus is the Coupon issuance domain.

The Point domain has completed the baseline, pessimistic lock, optimistic lock, and atomic update comparison.
The Coupon domain now follows the same learning pattern:

- Reproduce failures with a transaction-only baseline.
- Add one consistency mechanism at a time.
- Verify final database state under concurrent requests.
- Preserve observed results for portfolio explanation.

## Transaction-Only Baseline Result

Scenario:

- Initial balance: 10,000
- Concurrent requests: 15
- Deduct amount per request: 1,000

Expected correct result:

- successCount = 10
- failCount = 5
- finalBalance = 0

Actual result:

- successCount = 15
- failCount = 0
- finalBalance = 8000
- expectedBalanceBySuccessCount = -5000

This result demonstrates a lost update problem.

All 15 requests were counted as successful, but the final persisted balance does not reflect 15 successful deductions.
Several transactions read the same balance before other transactions committed, then overwrote each other's updates.

Note:

After adding `@Version` to the Point entity for optimistic locking, the transaction-only path is also affected by JPA version checking.
Therefore, this lost update result is preserved as the baseline result observed before `@Version` was added.

## Pessimistic Lock Result

The pessimistic lock version uses `PointService.deductWithPessimisticLock()`.
It reads the Point row with a database write lock before deducting points.

Same scenario:

- Initial balance: 10,000
- Concurrent requests: 15
- Deduct amount per request: 1,000

Observed result:

- successCount = 10
- failCount = 5
- finalBalance = 0

This result shows that row-level locking serializes concurrent deductions for the same Point row and prevents lost update.

## Optimistic Lock Result

The optimistic lock version uses `PointService.deductWithOptimisticLock()`.
The Point entity has an `@Version` field, so JPA detects version conflicts during update.

Same scenario:

- Initial balance: 10,000
- Concurrent requests: 15
- Deduct amount per request: 1,000

Observed example:

- successCount = 3
- failCount = 12
- finalBalance = 7000
- expectedBalanceBySuccessCount = 7000

The exact success count can vary depending on thread scheduling.
The important result is that failed requests are optimistic lock conflicts and the final balance matches the number of successful deductions.

## Atomic Update Result

The atomic update version uses `PointService.deductWithAtomicUpdate()`.
It performs the balance check and deduction in a single conditional update query.

Same scenario:

- Initial balance: 10,000
- Concurrent requests: 15
- Deduct amount per request: 1,000

Observed result:

- successCount = 10
- failCount = 5
- finalBalance = 0
- expectedBalanceBySuccessCount = 0

This result shows that the database can enforce the balance condition and update atomically without loading the Point entity first.

## Coupon Transaction-Only Overselling Result

Scenario:

- Coupon stock: 100
- Concurrent requests: 1,000
- Users: 1,000 distinct users

Expected correct result:

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

Observed result:

- successCount = 1000
- failCount = 0
- issuedCouponCountByCoupon = 1000
- finalIssuedQuantity = 100

This result demonstrates overselling and inventory-record mismatch.
All 1,000 requests created issued coupon records even though the coupon stock was 100.
The final `issuedQuantity` stayed at 100, so the Coupon row and IssuedCoupon records diverged.

## Coupon Transaction-Only Duplicate Issuance Result

Scenario:

- Coupon stock: 1,000
- Concurrent requests: 100
- User: same user requesting the same coupon

Expected correct result:

- successCount = 1
- failCount = 99
- issuedCouponCountByUserAndCoupon = 1

Observed baseline result before applying the DB unique constraint:

- successCount = 10
- failCount = 90
- issuedCouponCountByUserAndCoupon = 10

This result demonstrates that application-level duplicate checks are not enough under concurrency.
Multiple transactions can pass the duplicate check before another transaction's insert is visible.

## Coupon DB Unique Constraint Result

The DB unique constraint version enforces uniqueness for `(userId, couponId)`.

Same scenario:

- Coupon stock: 1,000
- Concurrent requests: 100
- User: same user requesting the same coupon

Expected correct result:

- successCount = 1
- failCount = 99
- issuedCouponCountByUserAndCoupon = 1

Observed result:

- successCount = 1
- failCount = 99
- issuedCouponCountByUserAndCoupon = 1

This result shows that the database constraint acts as the final consistency guard.
Even if multiple transactions pass the application-level duplicate check, only one insert can commit for the same user and coupon.

## Planned Domains and Strategies

- Product
- Order
- Coupon stock-control strategies:
  - Pessimistic lock
  - Optimistic lock
  - Atomic update
  - Redis counter
  - Redis Lua script

## Design Philosophy

- Start simple
- Implement a naive read-modify-write baseline first
- Intentionally reproduce concurrency problems
- Then solve them step by step
- Avoid over-engineering in early stages
- Prioritize code and tests that can be explained in interviews
