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

The current Point implementation keeps multiple strategies for comparison:

- Transaction-only read-modify-write baseline
- Pessimistic lock deduction using `PESSIMISTIC_WRITE`
- Optimistic lock deduction using `@Version`

Not applied yet:

- Atomic update query
- Redis
- Distributed lock
- `synchronized`

## Current Focus

The current focus is the Point domain.

The Point domain will be used as the foundation for:

- Point charging
- Point deduction
- Payment logic
- Concurrency experiments
- Data consistency validation

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

## Planned Domains and Strategies

- Product
- Coupon
- IssuedCoupon
- Order
- Atomic update
- Redis

## Design Philosophy

- Start simple
- Implement a naive read-modify-write baseline first
- Intentionally reproduce concurrency problems
- Then solve them step by step
- Avoid over-engineering in early stages
- Prioritize code and tests that can be explained in interviews
