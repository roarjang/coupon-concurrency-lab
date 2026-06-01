# Concurrency Experiment Plan

## Purpose

This document defines how concurrency issues will be reproduced and solved in this project.

The goal is not only to implement working code, but also to compare different consistency strategies and explain their trade-offs.

## Target Scenario

Point deduction under concurrent requests.

## Common Test Scenario

- Initial balance: 10,000
- Number of concurrent requests: 15
- Deduct amount per request: 1,000

## Correct Expected Result

- 10 requests should succeed
- 5 requests should fail
- Final balance should be 0
- Balance should never become negative
- Successful deduction count and final balance should be consistent

## Current Baseline: Transaction-Only Read-Modify-Write

### Description

The current Point implementation is an `@Transactional`-based naive read-modify-write implementation.

It uses service-level transactions, but does not use explicit concurrency control.

Flow:

1. Read Point by userId
2. Check current balance
3. Deduct amount from balance
4. Commit transaction

Currently not used:

- Pessimistic lock
- Optimistic lock
- Atomic update query
- Redis
- Distributed lock
- `synchronized`

### Expected Problem

Lost update may occur.

Multiple transactions can read the same balance before other transactions commit.

### Expected Result

The result may be inconsistent.

Examples:

- More than 10 requests may succeed
- Final balance may not match the number of successful deductions
- Lost update may occur

### Observed Result

Scenario:

- Initial balance: 10,000
- Number of concurrent requests: 15
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

### Interpretation

The success count and final balance are logically inconsistent.

If 15 requests each deducted 1,000 points from an initial balance of 10,000, the balance implied by the success count would be -5,000.
However, the actual persisted balance is 8,000.

This means several successful requests did not survive in the final database state.
Multiple transactions read the same old balance, applied their own deduction, and later updates overwrote earlier updates.

This demonstrates that `@Transactional` alone does not serialize concurrent requests and does not prevent lost update.

## Strategy 1. Pessimistic Lock

### Description

Use database row-level lock.

Expected SQL behavior:

```sql
SELECT ...
FOR UPDATE
```

Expected Result:

Concurrent deductions are serialized for the same Point row.

Trade-off:

- Strong consistency
- Simple reasoning
- Lower throughput under high contention
- Possible lock wait or deadlock issues

Status:

Planned as the next implementation step.

## Strategy 2. Optimistic Lock

Description:

Use a version column with JPA @Version.

Point does not have an `@Version` field yet.

Expected Result:

Concurrent updates are detected by version mismatch.

Trade-off:

- Good when conflicts are rare
- Requires retry handling
- Can fail frequently under high contention

Status:

Planned.

## Strategy 3. Atomic Update

Description:

Use a single conditional update query.

Example:

```SQL
UPDATE points
SET balance = balance - :amount
WHERE user_id = :userId
AND balance >= :amount
```

Expected Result:

The database performs the check and update atomically.

Trade-off:

- Efficient
- Good for simple balance deduction
- Less object-oriented domain logic
- More query-centered design

Status:

Planned.

## Strategy 4. Redis

Description:

Use Redis later for coupon issuance or traffic control experiments.
Redis is not actively used in the current Point implementation.

Status:

Planned.
