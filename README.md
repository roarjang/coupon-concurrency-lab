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

## 3. MVP 기능 범위

### 3.1 실험 핵심 기능
1. 선착순 쿠폰 발급
2. 쿠폰 중복 발급 방지
3. 쿠폰 적용 결제
4. 포인트 차감 정합성 보장
5. 동시성 테스트

### 3.2 실험 보조 기능
1. 회원가입 / 로그인
2. JWT 기반 인증
3. 포인트 충전 / 조회
4. 상품 조회
5. 테스트용 쿠폰 데이터 세팅
6. 내 쿠폰 조회

## 4. 동시성 실험 설계

### 4.1. 쿠폰 초과 발급

- 조건: 쿠폰 수량 100개, 동시 요청 1,000개
- 재현하려는 문제: 여러 요청이 동시에 쿠폰 재고를 읽고 갱신하면서 실제 재고보다 많은 쿠폰이 발급될 수 있다.
- naive 구현의 한계: 단순히 현재 발급 수량을 조회한 뒤 증가시키는 방식은 race condition으로 인해 초과 발급이 발생할 수 있다.
- 해결 전략: Redis 원자 연산으로 선착순 요청 수를 제한하고, DB에는 최종 발급 내역을 저장한다.
- 검증 기준: 최종 발급 수량은 정확히 100개여야 한다.

### 4.2. 쿠폰 중복 발급

- 조건: 동일 사용자가 같은 쿠폰에 대해 동시에 여러 번 발급 요청
- 재현하려는 문제: 중복 발급 여부를 확인하는 로직과 발급 내역을 저장하는 로직 사이에서 race condition이 발생할 수 있다.
- naive 구현의 한계: 애플리케이션에서만 중복 여부를 검사하면 동시에 들어온 요청을 모두 통과시킬 수 있다.
- 해결 전략: DB의 UNIQUE(user_id, coupon_id) 제약 조건과 트랜잭션을 함께 사용한다.
- 검증 기준: 한 사용자는 동일 쿠폰을 1개만 발급받을 수 있어야 한다.

### 4.3. 쿠폰 중복 사용

- 조건: 동일한 발급 쿠폰으로 동시에 여러 결제 요청
- 재현하려는 문제: 쿠폰 상태가 ISSUED인 것을 여러 요청이 동시에 읽고 각각 결제에 사용할 수 있다.
- naive 구현의 한계: 쿠폰 사용 가능 여부 확인과 상태 변경이 분리되면 중복 사용이 발생할 수 있다.
- 해결 전략: 결제 트랜잭션 안에서 발급 쿠폰 row를 잠그거나, status = ISSUED 조건부 업데이트로 한 요청만 USED로 변경한다.
- 검증 기준: 하나의 발급 쿠폰은 하나의 주문에만 사용되어야 한다.

### 4.4. 포인트 음수 잔액

- 조건: 사용자의 포인트 잔액보다 큰 금액이 동시에 여러 요청에서 차감
- 재현하려는 문제: 여러 결제 요청이 같은 포인트 잔액을 동시에 읽고 차감하면서 잔액이 음수가 되거나 정합성이 깨질 수 있다.
- naive 구현의 한계: 단순 @Transactional만으로는 lost update를 막지 못할 수 있다.
- 해결 전략: 먼저 포인트 row에 비관적 락을 적용해 차감 로직을 직렬화하고, 이후 낙관적 락을 비교 실험한다.
- 검증 기준: 포인트 잔액은 0 미만이 될 수 없고, 성공한 결제만 포인트 차감에 반영되어야 한다.

## 5. 정합성 보장 전략

이 프로젝트에서는 단순히 `@Transactional`을 적용하는 것만으로 동시성 문제가 해결된다고 보지 않습니다.

각 실험에서 naive 구현으로 문제를 먼저 재현한 뒤, Redis 원자 연산, DB Unique 제약조건, DB 트랜잭션, 락 전략을 적용하여 결과를 비교합니다.

- Redis: 대량의 선착순 요청을 빠르게 제한하는 앞단 제어 역할
- DB Transaction: 결제와 쿠폰 사용, 포인트 차감을 하나의 작업 단위로 보장
- DB Unique Constraint: 중복 발급과 같은 정합성 조건을 DB 레벨에서 보장
- Pessimistic Lock: 충돌 가능성이 높은 포인트 차감/쿠폰 사용 상황에서 동시 수정을 직렬화
- Optimistic Lock: 충돌이 적은 상황을 가정하고 version 기반으로 충돌을 감지

### 실험별 적용 전략

| 실험 | 주요 문제 | 적용 전략 |
| --- | --- | --- |
| 쿠폰 초과 발급 | 재고보다 많은 쿠폰 발급 | Redis atomic counter |
| 쿠폰 중복 발급 | 동일 사용자 중복 발급 | UNIQUE(user_id, coupon_id) |
| 쿠폰 중복 사용 | 동일 발급 쿠폰의 다중 결제 사용 | row lock 또는 conditional update |
| 포인트 음수 잔액 | 동시 차감으로 인한 lost update | pessimistic lock, optimistic lock 비교 |

## 6. Entity 설계
### User
- id
- email (UNIQUE)
- password
- userName
- role
- createdAt
- updatedAt

### Point
- id
- userId (UNIQUE)
- balance
- version (나중에 낙관적 락 실험할 때 사용)
- createdAt
- updatedAt

### Product
- id
- name
- price
- createdAt
- updatedAt

### Coupon
- id
- name
- discountAmount
- totalQuantity
- issuedQuantity
- version
- status
- issueStartAt
- issueEndAt
- createdAt
- expiredAt

### IssuedCoupon
- id
- userId
- couponId
- status (ISSUED / USED / EXPIRED)
- issuedAt
- usedAt
- 제약 조건: UNIQUE(userId, couponId)

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
- Point.balance: 0 이상
- Coupon.totalQuantity: 0 이상
- Coupon.issuedQuantity: 0 이상
- IssuedCoupon.status: ISSUED, USED, EXPIRED
- Order.status: CREATED, PAID, FAILED, CANCELED
- Order.finalPrice: 0 이상

## 7. 검증 방법

동시성 문제는 단순 API 호출만으로 확인하기 어렵기 때문에, 각 실험은 naive 구현과 개선 구현을 분리하여 테스트합니다.

- JUnit과 ExecutorService를 사용해 동시에 여러 요청을 발생시킨다.
- naive 구현에서 먼저 race condition을 재현한다.
- Redis, DB 제약 조건, 트랜잭션, 락을 적용한 구현에서 동일 조건으로 다시 검증한다.
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
   - Redis atomic counter 방식과 DB lock 방식의 결과와 성능을 비교한다.

2. 낙관적 락 실험 추가
   - 포인트 차감과 쿠폰 발급 수량 갱신에 version 기반 낙관적 락을 적용하고, 비관적 락과 비교한다.

3. Redis와 DB 불일치 보정
   - Redis에서는 발급 가능으로 처리되었지만 DB 저장이 실패하는 경우를 가정하고, 보상 처리 또는 재시도 전략을 설계한다.