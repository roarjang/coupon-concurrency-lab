# Flash Coupon Payment

선착순 쿠폰 발급 및 포인트 결제 정합성 실험 프로젝트

## 프로젝트 요약

선착순 쿠폰 발급과 포인트 결제 시나리오에서 발생하는 동시성 문제를 재현하고, 다양한 정합성 보장 전략의 동작 방식과 trade-off를 비교한 백엔드 실험 프로젝트입니다.

이 프로젝트의 핵심은 CRUD 기능 구현이 아니라, `@Transactional`만으로는 해결되지 않는 race condition을 실험으로 확인하고 DB Lock, Optimistic Lock, Atomic Update, Redis Counter, Redis Lua Script의 정합성 보장 범위와 trade-off를 비교하는 것입니다.

## 왜 이 프로젝트를 만들었는가

실제 서비스에서는 짧은 시간에 많은 요청이 같은 데이터에 접근합니다.
단순한 read-modify-write 구현은 이 상황에서 다음 문제를 만들 수 있습니다.

- 여러 결제 요청이 같은 포인트 잔액을 동시에 읽고 차감하는 lost update
- 쿠폰 재고보다 많은 발급 내역이 생성되는 overselling
- 같은 사용자가 같은 쿠폰을 중복 발급받는 duplicate issuance
- Redis에서 요청을 먼저 통과시킨 뒤 PostgreSQL 저장이 실패하는 cross-store consistency 문제

각 문제는 먼저 실패 케이스를 재현한 뒤, 전략을 하나씩 적용하고 최종 DB 상태를 검증하는 방식으로 실험했습니다.

## 핵심 문제

| 문제 | 설명 | 검증 기준 |
| --- | --- | --- |
| Point Lost Update | 동시에 포인트를 차감할 때 성공 처리와 최종 잔액이 불일치하는 문제 | 성공 요청 수와 최종 잔액이 일치해야 함 |
| Coupon Overselling | 쿠폰 재고보다 많은 발급 내역이 생성되는 문제 | 발급 내역과 쿠폰 발급 수량이 재고를 초과하지 않아야 함 |
| Coupon Duplicate Issuance | 동일 사용자가 같은 쿠폰을 여러 번 발급받는 문제 | `(userId, couponId)` 조합은 1건만 존재해야 함 |
| Redis/PostgreSQL Boundary | Redis 통과 후 DB 저장 실패로 상태가 어긋날 수 있는 문제 | Redis는 gate, PostgreSQL은 source of truth로 유지해야 함 |

## 기술 스택

- Java
- Spring Boot
- Spring Data JPA
- PostgreSQL
- Redis
- Gradle
- Docker Compose
- JWT Authentication
- JUnit concurrency tests

## 현재 구현 상태

| 영역 | 상태 |
| --- | --- |
| User/Auth | 회원가입, 로그인, JWT 인증 구현 완료 |
| Point Domain | transaction-only baseline, pessimistic lock, optimistic lock, atomic update 실험 완료 |
| Coupon Domain | overselling, duplicate issuance 재현 및 전략 비교 완료 |
| Redis Counter | 쿠폰 재고 front-line gate 실험 완료 |
| Redis Lua Script | 쿠폰 재고와 중복 발급을 Redis 내부에서 원자적으로 확인하는 실험 완료 |
| Product/Order/Payment | 상품, 주문, 쿠폰 사용 결제 흐름은 계획 단계 |

## 결과 요약

| 영역 | Baseline Failure | 적용 전략 | 관측 결과 |
| --- | --- | --- | --- |
| Point Lost Update | 잔액 10,000, 15개 동시 차감 요청에서 `successCount = 15`, `finalBalance = 8000` 관측 | Pessimistic Lock, Optimistic Lock, Atomic Update | Pessimistic Lock과 Atomic Update는 `successCount = 10`, `failCount = 5`, `finalBalance = 0`으로 정합성 유지. Optimistic Lock은 충돌 감지와 최종 잔액 일치 검증 |
| Coupon Overselling | 재고 100개, 1,000개 동시 요청에서 `issuedCouponCountByCoupon = 1000`, `finalIssuedQuantity = 100` 관측 | Pessimistic Lock, Optimistic Lock, Atomic Update, Redis Counter, Redis Lua Script | 완료된 전략에서 발급 내역과 쿠폰 발급 수량이 재고를 초과하지 않음 |
| Coupon Duplicate Issuance | 동일 사용자의 100개 동시 요청에서 `issuedCouponCountByUserAndCoupon = 10` 관측 | DB Unique Constraint, Redis Lua Script | DB UNIQUE 적용 후 1건만 저장. Redis Lua는 중복 요청을 DB write path 이전에 차단 |
| Redis/PostgreSQL Boundary | Redis 통과 후 DB 저장 실패 시 Redis와 DB 상태가 어긋날 수 있음 | Redis Counter compensation, Redis Lua compensation | Redis Counter는 counter 감소, Redis Lua는 counter 감소와 issued-user set 제거 보상 적용. PostgreSQL을 최종 source of truth로 유지 |

Optimistic Lock은 stale update를 감지하고 거부하지만, retry 없이 항상 재고를 정확히 모두 소진시키는 전략은 아닙니다.
쿠폰 재고를 반드시 끝까지 발급해야 한다면 retry 정책, Atomic Update, Pessimistic Lock, Redis 기반 gate 중 요구사항에 맞는 전략을 선택해야 합니다.

## 전략 요약

| 전략 | 핵심 역할 | 적합한 상황 |
| --- | --- | --- |
| Transaction-only Baseline | 문제 재현용 기준점 | `@Transactional`만으로 동시성 제어가 충분하지 않은 경우를 재현할 때 |
| DB Unique Constraint | 동일 사용자-쿠폰 중복 발급 최종 방어선 | DB 레벨에서 보장해야 하는 사용자-쿠폰 유일성 제약 |
| Pessimistic Lock | 같은 row에 대한 동시 수정을 직렬화 | 충돌이 많고 정확성이 처리량보다 중요한 경우 |
| Optimistic Lock | version mismatch로 stale update 감지 | 충돌이 적고 retry 또는 실패 응답이 허용되는 경우 |
| Atomic Update | 조건 확인과 갱신을 DB UPDATE 한 번으로 처리 | 포인트 차감, 쿠폰 재고 증가처럼 조건이 단순한 경우 |
| Redis Counter | 대량 요청을 DB write path 전에 제한 | 선착순 이벤트에서 재고 slot gate가 필요한 경우 |
| Redis Lua Script | Redis 안에서 재고와 중복 발급을 함께 원자적으로 확인 | 트래픽이 크고 중복 요청도 DB 이전에 차단해야 하는 경우 |

## 문서 가이드

### 주요 문서

| 문서 | 내용 |
| --- | --- |
| [Architecture](docs/architecture.md) | 현재 구현된 Point, Coupon, PostgreSQL, Redis, 테스트 구조 |
| [Concurrency Experiment Runbook](docs/runbook.md) | 로컬 환경에서 동시성 실험을 재현하고 검증하는 절차 |
| [Point Concurrency Strategy Comparison](docs/point-concurrency-strategy-comparison.md) | 포인트 차감 lost update 재현과 pessimistic lock, optimistic lock, atomic update 비교 |
| [Coupon Concurrency Strategy Comparison](docs/coupon-concurrency-strategy-comparison.md) | 쿠폰 overselling, duplicate issuance, DB/Redis 전략 비교와 trade-off 정리 |
| [Coupon Domain Design](docs/coupon-domain-design.md) | Coupon, IssuedCoupon 모델링과 정합성 제약 조건 |
| [Redis Consistency Boundary](docs/redis-consistency-boundary.md) | Redis front-line gate와 PostgreSQL source of truth 사이의 정합성 경계 |

### 참고 문서 (히스토리)

| 문서 | 내용 |
| --- | --- |
| [Implementation Roadmap](docs/history/implementation-roadmap.md) | 구현 단계와 phase별 의사결정 기록 |
| [Project Context](docs/history/project-context.md) | 초기 프로젝트 맥락과 working-state 기록 |
| [Point Experiment Plan](docs/history/concurrency-experiment-plan.md) | Point 동시성 실험의 초기 계획과 기준 시나리오 |
| [Coupon Experiment Plan](docs/history/coupon-concurrency-experiment-plan.md) | Coupon 동시성 실험의 초기 계획과 구현 진행 기록 |

## 다음 작업

1. 상품/주문/결제 흐름으로 도메인 확장
2. 발급 쿠폰 사용 시 중복 사용 방지 실험
3. Redis와 PostgreSQL 간 불일치 보상 및 재조정 전략 정리

## 정합성 설계 고려사항

이 프로젝트에서는 동시성 제어와 데이터 정합성 관점에서 다음과 같은 설계 고려사항을 다루었습니다.

- @Transactional만으로 Lost Update와 Overselling이 발생하는 이유
- DB Lock, Optimistic Lock, Atomic Update의 정합성 보장 범위와 trade-off
- DB Unique Constraint를 활용한 중복 발급 방지
- Redis Counter와 Redis Lua Script의 역할 및 적용 범위
- PostgreSQL을 source of truth로 유지해야 하는 이유
- Optimistic Lock without retry의 정합성 보장 범위와 한계
