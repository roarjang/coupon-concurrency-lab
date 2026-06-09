# 선착순 쿠폰 발급 및 포인트 결제 정합성 실험 프로젝트

## 1. 프로젝트 목적

이 프로젝트는 선착순 쿠폰 발급과 포인트 결제 과정에서 발생하는 동시성 문제를 직접 재현하고,
Redis 원자 연산과 DB 트랜잭션/락을 사용해 데이터 정합성을 보장하는 백엔드 실험 프로젝트입니다.

핵심 목표는 단순 CRUD 구현이 아니라, 다음 문제를 재현하고 해결하는 것입니다.

## 2. 핵심 문제

- 쿠폰 재고보다 많은 쿠폰이 발급되는 문제
- 동일 사용자가 같은 쿠폰을 중복 발급받는 문제
- 동일 쿠폰이 여러 주문에 중복 사용되는 문제
- 동시에 포인트가 차감될 때 잔액이 음수가 되는 문제

인증, 상품 조회, 포인트 충전 등의 기능은 실험을 위한 최소 보조 기능이며,
프로젝트의 핵심은 동시성 제어와 정합성 검증입니다.

## 3. 현재 구현 상태와 MVP 기능 범위

### 3.1 현재 구현된 기능

1. 회원가입 / 로그인
2. JWT 기반 인증
3. 회원가입 시 포인트 지갑 생성
4. 포인트 충전
5. 포인트 차감
6. 포인트 잔액 조회
7. 포인트 차감 동시성 테스트
8. 포인트 차감 비관적 락 실험
9. 포인트 차감 낙관적 락 실험
10. 포인트 차감 조건부 UPDATE 실험
11. 선착순 쿠폰 발급 transaction-only baseline
12. 쿠폰 초과 발급 동시성 재현
13. 동일 사용자 쿠폰 중복 발급 동시성 재현
14. DB UNIQUE(user_id, coupon_id) 제약 조건을 통한 중복 발급 방지 실험
15. UNIQUE 제약 조건 적용 후 중복 발급 방지 동시성 테스트
16. 쿠폰 재고 제어 비관적 락 실험
17. 쿠폰 재고 제어 비관적 락 동시성 테스트
18. 쿠폰 재고 제어 낙관적 락 실험
19. 쿠폰 재고 제어 낙관적 락 동시성 테스트
20. 쿠폰 재고 제어 조건부 UPDATE 실험
21. 쿠폰 재고 제어 조건부 UPDATE 동시성 테스트

현재 Point 구현은 `@Transactional` 기반 read-modify-write baseline, 비관적 락 적용 버전, 낙관적 락 적용 버전, 조건부 UPDATE 적용 버전을 비교합니다.
현재 Coupon 구현은 `@Transactional` 기반 transaction-only baseline으로 초과 발급과 동일 사용자 중복 발급을 재현한 뒤, 동일 사용자 중복 발급 문제는 DB UNIQUE 제약 조건으로 방지했습니다.
쿠폰 재고 제어는 비관적 락, 낙관적 락, 조건부 UPDATE를 적용해 stock 100, 동시 요청 1,000개 조건에서 발급 수량과 발급 내역 수가 모두 100건으로 유지되는 것을 검증했습니다.
Redis, `synchronized`는 아직 적용하지 않았습니다.

### 3.2 계획된 기능

1. 상품 조회
2. 쿠폰 재고 정합성 보장 전략 비교
3. 쿠폰 중복 발급 방지 전략 추가 비교
4. 쿠폰 적용 결제
5. 내 쿠폰 조회
6. 테스트용 쿠폰 데이터 세팅
7. 포인트 차감 정합성 보장 전략 추가 비교

## 4. 동시성 실험 설계

쿠폰 발급 도메인은 transaction-only baseline, DB UNIQUE 제약 조건, 쿠폰 재고 제어 비관적 락, 쿠폰 재고 제어 낙관적 락, 쿠폰 재고 제어 조건부 UPDATE 실험까지 구현되었습니다.
transaction-only baseline에서는 초과 발급과 동일 사용자 중복 발급 문제가 재현되었고, UNIQUE 제약 조건 적용 후에는 동일 사용자 중복 발급 방지가 검증되었습니다.
비관적 락 적용 후에는 같은 쿠폰 row의 재고 갱신이 직렬화되어 초과 발급과 발급 수량 불일치가 방지되는 것을 확인했습니다.
낙관적 락 적용 후에는 `Coupon.version` 기반 version check로 동시 갱신 충돌을 감지해 초과 발급과 발급 수량 불일치가 방지되는 것을 확인했습니다.
조건부 UPDATE 적용 후에는 PostgreSQL이 `issuedQuantity < totalQuantity` 조건 확인과 발급 수량 증가를 하나의 UPDATE로 처리해 초과 발급과 발급 수량 불일치가 방지되는 것을 확인했습니다.
상품, 주문 기반 결제 도메인과 쿠폰 사용 실험은 아직 계획 단계입니다.

### 4.1. 쿠폰 초과 발급

- 조건: 쿠폰 수량 100개, 동시 요청 1,000개
- 재현하려는 문제: 여러 요청이 동시에 쿠폰 재고를 읽고 갱신하면서 실제 재고보다 많은 쿠폰이 발급될 수 있다.
- naive 구현의 한계: 단순히 현재 발급 수량을 조회한 뒤 증가시키는 방식은 race condition으로 인해 초과 발급이 발생할 수 있다.
- 해결 전략: DB 비관적 락, 낙관적 락, 조건부 UPDATE를 비교하고, 이후 Redis 원자 연산 전략과 비교한다.
- 검증 기준: 최종 발급 수량은 정확히 100개여야 한다.

#### 관측 결과: transaction-only baseline

테스트 시나리오:

- 쿠폰 재고: 100
- 동시 요청 수: 1,000
- 사용자 조건: 1,000명의 서로 다른 사용자

정합성이 보장된다면 다음 결과가 나와야 합니다.

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

`@Version` 적용 전 `@Transactional` 기반 쿠폰 발급 baseline에서는 다음 결과가 관측되었습니다.

- successCount = 1000
- failCount = 0
- issuedCouponCountByCoupon = 1000
- finalIssuedQuantity = 100

발급 내역은 1,000건 생성되었지만 쿠폰 재고는 100개뿐입니다.
또한 `finalIssuedQuantity`는 100으로 남아 있어 실제 발급 내역 수와 쿠폰의 발급 수량 값이 서로 일치하지 않습니다.
이는 여러 트랜잭션이 같은 쿠폰 재고 상태를 동시에 읽고 발급 가능하다고 판단한 뒤, 각자 발급 내역을 저장한 concurrency failure입니다.

이 결과는 쿠폰 재고 확인, 발급 수량 증가, 발급 내역 저장을 하나의 트랜잭션 안에서 처리하더라도 동시 접근 자체가 직렬화되지는 않는다는 점을 보여줍니다.
따라서 transaction-only baseline은 문제 재현용 historical baseline으로 보존하고, 이후 락/조건부 UPDATE/Redis 전략과 비교합니다.
현재 `Coupon` 엔티티에는 낙관적 락 실험을 위한 `@Version` 필드가 추가되어 같은 transaction-only 경로도 version check 영향을 받습니다.
그래서 이 overselling 재현 테스트는 현재 활성 테스트가 아니라 `@Disabled` 상태로 보존되어 있으며, 관측값은 `@Version` 적용 전 결과로 문서화합니다.

#### 관측 결과: 비관적 락 적용

테스트 시나리오:

- 쿠폰 재고: 100
- 동시 요청 수: 1,000
- 사용자 조건: 1,000명의 서로 다른 사용자
- 락 경합 관측용 지연: `PESSIMISTIC_LOCK_HOLD_MILLIS = 5L`

정합성이 보장된다면 다음 결과가 나와야 합니다.

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

비관적 락 적용 후 다음 결과가 관측되었습니다.

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100
- 테스트 소요 시간: 약 10초

쿠폰 row를 `PESSIMISTIC_WRITE`로 조회한 뒤 재고 확인, 발급 수량 증가, 발급 내역 저장을 처리하면 같은 쿠폰에 대한 요청이 직렬화됩니다.
따라서 100개 요청만 발급에 성공하고 이후 요청은 재고 소진으로 실패합니다.
이 전략은 쿠폰 재고 초과 발급과 `Coupon.issuedQuantity`/IssuedCoupon record 불일치를 방지하지만, 동일 사용자 중복 발급의 최종 방어는 여전히 DB UNIQUE 제약 조건이 담당합니다.

#### 관측 결과: 낙관적 락 적용

테스트 시나리오:

- 쿠폰 재고: 100
- 동시 요청 수: 1,000
- 사용자 조건: 1,000명의 서로 다른 사용자
- 경합 관측용 지연: `LOCK_HOLD_MILLIS = 5L`

정합성이 보장된다면 다음 결과가 나와야 합니다.

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

낙관적 락 적용 후 다음 결과가 관측되었습니다.

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

`Coupon` 엔티티에 `@Version` 필드를 추가하고 일반 조회 후 발급 수량을 증가시키면, JPA가 update 시점에 version mismatch를 감지합니다.
충돌한 요청은 실패하고 커밋된 요청만 발급 내역을 생성하므로, 발급 내역 수와 `Coupon.issuedQuantity`가 모두 100건으로 유지됩니다.
현재 실험은 retry 없이 충돌 감지와 최종 정합성만 검증합니다.

#### 관측 결과: 조건부 UPDATE 적용

테스트 시나리오:

- 쿠폰 재고: 100
- 동시 요청 수: 1,000
- 사용자 조건: 1,000명의 서로 다른 사용자
- 충돌 관측용 지연: `LOCK_HOLD_MILLIS = 5L`

정합성이 보장된다면 다음 결과가 나와야 합니다.

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

조건부 UPDATE 적용 후 다음 결과가 관측되었습니다.

- successCount = 100
- failCount = 900
- issuedCouponCountByCoupon = 100
- finalIssuedQuantity = 100

쿠폰을 먼저 엔티티로 조회해 수정하지 않고, PostgreSQL에서 다음 조건부 UPDATE로 재고 확인과 발급 수량 증가를 한 번에 처리합니다.

```sql
UPDATE coupons
SET issued_quantity = issued_quantity + 1,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :couponId
AND issued_quantity < total_quantity
```

update row count가 1이면 재고 확보에 성공한 것이고, 0이면 재고가 소진된 것으로 판단합니다.
따라서 100개 요청만 발급에 성공하고 이후 요청은 발급 내역을 생성하기 전에 실패합니다.

이 방식은 비관적 락처럼 엔티티 조회부터 도메인 메서드 실행까지 row lock을 들고 진행하지 않고, 낙관적 락처럼 엔티티를 로딩한 뒤 flush 시점의 version conflict에 의존하지도 않습니다.
재고 제어 판단은 조건부 UPDATE의 affected row count가 담당합니다.

이 실험의 핵심은 재고 정합성입니다.
다만 JPQL bulk update는 `@PreUpdate`를 우회하므로, `updatedAt`은 쿼리에서 직접 갱신했습니다.
조건부 UPDATE만으로 동일 사용자 중복 발급을 해결하는 것은 아니며, 중복 발급의 최종 방어는 여전히 `UNIQUE(user_id, coupon_id)` 제약 조건이 담당합니다.
이 재고 제어 테스트는 서로 다른 사용자로 실행되므로 IssuedCoupon 저장은 `save`로 충분하며, 중복 키 검출을 강제로 앞당기기 위한 `saveAndFlush`는 필요하지 않습니다.

### 4.2. 쿠폰 중복 발급

- 조건: 동일 사용자가 같은 쿠폰에 대해 동시에 여러 번 발급 요청
- 재현하려는 문제: 중복 발급 여부를 확인하는 로직과 발급 내역을 저장하는 로직 사이에서 race condition이 발생할 수 있다.
- naive 구현의 한계: 애플리케이션에서만 중복 여부를 검사하면 동시에 들어온 요청을 모두 통과시킬 수 있다.
- 해결 전략: DB의 UNIQUE(user_id, coupon_id) 제약 조건과 트랜잭션을 함께 사용한다.
- 검증 기준: 한 사용자는 동일 쿠폰을 1개만 발급받을 수 있어야 한다.

#### 관측 결과: transaction-only baseline

테스트 시나리오:

- 쿠폰 재고: 1,000
- 동시 요청 수: 100
- 사용자 조건: 동일 사용자 1명이 같은 쿠폰에 대해 100번 동시 요청

정합성이 보장된다면 다음 결과가 나와야 합니다.

- successCount = 1
- failCount = 99
- issuedCouponCountByUserAndCoupon = 1

현재 `@Transactional` 기반 쿠폰 발급 baseline에서는 다음 결과가 관측되었습니다.

- successCount = 10
- failCount = 90
- issuedCouponCountByUserAndCoupon = 10

동일 사용자와 동일 쿠폰 조합의 발급 내역이 10건 생성되었습니다.
한 사용자는 같은 쿠폰을 1개만 발급받아야 하므로, 이는 중복 발급 방어가 실패한 결과입니다.
동시에 실행된 여러 트랜잭션이 모두 "아직 발급받지 않음" 상태를 읽고 발급 로직을 통과했기 때문에 발생한 concurrency failure입니다.

이 결과는 애플리케이션 레벨의 중복 조회만으로는 동일 사용자와 동일 쿠폰 조합의 유일성을 보장할 수 없음을 보여줍니다.
중복 발급 방지는 이후 DB UNIQUE 제약 조건을 포함한 전략에서 비교합니다.

#### 관측 결과: DB UNIQUE 제약 조건 적용

테스트 시나리오:

- 쿠폰 재고: 1,000
- 동시 요청 수: 100
- 사용자 조건: 동일 사용자 1명이 같은 쿠폰에 대해 100번 동시 요청
- 적용 전략: `UNIQUE(user_id, coupon_id)` 제약 조건과 중복 키 예외 처리

정합성이 보장된다면 다음 결과가 나와야 합니다.

- successCount = 1
- failCount = 99
- issuedCouponCountByUserAndCoupon = 1

UNIQUE 제약 조건 적용 후 다음 결과가 관측되었습니다.

- successCount = 1
- failCount = 99
- issuedCouponCountByUserAndCoupon = 1

여러 트랜잭션이 동시에 애플리케이션 레벨 중복 조회를 통과하더라도, 데이터베이스가 동일한 `(user_id, coupon_id)` 조합의 두 번째 insert를 거부합니다.
따라서 하나의 요청만 발급에 성공하고 나머지 요청은 중복 키 위반으로 실패하여, 최종 발급 내역은 1건으로 유지됩니다.

### 4.3. 쿠폰 중복 사용

- 조건: 동일한 발급 쿠폰으로 동시에 여러 결제 요청
- 재현하려는 문제: 쿠폰 상태가 ISSUED인 것을 여러 요청이 동시에 읽고 각각 결제에 사용할 수 있다.
- naive 구현의 한계: 쿠폰 사용 가능 여부 확인과 상태 변경이 분리되면 중복 사용이 발생할 수 있다.
- 해결 전략: 결제 트랜잭션 안에서 발급 쿠폰 row를 잠그거나, status = ISSUED 조건부 업데이트로 한 요청만 USED로 변경한다.
- 검증 기준: 하나의 발급 쿠폰은 하나의 주문에만 사용되어야 한다.

### 4.4. 포인트 차감 lost update

- 조건: 사용자의 포인트 잔액보다 큰 금액이 동시에 여러 요청에서 차감
- 재현하려는 문제: 여러 결제 요청이 같은 포인트 잔액을 동시에 읽고 차감하면서 잔액이 음수가 되거나 정합성이 깨질 수 있다.
- naive 구현의 한계: 단순 @Transactional만으로는 lost update를 막지 못할 수 있다.
- 해결 전략: 먼저 포인트 row에 비관적 락을 적용해 차감 로직을 직렬화하고, 이후 낙관적 락을 비교 실험한다.
- 검증 기준: 포인트 잔액은 0 미만이 될 수 없고, 성공한 결제만 포인트 차감에 반영되어야 한다.

#### 관측 결과: transaction-only read-modify-write baseline

- 초기 잔액: 10,000
- 동시 요청 수: 15
- 요청당 차감 금액: 1,000

정합성이 보장된다면 다음 결과가 나와야 합니다.

- successCount = 10
- failCount = 5
- finalBalance = 0

현재 `@Transactional` 기반 read-modify-write 구현에서는 다음 결과가 관측되었습니다.

- successCount = 15
- failCount = 0
- finalBalance = 8000
- expectedBalanceBySuccessCount = -5000

15개 요청이 모두 성공했다면 논리적으로 잔액은 -5,000이어야 하지만, 실제 DB 잔액은 8,000입니다.
이는 여러 트랜잭션이 같은 잔액을 읽은 뒤 서로의 변경을 덮어쓴 lost update 문제를 보여줍니다.

주의: 낙관적 락 실험을 위해 `Point` 엔티티에 `@Version`을 추가하면 같은 엔티티를 사용하는 transaction-only 경로도 version check의 영향을 받습니다.
따라서 위 transaction-only lost update 결과는 `@Version` 적용 전 baseline 관측값으로 보존합니다.

#### 비관적 락 적용 결과

`PointService.deductWithPessimisticLock()`는 포인트 row를 `PESSIMISTIC_WRITE`로 조회한 뒤 차감합니다.

- successCount = 10
- failCount = 5
- finalBalance = 0

비관적 락을 적용하면 같은 사용자의 Point row에 대한 차감 요청이 직렬화됩니다.
따라서 10개 요청만 성공하고, 잔액이 0이 된 이후의 5개 요청은 잔액 부족으로 실패합니다.

#### 낙관적 락 적용 결과

`Point` 엔티티에 `@Version`을 추가하고, `PointService.deductWithOptimisticLock()`에서 일반 조회 후 차감합니다.
JPA는 update 시점에 version mismatch를 감지해 `ObjectOptimisticLockingFailureException` 계열 예외를 발생시킵니다.

관측 예시:

- successCount = 3
- failCount = 12
- finalBalance = 7000
- expectedBalanceBySuccessCount = 7000

낙관적 락은 retry 없이 충돌을 감지하는 것까지 검증합니다.
성공 요청 수는 스레드 스케줄링에 따라 달라질 수 있으므로, 테스트는 실패 수와 최종 잔액이 성공 수와 일치하는지를 검증합니다.

#### 조건부 UPDATE 적용 결과

`PointService.deductWithAtomicUpdate()`는 포인트를 조회한 뒤 수정하지 않고, DB에서 조건 확인과 차감을 하나의 UPDATE 쿼리로 처리합니다.

```sql
UPDATE points
SET balance = balance - :amount,
    version = version + 1
WHERE user_id = :userId
AND balance >= :amount
```

- successCount = 10
- failCount = 5
- finalBalance = 0
- expectedBalanceBySuccessCount = 0

조건부 UPDATE는 `balance >= amount` 조건을 만족하는 경우에만 row를 갱신합니다.
따라서 10개 요청만 성공하고, 잔액이 부족해진 이후의 5개 요청은 update row count가 0이 되어 실패합니다.

## 5. 정합성 보장 전략

이 프로젝트에서는 단순히 `@Transactional`을 적용하는 것만으로 동시성 문제가 해결된다고 보지 않습니다.

각 실험에서 단순 구현으로 문제를 먼저 재현한 뒤, DB Unique 제약조건, DB 트랜잭션, 락 전략, Redis 원자 연산을 적용하여 결과를 비교합니다.

- Redis: 대량의 선착순 요청을 빠르게 제한하는 앞단 제어 역할 (계획)
- DB Transaction: 결제와 쿠폰 사용, 포인트 차감을 하나의 작업 단위로 보장
- DB Unique Constraint: 중복 발급과 같은 정합성 조건을 DB 레벨에서 보장
- Pessimistic Lock: 충돌 가능성이 높은 포인트 차감/쿠폰 발급/쿠폰 사용 상황에서 동시 수정을 직렬화
- Optimistic Lock: 충돌이 적은 상황을 가정하고 version 기반으로 충돌을 감지
- Atomic Update: 단순한 재고 증가나 잔액 차감 조건을 DB UPDATE 한 번으로 확인하고 갱신

### 실험별 적용 전략

| 실험 | 주요 문제 | 적용 전략 |
| --- | --- | --- |
| 쿠폰 초과 발급 | 재고보다 많은 쿠폰 발급 | transaction-only historical baseline 재현 완료, pessimistic lock/optimistic lock/atomic update 적용 완료 |
| 쿠폰 중복 발급 | 동일 사용자 중복 발급 | transaction-only baseline 재현 완료, UNIQUE(user_id, coupon_id) 적용 완료 |
| 쿠폰 중복 사용 | 동일 발급 쿠폰의 다중 결제 사용 | row lock 또는 conditional update (계획) |
| 포인트 lost update | 동시 차감으로 인한 lost update | pessimistic lock, optimistic lock, atomic update 적용 완료 |

## 6. Entity 설계

### 6.1 구현됨

### User
- id
- email (UNIQUE)
- password
- username
- role
- createdAt
- updatedAt

### Point
- id
- userId (UNIQUE)
- balance
- version (`@Version`)
- createdAt
- updatedAt

현재 Point에는 낙관적 락 충돌 감지를 위한 `@Version` 필드가 있습니다.
잔액이 0 이상이어야 한다는 조건은 현재 DB check constraint가 아니라 `Point.deduct()`의 애플리케이션 레벨 검증으로 처리합니다.

### Coupon
- id
- name
- discountAmount
- totalQuantity
- issuedQuantity
- version (`@Version`)
- createdAt
- updatedAt

현재 Coupon에는 낙관적 락 충돌 감지를 위한 `@Version` 필드가 있습니다.
이 필드 추가 이후 transaction-only overselling baseline은 기존과 같은 방식으로 활성 재현하지 않고, `@Version` 적용 전 historical baseline으로 보존합니다.

### IssuedCoupon
- id
- userId
- couponId
- status (ISSUED / USED / EXPIRED)
- issuedAt
- usedAt
- 제약 조건: UNIQUE(userId, couponId)

### 6.2 계획됨

### Product
- id
- name
- price
- createdAt
- updatedAt

### Coupon 확장 필드
- status
- issueStartAt
- issueEndAt
- expiredAt

### Order
- id
- userId
- productId
- issuedCouponId
- originalPrice
- discountAmount
- finalPrice
- status (CREATED / PAID / FAILED / CANCELED)
- createdAt
- updatedAt

### 주요 DB 제약 조건

- User.email: UNIQUE
- Point.userId: UNIQUE
- IssuedCoupon(userId, couponId): UNIQUE
- Point.balance: 0 이상이어야 함 (현재 애플리케이션 레벨 검증, DB check constraint 아님)
- Coupon.totalQuantity: 0 이상이어야 함 (DB check constraint는 계획)
- Coupon.issuedQuantity: 0 이상이어야 함 (현재 `Coupon.issue()`에서 재고 초과 검증, DB check constraint는 계획)
- IssuedCoupon.status: ISSUED, USED, EXPIRED
- Order.status: CREATED, PAID, FAILED, CANCELED (계획)
- Order.finalPrice: 0 이상 (계획)

주의: `spring.jpa.hibernate.ddl-auto=update` 환경에서는 이미 생성된 테이블에 새 UNIQUE 제약 조건이 자동 반영되지 않을 수 있습니다.
따라서 `IssuedCoupon(userId, couponId)` UNIQUE 제약 조건 적용 여부는 실제 DB에서 `psql`의 `\d issued_coupons` 명령으로 확인했습니다.

## 7. 검증 방법

동시성 문제는 단순 API 호출만으로 확인하기 어렵기 때문에, 각 실험은 transaction-only read-modify-write baseline과 개선 구현을 분리하여 테스트합니다.

- JUnit과 ExecutorService를 사용해 동시에 여러 요청을 발생시킨다.
- transaction-only baseline에서 먼저 race condition을 재현한다.
- Redis, DB 제약 조건, 트랜잭션, 락, 조건부 UPDATE를 적용한 구현에서 동일 조건으로 다시 검증한다.
- 테스트 종료 후 DB 상태를 조회해 최종 정합성을 확인한다.

### 주요 검증 지표

- 성공 요청 수
- 실패 요청 수
- 최종 쿠폰 발급 수량
- 사용자별 중복 발급 여부
- 발급 쿠폰의 중복 사용 여부
- 최종 포인트 잔액
- 생성된 주문 수

### 예상 테스트 예시

| 실험 | 조건 | 검증 기준 |
| --- | --- | --- |
| 쿠폰 초과 발급 | 쿠폰 100개, 동시 요청 1,000개 | 발급 수량 = 100 |
| 쿠폰 중복 발급 | 동일 사용자, 동일 쿠폰 동시 요청 | 발급 수량 = 1 |
| 쿠폰 중복 사용 | 동일 발급 쿠폰으로 동시 결제 | 성공 주문 = 1 |
| 포인트 음수 잔액 | 잔액보다 큰 동시 차감 요청 | 잔액 >= 0 |

## 8. 향후 개선

1. 쿠폰 발급 전략 비교
   - 완료된 DB pessimistic lock, optimistic lock, atomic update 결과를 기준으로 Redis atomic counter 방식의 결과와 성능을 비교한다.
   - 다음 단계는 Redis Counter를 DB 저장 전 front-line stock gate로 적용하는 것이다.
   - Redis Counter 실험은 쿠폰 재고 100개, 동시 요청 1,000개, 서로 다른 사용자 조건에서 successCount 100, failCount 900, issuedCouponCountByCoupon 100, finalIssuedQuantity 100을 목표로 한다.
   - Redis Counter는 재고 gate 역할만 담당하며, 동일 사용자 중복 발급 방지는 계속 `UNIQUE(user_id, coupon_id)` 제약 조건이 담당한다.
   - Redis에서 발급 가능으로 처리된 뒤 DB 저장이 실패하는 경우의 불일치 위험은 구현에서 보상하거나 관측 결과 문서에 명시한다.

2. 낙관적 락 재시도 전략 추가
   - 현재는 충돌 감지만 검증하며, 이후 retry를 적용했을 때 최종 성공/실패 결과가 어떻게 달라지는지 비교한다.

3. Redis와 DB 불일치 보정
   - Redis에서는 발급 가능으로 처리되었지만 DB 저장이 실패하는 경우를 가정하고, 보상 처리 또는 재시도 전략을 설계한다.
