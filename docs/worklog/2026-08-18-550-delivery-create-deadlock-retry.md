# 배송요청 생성 데드락 재시도 작업 기록

- 논의: [#550](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/550)
- 브랜치: `fix/550-delivery-create-deadlock-retry`
- 범위: backend
- 작성일: 2026-08-18

## 무엇을 만들었나

배송요청 INSERT 중 `uk_delivery_active_customer`에서 MySQL이 감지한 데드락의 희생자만
짧게 재시도한다. 기존 멱등 파사드가 트랜잭션 밖에 있다는 경계를 그대로 이용해, 실패한 트랜잭션이
완전히 롤백된 뒤 주문 생성 전체를 새 트랜잭션으로 다시 실행한다.

### API

변경 없음.

### 화면

해당 없음.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. 데드락 복구 위치

- **물었던 것**: DB 유니크 인덱스 INSERT 데드락을 어떤 방식으로 복구할지.
- **선택지**:
  - 애플리케이션 재시도 — 기존 requestKey 멱등성과 트랜잭션 외부 파사드를 재사용할 수 있다.
  - READ COMMITTED — 일반 gap lock은 줄지만 duplicate-key 검사 gap lock은 남아 제거를 보장하지 못한다.
  - 활성 주문 포인터로 스키마 재설계 — gap INSERT 구조를 바꿀 수 있지만 상태 동기화와 마이그레이션 범위가 크다.
- **고른 것**: Spring Framework 7의 선언적 애플리케이션 재시도.
- **근거**: 발생률이 낮은 일시적 실패이고, DB가 희생자 트랜잭션을 완전히 롤백하며 주문 생성에는 requestKey 멱등성이 이미 있다.
- **영향**: 재시도는 트랜잭션 외부 `DeliveryOrderCreator`에만 둔다.

### 2. 재시도할 오류 범위

- **물었던 것**: `CannotAcquireLockException` 전체를 재시도할지.
- **선택지**:
  - 전체 재시도 — 구현은 단순하지만 1205 lock wait timeout까지 반복해 응답시간을 크게 늘릴 수 있다.
  - 1213/40001만 재시도 — predicate가 필요하지만 즉시 감지된 MySQL 데드락만 복구한다.
- **고른 것**: MySQL error code 1213과 SQLState 40001을 모두 만족할 때만 최대 2회 재시도.
- **근거**: 50초 안팎의 락 대기 타임아웃을 반복하지 않고, #550에서 실제 관측한 오류만 좁게 복구하기 위해서다.
- **영향**: 1205/HY000은 기존처럼 즉시 상위로 전파된다.

## 스스로 판단한 것

- **Framework 내장 기능 사용**: Boot 4.1의 Spring Framework 7 resilience를 사용해 별도 `spring-retry` 의존성을 추가하지 않았다.
- **짧은 jitter backoff**: 20ms 시작, 30ms jitter, 100ms 상한으로 동시 재충돌 가능성을 낮추되 요청 지연은 제한했다.
- **원인 체인 검사**: Spring/Hibernate/JDBC 예외 래핑 깊이에 의존하지 않고 전체 cause chain에서 `SQLException`을 찾는다.

## 일부러 하지 않은 것

- **격리 수준 변경**: duplicate-key 검사에는 READ COMMITTED에서도 gap lock이 남으므로 전역·지역 격리 수준을 바꾸지 않았다.
- **스키마 재설계**: 낮은 발생률에 비해 변경 범위와 정합성 동기화 비용이 커 제외했다.
- **장시간 부하 재현**: 수십만 건에서 드물게 발생하는 실데드락 재현은 이번 로컬 검증에 포함하지 않았다. 기존 덤프로 원인은 이미 확인됐다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `MySqlDeadlockRetryPredicateTest` | 1213/40001만 허용하고 1205/HY000은 거부 |
| 통합(AOP) | `DeliveryOrderCreatorRetryTest` | 실제 retry proxy가 데드락만 재호출하고 timeout은 한 번만 호출 |
| MySQL 통합 | `DeliveryCreateIntegrationTest` | 기존 주문 생성·멱등·제약·트랜잭션 동작 유지 |

실행 결과:

```text
./gradlew test --tests '*MySqlDeadlockRetryPredicateTest' --tests '*DeliveryOrderCreatorRetryTest'
→ BUILD SUCCESSFUL

./gradlew test --tests '*DeliveryCreateIntegrationTest'
→ BUILD SUCCESSFUL

```

### 검증하지 못한 것

- 수십만 건 주문 생성·취소 부하에서 데드락 재발률과 재시도 성공률 비교.

## 새로 생긴 미결 사항

- 해당 없음.
