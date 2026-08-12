# 배송요청 생성(createDelivery) 커넥션 풀 교착 수정 작업 기록

- 이슈: [#463](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/463)
- 브랜치: `feature/463-create-pool-deadlock`
- 범위: backend
- 작성일: 2026-08-11

## 무엇을 만들었나

`POST /api/customer/deliveries` 경로의 **커넥션 풀 교착**을 고쳤다. `DeliveryService.createDelivery`(`@Transactional`)가 커넥션 C1을 쥔 채 첫 줄에서 `DeliveryTimeoutService.expireIfStale`(`@Transactional(REQUIRES_NEW)`)를 불러, 생성 1건이 커넥션 2개를 동시 점유하던 것이 원인이었다(#446 accept 경로의 형제 버그). `expireIfStale` 호출을 생성 트랜잭션 **밖**(컨트롤러 `CustomerDeliveryController.createDelivery`)으로 옮겨, 만료 정리와 주문 생성이 커넥션을 순차로만 쓰게 했다. `REQUIRES_NEW`의 독립 커밋 의미와 "새 주문 생성이 만료 주문을 자동 정리한다(#42)"는 동작은 그대로 유지된다.

기능 추가가 아니라 성능/자원 결함 수정이다. 정합성 문제는 아니다(주문 생성은 각자 다른 고객·행) — 순수 풀 고갈 효율 문제다.

### API

계약 변경 없음. 엔드포인트·요청·응답·상태코드 모두 그대로다(동작만 교착 제거). 스키마 변경 없음.

### 화면

해당 없음.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. 커넥션 2개 동시 요구를 없애는 방식

- **물었던 것**: `expireIfStale`를 `createDelivery`의 `@Transactional` 경계 밖으로 빼는 방식 — 이슈 본문은 create에는 컨트롤러 분리보다 서비스 내 TransactionTemplate(#446 검토의 후보 D)나 비-트랜잭션 진입→트랜잭션 코어 분리가 더 자연스러울 수 있다고 봤다.
- **선택지**:
  - (A) TransactionTemplate — 서비스 안에서 생성 코어만 프로그램적 트랜잭션으로 감쌈. 트리거가 서비스에 남아 기존 지연 만료 통합 테스트가 그대로 통과, 새 클래스 없음 / 이 메서드만 선언적↔프로그램적 트랜잭션 혼용.
  - (B) **컨트롤러 오케스트레이션** — 컨트롤러가 `expireIfStale`를 먼저 부르고 `createDelivery` 호출. #446 accept와 **동일 패턴**이라 두 경로의 만료-정리 배선이 일관됨, diff 최소 / 만료 트리거가 서비스 불변식이 아니라 HTTP 배선에 존재, 서비스를 직접 부르는 지연 만료 테스트를 E2E로 이전해야 함.
  - (C) 신규 빈 추출 — 생성 코어를 별도 `@Service`로. 순서·계층 유지 / 작은 메서드 하나로 클래스 +1(성급한 추상화 지양 문화와 마찰).
  - (탈락) 단순 self-injection — 자기 자신 주입은 순환 참조라 Boot 3.4 기동 실패(CLAUDE.md 「확정된 결정」).
- **고른 것**: (B) 컨트롤러 오케스트레이션.
- **근거**: 사람이 "컨트롤러 오케스트레이션"으로 명시 선택. #446 accept와 같은 패턴이라 두 경로의 만료-정리 배선이 일관되는 점을 취했다(이슈가 제안한 A/D 대신 형제 버그와의 일관성을 우선).
- **영향**: 만료 정리 트리거가 두 경로(accept, create) 모두 **HTTP 컨트롤러 배선**에 존재하게 된다. `createDelivery`를 비-HTTP로 직접 호출하면 만료 정리가 걸리지 않는다(현재 호출자는 컨트롤러뿐). 그래서 "새 주문 생성이 만료 주문을 정리한다"의 회귀 방어를 서비스 통합 테스트에서 E2E로 옮겼다(아래 「스스로 판단한 것」). 순서상 만료 정리가 `createDelivery` 진입 전에 일어나지만, 원래도 `createDelivery` 첫 줄이 `expireIfStale`였으므로(멱등 조회보다 앞) 상대 순서는 동일 — 동작 변화 없음.

### 2. create 경로 부하 스크립트 실측 여부

- **물었던 것**: 완료조건의 "가능하면 create 경로 부하 스크립트로 실측"을 이번에 할지.
- **선택지**:
  - (A) **생략** — 구조적으로 #446과 동일한 버그이고 커넥션 홀딩 제거는 코드 리뷰로 확인 가능, 단위·통합·E2E로 동작 회귀만 방어.
  - (B) 로컬 실측만(커밋 제외) — create 전용 부하 스크립트로 before/after 대조. 시간이 더 걸림.
- **고른 것**: (A) 생략.
- **근거**: 사람이 "생략"으로 명시 선택. #446 부하 하니스는 저장소에 커밋하지 않는 로컬 벤치라, create 전용 스크립트를 새로 만들어도 커밋되지 않고 재현 근거가 저장소에 남지 않는다. 완료조건상 실측은 "if possible" 항목.
- **영향**: 이 저장소에는 create 경로 교착의 정량 실측(before/after 수치)이 남지 않는다. accept(#446) 실측이 같은 메커니즘을 이미 증명했고, 이번 수정은 그 메커니즘의 원인(2커넥션 홀딩)을 create에서 동일하게 제거한다.

## 스스로 판단한 것

- **지연 만료 검증을 서비스 통합 → E2E로 이전**: 선택 (B) 때문에 만료 정리가 컨트롤러에서 일어나므로, `DeliveryTimeoutServiceIntegrationTest.expiresStaleOrderLazilyOnNewOrderCreation`이 `deliveryService.createDelivery`를 직접 불러 검증하던 "만료 주문 자동 정리 + 새 주문 생성"은 더 이상 서비스만으로 성립하지 않는다(서비스만 부르면 만료 주문이 그대로 남아 `uk_delivery_active_customer` 위반 → 409). 그 시나리오를 `CustomerDeliveryCreateE2ETest`로 옮겨 실제 HTTP → 컨트롤러 → 만료 정리 배선을 검증한다(#446이 accept에서 한 것과 동일한 이전). E2E에는 `requested_at` backdate용 `JdbcTemplate`과 상태 확인용 `DeliveryOrderRepository`를 새로 주입했다.
- **`DeliveryService`에서 `DeliveryTimeoutService` 필드·호출 완전 제거**: #446(accept)은 javadoc `{@link}` 참조 때문에 임포트를 남겼지만, `createDelivery` javadoc에는 `{@link DeliveryTimeoutService}` 참조가 없다. 두 서비스가 같은 패키지(`order.service`)라 임포트도 없어, 필드·호출만 지우면 잔재가 남지 않는다.
- **단위 테스트의 `DeliveryTimeoutService` mock 제거**: 서비스가 더는 의존하지 않으므로 죽은 mock을 지웠다(스텁/검증이 없어 안전).
- **javadoc에 "커넥션 1개" 불변식 명시**: `createDelivery`가 왜 `expireIfStale`를 스스로 부르지 않는지를 클래스 javadoc과 컨트롤러 javadoc 양쪽에 남겼다 — 나중에 "만료 정리를 여기서 부르면 되잖아" 하고 되돌리는 것을 막기 위해서다.

## 일부러 하지 않은 것

- **TransactionTemplate/신규 빈 방식(이슈가 제안한 A/D)**: 이유 — 사람이 #446과의 배선 일관성을 우선해 (B)를 골랐다. 필요 시 만료 트리거를 서비스로 되살리려면 (A)/(C)로 재구성해야 한다.
- **create 경로 부하 실측**: 이유 — 사람이 생략 선택(위 「사람이 고른 선택 2」).
- **`#42`의 기존 미결(다중 인스턴스 스캐너 중복/분산락, 자동취소 스캐너 1분 창)**: 이번 수정과 무관해 건드리지 않음.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `DeliveryServiceTest` | createDelivery 로직(정상 생성·요금 변경 409·멱등 재전송). 죽은 `DeliveryTimeoutService` mock 제거 후에도 그대로 통과 |
| 통합 | `DeliveryTimeoutServiceIntegrationTest` | 자동 취소·환급, 스캐너 조회, 배차 확정 vs 자동취소 경쟁. 지연 만료-생성 테스트는 E2E로 이전(주석으로 이전 사유 명시) |
| 통합 | `DeliveryCreateIntegrationTest` | createDelivery의 DB 제약·트랜잭션 경계(멱등·진행중 1건·이중차감 방지). 만료 정리에 의존하지 않아 변경 없이 통과 |
| E2E | `CustomerDeliveryCreateE2ETest` | (신규 케이스) 만료된 기존 주문이 있을 때 새 요청을 HTTP로 보내면 컨트롤러 배선이 그 주문을 취소·전액 환급 후 새 주문 생성. 기존 401/견적공개/201/402/409/멱등 흐름 |

실행 결과:

```text
./gradlew test --tests DeliveryServiceTest                       → BUILD SUCCESSFUL
./gradlew test --tests DeliveryTimeoutServiceIntegrationTest \
  --tests DeliveryCreateIntegrationTest \
  --tests CustomerDeliveryCreateE2ETest                          → BUILD SUCCESSFUL (failures=0)
```

### 검증하지 못한 것

- create 경로의 교착 정량 실측(before/after). 사람이 부하 스크립트 실측을 생략 선택 — 구조적 동일성과 코드 리뷰로 커넥션 홀딩 제거를 확인했다.
- 다중 인스턴스에서의 재현/수정 확인(단일 인스턴스 전제).

## 새로 생긴 미결 사항

- 없음. #463은 #446이 남긴 미결(create 경로 동일 교착)을 해소하는 이슈였고, 이번 수정으로 그 항목이 닫힌다. 만료-정리 트리거가 두 경로 모두 컨트롤러 배선에 존재한다는 점은 위 「사람이 고른 선택 1」의 영향으로 기록해 둔다(비-HTTP 호출 시 만료 정리 미적용 — 현재 호출자는 컨트롤러뿐이라 무해).
