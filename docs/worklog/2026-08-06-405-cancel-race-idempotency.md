# 동시 취소 요청 멱등성 보장 작업 기록

- 이슈: [#405](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/405)
- 브랜치: `feature/405-delivery-cancel-race-idempotency`
- 범위: backend
- 작성일: 2026-08-06

## 무엇을 만들었나

같은 주문에 취소 요청 두 개가 거의 동시에 들어오면 도착 간격에 따라 200/409가 갈리던 문제(#369
리뷰에서 @githings 제기)를 고쳤다. `DeliveryService.cancelDelivery`의 조건부 UPDATE
(`cancelIfWaiting`)가 0행일 때 곧바로 409를 던지던 것을, 잠금 읽기(`FOR SHARE`)로 재조회해
CANCELED면 200(멱등), 그 외 상태면 409로 사유를 구분하도록 바꿨다.

### API

변경 없음(기존 `PATCH /api/customer/deliveries/{deliveryId}/cancel`, #47의 동작만 바뀜).

### 화면

해당 없음.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. 재조회 방식 — 같은 트랜잭션 FOR SHARE vs 새 트랜잭션(REQUIRES_NEW)

- **물었던 것**: 조건부 UPDATE 0행 시 "다시 DB를 쿼리해서 CANCELED인지 확인"을 어떻게 구현할지.
  MySQL InnoDB REPEATABLE READ에서는 평범한(락 없는) 재조회가 이 트랜잭션이 시작할 때 고정된
  스냅샷을 그대로 따르기 때문에, 방금 경쟁 트랜잭션이 커밋한 값을 못 볼 수 있다는 게 먼저 확인됐다.
- **선택지**:
  - (A) 같은 트랜잭션에서 `FOR SHARE`(락 읽기)로 재조회 — 스냅샷을 건너뛰어 최신 커밋을 직접 봄.
    직전 `cancelIfWaiting` UPDATE가 이미 이 행을 검사해 락을 쥔 상태라(REPEATABLE READ에서는
    조건에 안 맞아도 검사한 행에 락이 남는다) 대기 없이 통과함. 추가 커넥션 불필요.
  - (B) `Propagation.REQUIRES_NEW`로 새 트랜잭션을 열어 평범하게 재조회 — 새 트랜잭션은 시작
    시점 기준으로 스냅샷을 새로 잡으므로 상대의 커밋을 정상적으로 봄. 다만 커넥션 풀에서
    커넥션을 하나 더 빌려야 하고, `REQUIRES_NEW`는 원래 "바깥 트랜잭션의 성패와 무관하게
    독립적으로 커밋돼야 하는 것"(예: `DeliveryTimeoutService.cancelIfExpired`)을 위한 도구라
    단순 확인용 조회에 쓰기엔 과함.
- **고른 것**: (A)
- **근거**: 사람 확인. "이미 벌어진 사실을 정확히 확인"하는 데는 (A)로 충분하고, (B)의 독립적
  커밋 보장은 이 케이스에 필요 없다.
- **영향**: `DeliveryOrderRepository`에 기존 `findByIdForUpdate`(`PESSIMISTIC_WRITE`, 배송 완료
  트랜잭션용)와 대칭인 `findByIdForShare`(`PESSIMISTIC_READ`)를 추가했다. 같은 "조건부 UPDATE
  실패 시 재조회로 사유 구분" 패턴을 쓰는 다른 곳에서도 이 방식을 참고할 수 있다.

### 2. RiderDeliveryRequestService.notWaitingException(#56)도 같이 고칠지

- **물었던 것**: #56의 `notWaitingException`도 같은 "조건부 UPDATE 실패 시 재조회로 사유 구분"
  패턴을 쓰면서 평범한 조회(`findById`)를 쓰고 있어, 겉보기엔 #405와 같은 문제가 있을 수 있어
  보였다. 이번에 같이 고칠지 물었다.
- **선택지**:
  - (A) #405만 진행, #56은 별도 판단으로 남김
  - (B) #56도 같이 FOR SHARE로 고침
- **고른 것**: 둘 다 아님 — 재분석 결과 #56은 애초에 고칠 대상이 아닌 것으로 판단해 손대지 않음.
- **근거**: `acceptDeliveryRequest`의 실제 호출 순서를 다시 보면, `requireAvailable`은 DB를
  안 건드리고(세션 값만 검사), `cancelIfExpired`는 `REQUIRES_NEW`라 완전히 별도 트랜잭션이고,
  `assignIfWaiting`(UPDATE)은 현재 읽기라 스냅샷을 만들지 않는다. 즉 `notWaitingException`의
  `findById`가 **이 트랜잭션의 첫 평범한 SELECT**이고, 그 시점은 이미 경쟁 트랜잭션이 커밋한
  뒤다 — #405와 달리 "먼저 찍어둔 옛 기준"이 없어 최신 값을 정상적으로 본다. 코드 주석의
  "WAITING 분기는 정상 흐름에서 도달 안 함"이 맞을 가능성이 높다는 결론.
- **영향**: `RiderDeliveryRequestService`는 이 이슈에서 변경하지 않았다. 100% 확신하려면
  두 라이더가 동시에 같은 배송을 수락하는 동시성 테스트로 실측해야 하지만, 현재 근거로는
  그 정도 확인까지는 필요하지 않다고 판단했다.

## 스스로 판단한 것

- **`findByIdForShare`를 새로 만들지 않고 기존 `findByIdForUpdate`를 재사용하지 않은 이유**:
  이후 이 행을 다시 쓰지 않는 순수 확인용 조회라 배타 락(`PESSIMISTIC_WRITE`)보다 공유 락
  (`PESSIMISTIC_READ`)이 더 정확한 의도 표현이다. 두 메서드는 대칭 구조로 나란히 둔다.
- **`handleLostRace`를 `cancelDelivery`에서 분리한 이유**: 조건부 UPDATE 실패 시 사유 구분
  로직이 늘어나 메인 메서드가 길어지는 것을 막기 위해서다. `RiderDeliveryRequestService
  .notWaitingException`과 같은 이유(가독성)로 별도 private 메서드로 뺐다.
- **재조회 결과의 409 메시지를 기존과 동일하게 유지**: 재조회로 CANCELED 외 상태를 확인했을 때
  ASSIGNED/MOVING_TO_PICKUP 등을 세분화하지 않고 기존 "이미 배차되었거나 완료되어 취소할 수
  없습니다" 메시지를 그대로 재사용했다 — #56의 에러코드 체계 논의(CLAUDE.md 미결 항목)와 같은
  사안이라 이 이슈에서 새로 만들지 않았다.

## 일부러 하지 않은 것

- **#56(`RiderDeliveryRequestService.notWaitingException`)에 같은 패턴 적용**: 위 "사람이 고른
  선택 2"에서 설명한 대로, 재분석 결과 실제 결함이 확인되지 않아 손대지 않았다. 동시 수락
  경쟁을 실측하는 통합 테스트도 이번 이슈 범위에서는 만들지 않았다 — 필요해지면 별도 판단.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `DeliveryServiceTest.CancelDeliveryTest` | 재조회 결과가 CANCELED가 아니면(배차 등 다른 전이) 409, CANCELED면(동시 취소 경쟁에서 짐) 200 멱등 응답 |
| 통합 | `DeliveryCancelIntegrationTest.bothCancelRequestsSucceedIdempotentlyWhenRacingEachOther` | 실제 MySQL에서 취소 요청 두 개를 `CountDownLatch`로 동시에 실행해 둘 다 예외 없이 200이고, 환급은 정확히 한 번만(잔액 전액 복구, `point_transaction` 2건만) 일어남을 확인 |

실행 결과:

```text
./gradlew test --tests '*DeliveryServiceTest' → BUILD SUCCESSFUL
./gradlew test --tests '*DeliveryCancelIntegrationTest' → BUILD SUCCESSFUL (6 tests, 0 failures)
./gradlew test (전체) → BUILD SUCCESSFUL (585 tests, 0 failures, 0 errors)
```

### 검증하지 못한 것

- 세 요청 이상이 동시에 경쟁하는 경우(이슈는 두 요청 기준). 락 메커니즘상 순차적으로 하나씩만
  통과하므로 결과는 같을 것으로 예상하지만 별도로 실측하지 않았다.

## 새로 생긴 미결 사항

- 없음. (검토했던 #56 관련 의심은 재분석으로 해소됨 — CLAUDE.md에 새 미결 항목을 추가하지 않음)
