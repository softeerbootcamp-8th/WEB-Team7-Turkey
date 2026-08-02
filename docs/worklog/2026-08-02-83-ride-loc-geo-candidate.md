# [RIDE-LOC-003] 운행 상태 기반 GEO 배차 후보 반영 작업 기록

- 이슈: [#83](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/83)
- 브랜치: `feature/83-ride-loc-geo-candidate`
- 범위: backend
- 작성일: 2026-08-02

## 무엇을 만들었나

라이더 위치 갱신(`POST /api/rider/location`, #290)과 배차 확정(`POST /api/rider/requests/{id}/accept`, #56)
두 시점에서 `riders:geo`(Redis GEO)를 최신 상태로 유지한다. `GET /api/rider/requests`(#55)가 이미
이 키를 읽고 있었는데, 쓰는 코드가 없어 사실상 죽어 있던 경로를 살렸다.

새 엔드포인트는 없다 — 기존 두 API의 부수 효과로 반영한다.

### API

기존 API의 내부 동작 변경만 있다(요청/응답 스키마 변화 없음).

| 메서드 | 경로 | 변경 |
|---|---|---|
| POST | `/api/rider/location` | AVAILABLE→GEO 등록/갱신, BUSY→GEO 제거 |
| POST | `/api/rider/requests/{id}/accept` | 배차 확정 성공 시 GEO 즉시 제거 |

### 화면

해당 없음(백엔드 전용, 소비할 화면은 #41/#55 쪽).

### 스키마 변경

해당 없음(기존 `riders:geo` 키를 그대로 씀, ERD에 이미 확정된 값).

## 사람이 고른 선택

### 1. #83과 다중 인스턴스 Redis Pub/Sub 중 무엇을 먼저 할지

- **물었던 것**: 사용자가 "라이더 위치 Redis 최신화"와 "다중 인스턴스 대비 Pub/Sub 도입" 두 가지를
  동시에 지시했다. 어느 것부터 할지.
- **선택지**:
  - (A) #83(GEO 최신화) 먼저 — 지금 당장 고장난 경로(#55)를 고침, 실사용 가치 즉시 발생
  - (B) Pub/Sub 먼저 — 실제 배포는 단일 인스턴스라 지금 기능적으로 고치는 것이 없음
- **고른 것**: (A)
- **근거**: 사용자가 "실제 배포 환경은 단일 인스턴스지만 스케일 아웃에 대비해야 한다"고 확인해줬고,
  "최소한의 기능을 기준으로 삼아" 우선순위를 정해달라고 했다. #83은 지금 고장난 걸 고치는 것이고
  Pub/Sub은 순수 대비용이라 (A)를 골랐다.
- **영향**: Pub/Sub 도입(다중 인스턴스 SSE 팬아웃)은 별도 이슈로 이어서 진행 예정(아직 이슈 번호 없음,
  예전 #78 설계가 참고 대상이나 #297로 제거된 상태).

### 2. #54(PR #284)와의 관계

- **물었던 것**: (사용자가 알려줌) PR #284(#54, 운행 상태 변경 API)가 이미 구현·테스트까지 끝났는데,
  `location` 패키지 단순화(#297) 때 그 PR이 의존하던 `RiderLocationStore`/`RedisRiderLocationStore`를
  지워서 지금 merge conflict 상태다.
- **선택지**:
  - (A) #83을 먼저 끝내고, #83이 만든 `RiderGeoRepository.remove`로 PR #284의
    `RiderLocationStore.delete` 호출부를 교체
  - (B) PR #284 쪽에서 `RiderLocationStore`를 되살려 임시로 충돌만 해소
- **고른 것**: (A)
- **근거**: `RiderLocationStore.delete(riderId)`와 이번에 만든 `RiderGeoRepository.remove(riderId)`가
  의미상 완전히 같다(둘 다 "즉시 배차 후보에서 제외", 멱등 삭제). 저장소 하나를 되살렸다가 다시
  지우는 것보다 처음부터 새 저장소로 교체하는 게 낫다.
- **영향**: #83 머지 후 PR #284를 새 dev로 리베이스하고 필드·호출부만 치환하는 후속 작업이 남는다.
  이 문서에는 그 계획만 적고, 실제 리베이스는 별도 커밋에서 진행한다.

## 스스로 판단한 것

- **배차 확정(#56) 시점에도 즉시 GEO 제거를 추가함**: 이슈 본문 "비고"에 "배차 확정 …과 연계하여
  후보 상태가 늦게 반영되지 않도록 한다"는 문장이 있어서, 위치 갱신 엔드포인트 훅만으로는 부족하다고
  판단했다. 배차가 확정된 순간부터 다음 위치 전송 전까지는 라이더가 BUSY인데도 GEO에는 여전히
  AVAILABLE 시절 좌표가 남아 다른 고객의 배차 후보로 잘못 잡힐 수 있는 창이 생기기 때문이다.
  `RiderDeliveryRequestService.acceptDeliveryRequest`에 `riderGeoRepository.remove` 호출을 추가했다.
- **Redis 실패를 예외로 전파하지 않고 로깅만 함**: 이슈 예외 처리 조항이 "Redis 갱신 실패 시 해당
  라이더를 배차 후보로 사용하지 않고 오류를 기록한다"고 명시해서, try/catch(RuntimeException)로
  감싸고 WARN 로깅만 했다. 위치 갱신(SSE 중계)·배차 확정(DB 트랜잭션) 모두 GEO 동기화 실패로
  전체 요청이 실패하면 안 된다는 판단이다.
- **GEO 제거를 ZREM으로 구현**: Spring Data Redis의 `GeoOperations`에는 좌표 전용 삭제 명령이 없다
  (GEO가 ZSET 백엔드라서). 같은 키에 `opsForZSet().remove(key, member)`를 쓰는 게 표준적인 방법이라
  그대로 썼다.

## 일부러 하지 않은 것

- **UNAVAILABLE 전환 시 제거**: #54(운행 상태 변경 API, PR #284)가 아직 dev에 merge되지 않아서, 그
  전환 시점에 훅을 걸 안정된 지점이 없다. PR #284 리베이스 때 `RiderGeoRepository.remove`를 그쪽에서
  호출하도록 연결할 예정 — 후속: PR #284 리베이스 작업(이슈 아님, 진행 중인 PR 수정).
- **배송완료(#62, BUSY→AVAILABLE) 시 재등록**: #62가 아직 구현되지 않았다. 재등록은 다음 위치 전송
  시점에 자연히 반영되므로 당장 기능이 깨지진 않지만, 그 사이 짧은 창에는 GEO에 없는 상태로 남는다
  — 후속: #62 구현 시 함께 고려.
- **"유효한 위치" 필터링(#82)**: 이슈 흐름에 "유효한 위치가 있으면"이라는 조건이 있지만, 위치
  중복·이상 이동 필터링(#82)이 아직 구현 전이라 이번 작업에서는 Bean Validation을 통과한 좌표를
  그대로 유효한 것으로 취급했다 — 후속: #82.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderGeoRepositoryTest` | registerOrUpdate가 GEOADD를, remove가 ZREM을 호출하는지 |
| 단위 | `RiderLocationUpdateControllerTest` | AVAILABLE→등록, BUSY→제거, Redis 실패해도 응답은 성공 |
| 단위 | `RiderDeliveryRequestServiceTest` | 배차 확정 성공 시 GEO 제거 호출, 실패해도 배차 응답은 성공 |
| E2E | `RiderLocationUpdateE2ETest` | 실제 Redis로 AVAILABLE→GEO 등록, BUSY→GEO 제거 확인 |

실행 결과:

```text
./gradlew test → BUILD SUCCESSFUL, 317 tests, 0 failures, 0 errors
```

### 검증하지 못한 것

- PR #284(#54)와의 실제 연결(리베이스 후 `RiderLocationStore.delete` → `RiderGeoRepository.remove`
  치환)은 이 작업에 포함하지 않았다 — 별도로 진행.
- 실제 다중 인스턴스 환경에서의 동작(Redis GEO 자체는 중앙 저장소라 인스턴스 수와 무관하게
  안전하지만, 별도로 부하 검증은 하지 않았다).

## 새로 생긴 미결 사항

- PR #284(#54)가 `location` 패키지 단순화(#297)로 `RiderLocationStore`/`RedisRiderLocationStore`가
  삭제되며 merge conflict 상태다. #83이 만든 `RiderGeoRepository.remove(riderId)`로 교체하는 리베이스
  작업이 남아 있다(CLAUDE.md에도 추가함).
