# 배차 수락(accept) 커넥션 풀 교착 수정 작업 기록

- 이슈: [#446](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/446)
- 브랜치: `feature/446-accept-pool-deadlock`
- 범위: backend
- 작성일: 2026-08-11

## 무엇을 만들었나

`POST /api/rider/requests/{deliveryId}/accept` 경로의 **커넥션 풀 교착**을 고쳤다. `acceptDeliveryRequest`(`@Transactional`)가 커넥션 C1을 쥔 채 첫 줄에서 `DeliveryTimeoutService.cancelIfExpired`(`@Transactional(REQUIRES_NEW)`)를 불러, accept 1건이 커넥션 2개를 동시 점유하던 것이 원인이었다. `cancelIfExpired` 호출을 accept 트랜잭션 **밖**(컨트롤러)으로 옮겨, 만료 정리와 배차 확정이 커넥션을 순차로만 쓰게 했다. `REQUIRES_NEW`의 독립 커밋 의미와 "수락 시도가 만료 정리를 트리거한다(#42)"는 동작은 그대로 유지된다.

기능 추가가 아니라 정합성/성능 결함 수정이다. 부하 리허설로 실측 발견 → 수정 → 재실측(before/after)까지 한 사이클로 진행했다.

### API

계약 변경 없음. 엔드포인트·요청·응답·상태코드 모두 그대로다(동작만 교착 제거).

### 화면

해당 없음.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. 수정을 어디에 두나 (커넥션 2개 동시 요구 제거 방식)

- **물었던 것**: `cancelIfExpired`를 accept의 `@Transactional` 경계 밖으로 빼는 3가지 방식 중 무엇으로 갈지.
- **선택지**:
  - (A) TransactionTemplate — 서비스 안에서 배차 코어만 프로그램적 트랜잭션으로 감쌈. 순서·계층 유지, 새 클래스 없음 / 이 메서드만 선언적↔프로그램적 트랜잭션 혼용.
  - (B) **컨트롤러 오케스트레이션** — 컨트롤러가 `cancelIfExpired`를 먼저 부르고 accept 호출. diff 최소 / 만료 정리가 `requireAvailable`(403)보다 먼저 실행돼 문서화된 단계 순서가 바뀜, 얇은 컨트롤러에 오케스트레이션이 들어감.
  - (C) 신규 빈 추출 — 배차 코어를 별도 `@Service`로. 순서·계층 유지 / 작은 메서드 하나로 클래스 +1(성급한 추상화 지양 문화와 마찰).
  - (탈락) 단순 self-injection — 자기 자신 주입은 순환 참조라 Boot 3.4 기동 실패(CLAUDE.md 「확정된 결정」).
- **고른 것**: (B) 컨트롤러 오케스트레이션.
- **근거**: 사람이 "브랜치 파고 2번으로 해줘"로 명시 선택. diff가 가장 작고, accept 서비스 메서드를 `@Transactional` 단일 커넥션 그대로 둔다.
- **영향**: 만료 정리 트리거가 서비스 불변식이 아니라 **HTTP 컨트롤러 배선**에 존재하게 된다. accept를 비-HTTP로 호출하면 만료 정리가 걸리지 않는다(현재 호출자는 컨트롤러뿐). 그래서 "accept가 만료 정리를 트리거한다"의 회귀 방어를 서비스 단위 테스트에서 E2E로 옮겼다(아래 「스스로 판단한 것」).

## 스스로 판단한 것

- **만료-수락 테스트를 서비스 통합 → E2E로 이전**: 선택 (B) 때문에 만료 정리가 컨트롤러에서 일어나므로, `RiderDeliveryRequestServiceIntegrationTest`가 서비스를 직접 불러 검증하던 "만료 주문 수락 시 취소·환급+409"는 더 이상 서비스만으로는 성립하지 않는다(서비스만 부르면 만료 주문이 그대로 ASSIGNED 된다). 그 시나리오를 `RiderDeliveryRequestE2ETest`로 옮겨 실제 HTTP → 컨트롤러 → 만료 정리 배선을 검증한다. 전용 헬퍼(지갑 픽스처·backdate)와 미사용 필드/임포트도 함께 이전했다.
- **`DeliveryTimeoutService` 임포트는 서비스에 남겨 둠**: 필드·호출은 지웠지만 `acceptDeliveryRequest` javadoc의 `{@link DeliveryTimeoutService#cancelIfExpired}`가 여전히 참조하므로 임포트를 유지했다.
- **단위 테스트의 `DeliveryTimeoutService` mock 제거**: 서비스가 더는 의존하지 않으므로 죽은 mock을 지웠다(스텁/검증이 없어 안전).
- **범위를 accept 경로로 한정**: `createDelivery→expireIfStale`도 구조적으로 같은 2커넥션 패턴이지만, 이번 이슈/부하 리허설의 실측 대상이 accept뿐이고 create 전용 부하 스크립트가 없다. 같이 고치면 PR이 커지고 검증 근거가 없어, 후속으로 분리했다(사람이 명시 선택한 항목은 아님 — 내 판단). 아래 「일부러 하지 않은 것」·「새로 생긴 미결」 참조.

## 일부러 하지 않은 것

- **`createDelivery→expireIfStale`의 동일 교착 수정**: 이유 — 측정된 적 없고 이슈 초점 밖. 후속: 별도 이슈 [#463](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/463)으로 등록.
- **다중 인스턴스 스캐너 중복/분산락 등 #42의 기존 미결**: 이번 수정과 무관해 건드리지 않음.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderDeliveryRequestServiceTest` | accept 로직(상태 위반·취소/이미배차 409 등). 죽은 mock 제거 후에도 그대로 통과 |
| 통합 | `RiderDeliveryRequestServiceIntegrationTest` | 배차 확정·경쟁(동일주문 2라이더/동일라이더 2주문 정확히 1건). 만료-수락 테스트는 E2E로 이전 |
| 통합 | `DeliveryTimeoutServiceIntegrationTest` | 배차 확정 vs 자동취소 경쟁 "정확히 하나만 성공" — 만료 주문에서 accept가 더 이상 자체 취소를 하지 않으니, 이제 assign↔cancel의 깨끗한 CAS 경쟁으로 통과 |
| E2E | `RiderDeliveryRequestE2ETest` | (신규) 만료 주문을 HTTP로 수락하면 컨트롤러 배선이 취소·전액 환급 후 409. 기존 accept 정상/예외 흐름 |

실행 결과:

```text
./gradlew test --tests RiderDeliveryRequestServiceTest \
  --tests RiderDeliveryRequestServiceIntegrationTest(15) \
  --tests DeliveryTimeoutServiceIntegrationTest(5) \
  --tests RiderDeliveryRequestE2ETest(19)
→ BUILD SUCCESSFUL, failures=0 errors=0 (모든 층)
```

부하 리허설(k6 contention, 주문 4×라이더 10=40쌍, HikariCP 풀 기본 10, 로컬 docker MySQL):

```text
BEFORE(버그 코드): VUS=40 → 1건만 즉시 완료 후 39 VU가 ~30초 정지 → 일제 실패.
                    승자 3, 패자 2, 오류 35, 총 30.3초 (HikariCP connectionTimeout 30초 신호).
AFTER (수정본):    VUS=40 → 40건 전부 0.4초 완료. 승자 4==K, 패자 36, 오류 0 (이중 배차 0).
```

before/after는 같은 시드 위에서 수정본을 stash로 떼었다 붙였다 하며 대조했다. 부하용 시더(`AcceptLoadSeeder`, `loadtest-seed` 프로파일)와 k6 스크립트는 저장소에 커밋하지 않는 로컬 벤치 하니스라 이 브랜치에 포함하지 않았다. 리허설 후 dev DB의 `loadtest_` 시드는 정리했다.

### 검증하지 못한 것

- 다중 인스턴스에서의 재현/수정 확인(단일 인스턴스 리허설만 수행).
- Grafana 서버측 지표(HikariCP `connections_pending`, MySQL QPS) 상관 — k6 커스텀 지표(won/lost/error, wall-clock)로만 판정했다. 모니터링 스택 없이도 "30초 정지 후 일제 실패 → 0.4초 무오류"의 대비가 교착 제거를 충분히 보인다고 판단.

## 새로 생긴 미결 사항

- **`createDelivery→expireIfStale`도 같은 2커넥션 패턴이다** → 별도 이슈 [#463](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/463): `DeliveryService.createDelivery`(`@Transactional`)가 첫 줄에서 `expireIfStale`(`REQUIRES_NEW`)를 부른다. 서로 다른 고객이 동시에 다수 주문을 생성하면 같은 방식으로 풀이 고갈될 수 있다. 측정된 적 없고 이번 범위 밖이라 #463으로 분리했다(같은 수정 방식 적용 가능).
- **만료 정리가 `requireAvailable`(403)보다 먼저 실행되는 순서 변화**: 선택 (B)의 결과다. 만료 정리는 멱등·주문 스코프라 non-AVAILABLE 라이더가 트리거해도 무해하지만(오히려 "누가 시도하든 정리"가 더 옳다고 볼 여지), 서비스 javadoc의 문서화된 단계 순서와는 달라졌다. 필요 시 순서를 되살리려면 선택 (A)/(C)로 재구성해야 한다.
