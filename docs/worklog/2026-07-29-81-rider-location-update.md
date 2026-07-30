# 라이더 현재 위치 갱신 작업 기록

- 이슈: [#81](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/81)
  — 서브 이슈 [#233](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/233)
  · [#234](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/234)
  · [#235](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/235)
  · [#236](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/236)
- 브랜치: `feature/81-rider-location-update`
- 범위: backend
- 작성일: 2026-07-29

## 무엇을 만들었나

시작은 `#196`(프론트 공용 훅 `useTrackingStream`·`useLocationSender`)이었다. 그런데 그 훅이
소비할 백엔드가 존재하지 않았다 — `location` 패키지는 `.gitkeep` 뿐이고 `/v3/api-docs` 에
location·sse 경로가 하나도 없었으며, 의존 이슈 #77~#82 가 전부 OPEN 이었다. 그래서 #196 을
중단하고 그중 첫 조각인 #81 을 먼저 구현했다.

`location` 도메인의 첫 실체가 생겼다. 라이더가 위치를 올리면 검증·판정을 거쳐 Redis 최신 위치가
갱신된다. 이력 저장(#102)·GEO 검색(#83)·SSE 전파(#78)·거리·속도 필터(#82)는 범위 밖이다.

6개 이슈를 한 번에 진행하니 사람이 따라갈 수 없다는 피드백을 받아, **#81 을 4개 서브 이슈로
쪼개 하나씩 확인받으며 진행**했다. 커밋도 서브 이슈당 하나다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| POST | `/api/rider/location` | 라이더 최신 위치 갱신 (`operationId=updateRiderLocation`) | 400 좌표 범위·필수 값·정확도 음수·미래 시각 / 401 세션 / 409 운행 중 아님 |

요청이 정상이어도 값을 쓸 수 없으면 **200 으로 응답하면서 버린다**. 응답 `reason` 이
`STALE`·`LOW_ACCURACY`·`NON_MONOTONIC` 중 하나가 된다(아래 「사람이 고른 선택」 3번).

### 화면

해당 없음. 프론트 연동은 #196 이며, 이 API 가 생겼으므로 이제 `pnpm generate:api` 로
`useUpdateRiderLocation` 훅을 받을 수 있다.

### 스키마 변경

없다. `rider_location_history`(V15)는 이미 있지만 이 범위에서 쓰지 않는다(#102).

## 사람이 고른 선택

### 1. #196 을 중단하고 백엔드부터 구현

- **물었던 것**: 백엔드 계약(#77~#82)이 없는 상태에서 #196 을 어디까지 진행할까?
- **선택지**:
  - (A) 잠정 계약을 프론트에 한 파일로 격리하고 플래그 off 로 화면까지 배선 — 이슈 완료 조건을
    다 채우지만, 플래그를 켜기 전까지 브라우저에서 실제 연결을 확인할 수 없다
  - (B) 훅·엔진·테스트만 만들고 화면 배선 제외 — 틀릴 수 있는 계약이 화면에 퍼지지 않지만
    "부착 화면" 요구를 후속으로 미룬다
  - (C) #196 중단, #77~#82 먼저 — 추측이 0이지만 백엔드 6건이 미착수라 프론트가 무기한 멈춘다
- **고른 것**: (C), 그리고 이어서 "내가 #77~#82 백엔드를 구현"
- **근거**: 사람 확인. 잠정 계약으로 프론트를 먼저 쓰는 것보다 실제 계약을 만드는 편이 낫다는 판단.
- **영향**: 범위가 `frontend` → `backend` 로 바뀌었다. #77~#82 는 jsj3473 에게 할당돼 있어
  **중복 작업 위험이 있다** — 팀 공유가 필요하다.

### 2. 위치 전송 주기·필터 임계값 확정

- **물었던 것**: `CLAUDE.md` 「확인이 필요한 항목」에 미결로 남아 있던 수치를 무엇으로 확정할까?
- **선택지**: 표준안 / 보수안(요청 절감, AVAILABLE 60s·BUSY 10s·30m) / 적극안(반응성 우선,
  AVAILABLE 20s·BUSY 3s·10m — Redis 쓰기와 배터리가 표준안의 1.7배)
- **고른 것**: **표준안**
  - AVAILABLE 30s · BUSY 5s · 최소 이동 20m · 최대 속도 50 m/s(180km/h) · 정확도 상한 100m
  - 강제 전송 간격 120s · 재연결 백오프 1→2→4→8→16→30s ±20%, 최대 6회
- **근거**: 사람 확인. BUSY 5초는 40km/h 에서 약 55m 간격이라 지도 마커가 튀지 않는 최소치이고,
  AVAILABLE 30초는 배차 반경이 km 단위라 충분하다.
- **영향**: 서버 필터 임계값도 **같은 값**을 쓴다. 클라이언트가 통과시킨 좌표는 서버도 통과해야
  두 필터가 서로 싸우지 않는다. Redis TTL 10분은 강제 전송 간격 120초의 5배로 잡았다.

### 3. 정확도 초과·오래된 fix 를 400 이 아니라 200 + reason 으로

- **물었던 것**: #234 등록 시 정확도 상한 초과와 60초 초과 과거 fix 를 둘 다 400 으로 적었는데,
  이대로 갈지.
- **선택지**:
  - (A) 200 + reason 으로 수용·폐기, 400 은 구조적 오류만
  - (B) 이슈 등록대로 둘 다 400 — 이슈·계획서를 안 건드리고 거부 조건이 한 곳에 모인다
  - (C) 정확도만 200, 오래된 fix 는 400
- **고른 것**: (A)
- **근거**: 사람 확인. 정확도 150m·70초 전 fix 는 실내·지하·탭 복귀 직후에 **정상적으로
  발생**하고 클라이언트가 고칠 것이 없다. 4xx 로 주면 라이더 화면이 정상 상황에서 에러를 기록하고
  로그·모니터링에서 진짜 문제와 섞인다.
- **영향**: 정확도 상한을 `@DecimalMax` 로 달 수 없게 됐다 — Bean Validation 위반은
  `GlobalExceptionHandler` 를 타고 400 이 된다. 그래서 상한·시각 판정을
  `LocationAcceptancePolicy` 로 분리했고, DTO 어노테이션에는 400 이 되어야 하는 것만 남겼다
  (정확도 **음수**는 물리적으로 불가능한 입력이라 400 이다).

### 4. #235 를 통합 테스트가 아니라 단위 테스트로

- **물었던 것**: 이슈 본문에 통합 테스트라고 적었지만, 이 서비스는 DB 를 읽지 않는데 그대로 갈지.
- **고른 것**: 단위 테스트
- **근거**: 사람 확인. `RiderSessionInterceptor` 가 매 요청 `RiderProfile` 을 새로 읽어 운행
  상태를 넘겨주므로 서비스가 DB 를 볼 이유가 없고, 따라서 트랜잭션 경계도 없다.
  `testing.md` 의 층 구분상 통합이 잡는 것(트랜잭션 경계·JPA 매핑·DB 제약·동시성)이 하나도
  해당하지 않는다.
- **영향**: `@Transactional` 을 붙이지 않았다. 붙이면 위치 전송마다 아무 일도 하지 않는 DB
  트랜잭션이 열린다. DB 가 실제로 개입하는 검증은 #236 E2E 가 담당한다.

### 5. 서브 이슈를 GitHub 에 등록하는 범위

- **물었던 것**: 분할한 16개(=#77~#82 전체)를 다 등록할지.
- **고른 것**: **#81 상위에만 4개**(#233~#236). 나머지는 계획서 표로 두고 착수 시 등록한다.
- **근거**: 사람 확인. 아직 생각이 덜 익은 #79·#80 분할이 이슈로 박제되는 것을 피한다.

### 6. 테스트 이름 규칙

- 사람이 한글 스네이크 케이스 대신 `@DisplayName`(+ 필요시 `@Nested`)을 쓰라고 요청했고,
  확인해 보니 `testing.md` 가 이미 그렇게 개편돼 있었다(내가 오래된 내용을 보고 있었다).
  새 테스트 8개 파일 전부 그 규칙을 따른다.

## 스스로 판단한 것

- **`location/store/` 대신 `location/service/`**: 계획서에 `store/` 라고 썼지만, 같은 성격의
  선례인 `VerificationCodeStore` 가 `member/service/` 에 있고 `location/` 하위에 이미
  `controller`·`dto`·`repository`·`service`·`sse` 골격이 잡혀 있었다. `repository/` 는 이
  저장소에서 JPA 전용이라 #102 의 `RiderLocationHistoryRepository` 자리다.

- **Redis 를 Hash 가 아니라 `SET key value EX` 로**: `RedisSessionStore` 는 `HSET` + `EXPIRE`
  두 번 호출이고 주석에 "세션 생성 경로라 그 사이 짧은 창은 감내 가능"이라고 적혀 있다. 세션은
  로그인당 1회지만 **위치는 라이더당 수 초에 한 번**이다. 그 사이 프로세스가 죽으면 TTL 없는 키가
  남고, 그 라이더가 다시 안 보내면 옛 좌표가 영구히 배차 후보에 잡힌다. 네 필드를 한 문자열로
  합쳐 단일 원자 명령으로 바꿨다.

- **구분자를 `|` 가 아니라 쉼표로**: `String.split` 의 인자는 정규식이고 `|` 는 메타문자다.
  escape 를 빠뜨리는 순간 문자 단위로 쪼개진다. 메타문자가 아닌 문자를 골라 함정 자체를 없앴다.
  `split(DELIMITER, -1)` 의 `-1` 도 필수다 — 기본값은 뒤쪽 빈 조각을 버려서 정확도 없는 위치가
  전부 "손상된 값"이 된다. 이 회귀를 잡는 테스트를 두고, `-1` 을 지웠을 때 실제로 실패하는 것을
  확인했다.

- **TTL 을 파라미터가 아니라 저장소 상수로**: `SessionStore.create` 는 TTL 을 받지만 그건 세션
  TTL 이 로그인 정책(2시간, #26)이라 호출자가 정할 일이기 때문이다. 최신 위치 유효 시간은 이
  저장소의 인프라 성질이라, 호출자가 매번 넘기면 서비스마다 다른 값이 들어갈 통로가 생긴다.

- **`delete` 를 인터페이스에 넣지 않았다**: 운행 종료 시 즉시 지우는 것이 맞지만 그 API 가 아직
  없어 부를 곳이 없다. "인터페이스만 만들고 다음 이슈에서 쓴다"는 반쪽 작업을 피했다. 대가는
  아래 미결 사항 1번이다.

- **좌표를 `BigDecimal` 로 두고 생성 시점에 정규화**: `RiderLocationHistory` 가 `BigDecimal` 이고
  #102 가 이 값을 그대로 엔터티에 넣는다. `double` 을 거치면 scale 7 이 깨진다. 정규화를 record
  compact constructor 에 두면 `encode`/`decode` 양쪽이 자동으로 같은 값을 갖는다. 거리·속도
  계산(#82)에서만 `doubleValue()` 로 내린다.

- **`measuredAt` 을 `Instant` 로**: 처음엔 "`Z` 가 붙은 문자열은 `LocalDateTime` 으로
  역직렬화되지 않는다"고 판단했는데 **실제로 돌려 보니 틀렸다.** 실측 결과:

  | 입력 | `LocalDateTime` | `Instant` |
  |---|---|---|
  | `2026-07-29T12:34:56.789Z` | 통과 | 통과 |
  | `2026-07-29T21:34:56.789+09:00` | 실패 | 통과, UTC 정규화 |
  | `2026-07-29T12:34:56.789` | **조용히 UTC 로 간주** | 실패 |

  결론은 유지되지만 근거는 마지막 줄이다. `LocalDateTime` 은 시간대 없는 문자열을 UTC 로 단정하니,
  클라이언트가 로컬 시각을 보내면 9시간 틀어진 값이 **오류 없이** 들어오고 이후 모든 위치가
  `STALE` 로 판정돼 위치 갱신이 통째로 죽는다. 원인을 찾기 가장 어려운 실패라 `Instant` 로 끊었다.
  기존 DTO 의 시각 필드는 모두 `LocalDateTime` 이지만 전부 **응답**이고, 요청으로 시각을 받는
  선례는 없었다. 내부 저장 타입은 관례대로 UTC `LocalDateTime` 을 유지한다.

- **측정 시각 비교를 #82 가 아니라 #81 에 뒀다**: 처음엔 "필터는 전부 #82"로 뭉뚱그렸지만
  #81 원본 처리 흐름의 `③ 이전 위치와 시간 비교`가 #81 안에 있다. 순서가 뒤바뀐 요청이 "최신
  위치"를 과거로 되돌리는 것은 트래픽 최적화가 아니라 저장 값의 정합성 문제라 미룰 수 없다.
  이전과 **같은** 시각의 재전송도 막는다(덮어써도 값은 같은데 Redis 쓰기만 늘어난다).

- **폐기 판정에서는 저장도 하지 않는다**: "저장은 하되 전파하지 않는다"는 예외는 #82 의 최소
  이동 거리 미달(정지한 라이더의 TTL 갱신)에만 해당한다. `STALE`·`LOW_ACCURACY`·`NON_MONOTONIC`
  은 값 자체를 믿을 수 없는 경우다. 이 둘을 헷갈리기 쉬워 Javadoc·커밋에 반복해 적었다.

- **정책이 던진 예외를 감싸지 않는다**: `throw new IllegalArgumentException(e)` 로 감싸면
  `getMessage()` 가 `"java.lang.IllegalArgumentException: 측정 시각이…"` 로 바뀐다.
  `GlobalExceptionHandler` 가 그 값을 `ApiResponse.fail` 에 그대로 쓰므로 **API 응답 본문에
  Java 클래스명이 새어 나간다.**

- **운행 상태 검사를 `== UNAVAILABLE` 이 아니라 허용 목록(`EnumSet`)으로**: 상태가 하나 늘었을 때
  거부가 아니라 **허용**으로 조용히 새는 쪽이 위험하다.

- **`encode`/`decode` 를 `static` package-private 로**: `StringRedisTemplate` 을 목킹해도
  "내가 짠 목이 내 코드를 호출했다"만 확인된다. 실제로 깨지는 건 문자열 형식이라 그것만 직접 쳤다.

- **`@RequestAttribute` 파라미터에 `@Parameter(hidden = true)`**: 감추지 않으면 springdoc 이
  파라미터로 노출하고 Orval 이 그것을 인자로 받는 훅을 만들어, 프론트가 라이더 식별자를 넘길 수
  있는 것처럼 보인다. 저장소에 인터페이스 + `@RequestAttribute` 조합 선례가 없어 실제
  `/v3/api-docs` 로 확인했다(`parameters` 비어 있음).

- **로그 레벨을 나눴다**: 폐기(`LOCATION_DISCARDED`)는 실내 측위·탭 복귀처럼 정상 운행 중에도
  일어나므로 `info`, 운행 종료 후 전송(`LOCATION_REJECTED`)은 클라이언트 버그라 `warn`.
  둘 다 `warn` 이면 정상 동작이 경고 로그를 채워 진짜 문제가 묻힌다. 이 저장소에서 `@Slf4j` 를
  쓰는 첫 서비스다.

## 일부러 하지 않은 것

- **SSE 전파 (#81 흐름 ⑤)**: BUSY 라이더의 좌표를 배정 주문 구독자에게 밀어 주는 것 — 이유:
  #78 범위. `DeliveryOrderRepository` 도 그때 만든다 — 후속: #78
- **거리·속도 기반 중복·이상 이동 필터**: 이유: #82 범위 — 후속: #82
- **Redis GEO 배차 후보 반영**: 이유: #83 범위 — 후속: #83
- **MySQL 위치 이력 선별 저장**: `RiderLocationHistory`·V15 를 건드리지 않았다 — 이유: #102 범위
  — 후속: #102
- **`RiderLocationStore.delete`**: 이유: 운행 상태 변경 API 가 없어 부를 곳이 없다 — 후속: 그 API
  구현 이슈 (미등록)
- **실제 Redis 왕복을 영구 테스트로 두는 것**: 이유: `testing.md` 가 Redis 를 인메모리 대체로
  바꾸라고 규정하고 CI 에 Redis 가 없다 — 후속: 미등록 (아래 「검증하지 못한 것」 참고)
- **#196 프론트 훅**: 이유: 이 작업의 출발점이었지만 백엔드 계약을 먼저 만들기로 했다. #77~#80,
  #82 가 남아 있어 `useTrackingStream` 은 아직 붙일 대상이 없다 — 후속: #196

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `location/dto/RiderLocationSnapshotTest` (8) | 좌표 scale 7·정확도 scale 2 정규화, 반올림, 필수 값, **범위 검증은 하지 않음**(#234 경계 명시) |
| 단위 | `location/service/RedisRiderLocationStoreTest` (11) | encode/decode 왕복, 정확도 null 왕복(`split(-1)` 회귀), 음수 좌표, 값 형식 리터럴 고정, 손상된 값 5종 |
| 단위 | `location/service/InMemoryRiderLocationStoreTest` (4) | 대체 구현이 계약을 지키는지(이게 틀리면 #235·#236 이 잘못된 전제에서 통과) |
| 단위 | `location/dto/RiderLocationUpdateRequestTest` (16) | Bean Validation 경계값, **정확도 상한이 위반이 아님**, `toISOString`·오프셋 파싱, 시간대 없는 시각 거부, 스냅샷 변환 |
| 단위 | `location/dto/RiderLocationUpdateResponseTest` (4) | `applied`/`published` 분리, 폐기는 전파되지 않음 |
| 단위 | `location/service/LocationAcceptancePolicyTest` (12) | 5초·60초·100m 경계값, 판정 순서 고정 |
| 단위 | `location/service/RiderLocationServiceTest` (12) | 운행 상태별 저장 여부(**예외 + 저장소가 비어 있음**), 폐기 3종, 시각 단조성 4종 |
| E2E | `location/controller/RiderLocationE2ETest` (9) | **쿠키 없이 401**(인터셉터 등록 누락 회귀), 고객 세션 401, 만료 쿠키, 200 갱신, 409, 400 2종, 정확도 초과는 200 폐기, 재전송은 `NON_MONOTONIC` |

실행 결과:

```text
./gradlew test  →  전체 tests=271 failures=0 errors=0 skipped=0
                   (이번 작업으로 76개 추가: #233 23 / #234 32 / #235 12 / #236 9)
```

**테스트가 실제로 회귀를 잡는지 세 번 확인했다** (일부러 코드를 망가뜨려 실패를 확인하고 되돌림):

```text
split(DELIMITER, -1) → split(DELIMITER)
  → 왕복 > 정확도가 없어도 왕복된다 FAILED
    왕복 > 측정 시각의 밀리초가 왕복에서 보존된다 FAILED   (11 tests, 2 failed)

isOutOfOrder 가드 무력화
  → 측정 시각 단조성 > 이전과 같은 시각의 재전송도 덮지 않는다 FAILED
    측정 시각 단조성 > 이전보다 과거인 좌표는 최신 위치를 덮지 않는다 FAILED   (12 tests, 2 failed)

RiderWebMvcConfig 에서 /api/rider/location 등록 제거
  → 9 tests completed, 8 failed
```

마지막 것은 예상보다 좋은 결과다. `@RequestAttribute` 가 필수 파라미터라 인터셉터가 없으면 API 가
**아예 동작하지 않는다** — "인증 없이 조용히 열리는" 것보다 나은 실패 방식이다.

`/v3/api-docs` 실측(local 프로파일 기동 후):

```text
경로 존재: True           operationId: updateRiderLocation
tags: ['rider-location']  노출된 parameters: 없음
measuredAt: string/date-time
required: ['latitude', 'longitude', 'measuredAt']
reason enum: ['ACCEPTED', 'STALE', 'LOW_ACCURACY', 'NON_MONOTONIC']
응답 코드: ['200', '400', '401', '409']
```

실제 Redis 왕복(컨테이너에 직접 붙인 **일회성** 확인, 영구 테스트로 남기지 않음):

```text
raw    = 37.4979000,127.0276000,2026-07-29T12:34:56.789,12.50
ttl(s) = 600                     ← SET+EX 로 값과 TTL 이 함께 걸린다
equal  = true                    ← scale 7 이 왕복에서 보존된다
raw2   = -33.8688000,-70.6693000,2026-07-29T12:00,   ← 정확도 null
miss   = Optional.empty
```

### 검증하지 못한 것

- **실제 Redis TTL 만료 동작.** 위 확인은 `SET+EX` 로 TTL 600초가 걸린다는 것까지다. 10분을
  기다려 키가 사라지는지는 확인하지 않았다. 통합·E2E 는 인메모리 대체를 쓰고 그 대체는 TTL 을
  흉내만 낸다(`testing.md` 규정). `#20` 에서 이미 올라간 미결 사항과 같은 성질이다.
- **`HttpMessageNotReadableException` 의 응답 본문 형태.** `GlobalExceptionHandler` 에 그
  핸들러가 없고 `ResponseEntityExceptionHandler` 도 상속하지 않는다. 시간대 없는 `measuredAt`
  처럼 본문 파싱이 실패하는 400 이 `ApiResponse` 형태가 아닌 스프링 기본 오류 본문으로 나갈
  것으로 보이지만, 실제 본문을 확인하지 않았다. **이 저장소의 모든 엔드포인트에 해당하는
  기존 빈틈**이다.
- **브라우저·실제 라이더 단말에서의 동작.** 프론트가 아직 이 API 를 호출하지 않는다(#196).
- **동시 요청.** 같은 라이더가 두 요청을 동시에 보내면 `find` → `save` 사이에 경쟁이 있다. 결과는
  "둘 중 하나가 최신으로 남음"이고 어느 쪽이든 유효한 좌표라 무해하다고 판단해 테스트하지 않았다.
  다만 판단만 했고 재현은 하지 않았다.

## 새로 생긴 미결 사항

1. **운행 종료 후 최대 10분간 최신 위치가 Redis 에 남는다.** `RiderLocationStore.delete` 를 만들지
   않았고 운행 상태 변경 API 도 없어서, TTL 이 만료될 때까지 배차 후보 검색(#83)에 잡힌다.
   운행 상태 변경 API 를 구현할 때 `delete` 를 함께 추가해야 한다.
2. **실제 Redis TTL 만료 동작이 여전히 검증되지 않는다.** 인메모리 대체가 TTL 을 흉내만 내기
   때문이다. Redis 를 쓰는 테스트를 어떤 층에 둘지(또는 CI 에 Redis 서비스 컨테이너를 붙일지)
   결정이 필요하다.
3. **`GlobalExceptionHandler` 에 `HttpMessageNotReadableException` 핸들러가 없다.** 본문 JSON
   파싱 실패 400 이 `ApiResponse` 형태로 나가지 않을 것으로 보인다. 프론트가 의존하는 응답 계약이
   깨지는 지점이고 전체 엔드포인트에 해당한다 — 별도 이슈로 올릴지 판단이 필요하다.
4. **#77~#82 가 jsj3473 에게 할당돼 있는데 #81 을 다른 사람이 구현했다.** 서브 이슈 4개는
   구현자에게 할당했지만 상위 #81 은 그대로다. 나머지 #77~#80·#82 를 누가 할지 팀에서 정리해야
   중복 작업을 피할 수 있다.
