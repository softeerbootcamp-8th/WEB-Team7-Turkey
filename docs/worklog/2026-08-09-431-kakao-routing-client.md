# 라우팅 클라이언트 OSRM → 카카오모빌리티 API 전환 작업 기록

> **2026-08-10 정정**: 아래 2026-08-09 기록의 "카카오 API 사용 권한 심사가 끝나 확정됐다
> (사람 확인)"는 근거 없이 기록된 오류였다 — 실제로 그런 확인은 없었다. 카카오모빌리티 길찾기
> API를 실제로 쓸 수 없는 상황이 되어, 이 이슈를 OSRM 유지 + 러시아워 duration 보정으로 방향을
> 바꿔 재사용했다. 상세는 맨 아래 「2026-08-10 재작업」 참고. 2026-08-09 기록은 당시 상황
> 그대로 보존한다.

- 이슈: [#431](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/431)
- 브랜치: `feature/431-kakao-routing-client`
- 범위: backend
- 작성일: 2026-08-09

## 무엇을 만들었나

`RoutingClient`(#420)의 구현체를 자체 호스팅 OSRM(`OsrmRoutingClient`)에서 카카오모빌리티
자동차 길찾기 API(`KakaoMobilityRoutingClient`)로 교체했다. 인터페이스·값 타입
(`RoutingClient`/`Route`/`Coordinate`)과 호출자(`DeliveryRouteEstimator` → `DeliveryTrackingQueryService`,
#421)는 한 줄도 바뀌지 않았다 — #420이 인터페이스로 끊어 둔 것이 실제로 어댑터 교체만으로 끝났다.
`OsrmRoutingClient`와 그 테스트는 삭제했다(아래 「사람이 고른 선택」1번).

### API

이슈 없음(내부 어댑터 교체, 외부 API 계약 변경 없음).

### 화면

해당 없음.

### 스키마 변경

해당 없음.

### 설정 변경

- `routing.base-url` 기본값: `http://10.0.130.0:5000`(OSRM) → `https://apis-navi.kakaomobility.com`
- `routing.api-key` 신설: `${KAKAO_REST_API_KEY:}` — 비밀정보라 저장소에 값 없음. 빈 문자열이면
  클라이언트가 호출 자체를 건너뛴다(기동 시 경고 로그 1회, `ROUTING_API_KEY_MISSING`).
- `backend/src/test/resources/application.yml`에 `routing.api-key: test-key-never-sent-to-kakao` 추가
  — 실제로는 아무도 듣지 않는 주소(`localhost:1`)라 이 키가 카카오로 나갈 일은 없지만, 키를 비워
  두면 통합 테스트가 "연결 실패" 경로가 아니라 "키 없음" 경로를 타 버려 `RoutingClientIntegrationTest`의
  검증 취지(라우팅 서버 부재 시 정상 동작)가 흐려진다.

## 사람이 고른 선택

### 1. 기존 `OsrmRoutingClient` 처리

- **물었던 것**: 완전 삭제 / 설정으로 전환 가능하게 병행 유지 / 코드는 남기고 빈 등록만 해제.
- **선택지**:
  - (A) 완전 제거 — 죽은 코드가 안 남지만, 카카오로 급히 되돌려야 할 때 git 이력에서 꺼내야 함
  - (B) `routing.provider` 로 조건부 분기 유지 — 장애 시 설정 한 줄로 되돌릴 수 있지만, 안 쓰는
    경로를 계속 컴파일·테스트해야 하고 배포된 OSRM 서버(#416)도 계속 띄워 둬야 함
  - (C) 클래스는 남기고 `@Component`만 떼기 — `RiderGeoRepository`(#342)처럼 "호출자 0인 의도된
    데드 코드"가 하나 더 늘어남
- **고른 것**: (A)
- **근거**: 이 저장소가 #297→#317에서 실제로 "제거했다가 되돌린" 전례가 있어, 되돌릴 일이 생겨도
  git 이력에서 꺼내는 비용이 낮다고 판단함(사람 확인).
- **영향**: `OsrmRoutingClient.java`·`OsrmRoutingClientTest.java` 삭제. 배포된 OSRM 서버(#416)·
  Redis co-locate 인프라를 걷어낼지는 이 이슈 범위 밖 — 별도 인프라 정리 이슈가 필요하면 그때 판단.

### 2. 타임아웃 값

- **물었던 것**: #420이 OSRM(VPC 내부) 기준으로 잡은 연결 300ms/읽기 700ms를 카카오(공인망 HTTPS)에도
  쓸지.
- **선택지**:
  - (A) 연결 1s / 읽기 2s — 외부 HTTPS API의 통상값. 최악의 경우 추적 API가 3초 밀리지만, TLS
    핸드셰이크가 느린 순간(첫 호출·커넥션 재수립)에 300ms로는 상시 타임아웃날 위험이 큼
  - (B) 연결 500ms / 읽기 1s — 지연을 더 좁게 묶지만 핸드셰이크 실패 위험이 여전함
  - (C) 300ms/700ms 그대로 유지 — 외부 API에는 거의 확실히 짧아 ETA가 상시 비어 있을 위험
- **고른 것**: (A)
- **근거**: 실측 없이 정하는 값이라, 안전 마진을 넉넉히 두는 쪽을 택함(사람 확인). 부하 테스트에서
  재조정.
- **영향**: `RoutingFailureBackoff`의 백오프 임계(3회 연속 실패)·상한(30초)은 그대로 재사용 —
  타임아웃이 늘어난 만큼 장애 시 개별 호출이 더 오래 걸리지만, 판단 로직 자체는 프로토콜 독립적이라
  손대지 않았다.

### 3. 경로 좌표(폴리라인) 수신 여부

- **물었던 것**: #421이 "고객에게 ETA만 보여준다"고 확정해 `Route.path`를 지금 아무도 안 쓰는데,
  카카오는 `summary=true`로 경로를 빼고 받을 수 있음(OSRM엔 그런 옵션이 없었음).
- **선택지**:
  - (A) 받는다(`summary` 미지정) — 응답이 커지지만, #422에서 경로선이 필요해질 때 이 클래스를
    다시 열지 않아도 됨. OSRM 시절 이미 이 판단을 한 번 했음(`DeliveryRouteEstimator` Javadoc).
  - (B) `summary=true`로 안 받는다 — 응답이 작아지고 파싱 코드도 줄지만, `Route.path`가 상시 빈
    필드가 되어 record 계약이 어색해지고, 나중에 좌표가 필요해지면 좌표 뒤집기 로직을 다시 쓰면서
    같은 실수(순서 오혼동)를 반복할 위험이 있음
- **고른 것**: (A)
- **근거**: 서버 내부 호출 비용은 이미 OSRM 호출 한 번으로 ETA와 함께 오던 것이라 (A)를 택해도
  API 호출 수·지연이 늘지 않음(사람 확인, #421 결정과 같은 논리를 그대로 계승).
- **영향**: `KakaoMobilityRoutingClient.pathOf()`가 `sections[].roads[].vertexes`(평탄 배열, x=경도)를
  파싱해 `Route.path`를 채운다. 지금은 호출자가 여전히 버리지만, #422에서 경로선이 필요해지면
  이 클래스를 다시 열 필요가 없다.

## 스스로 판단한 것

- **4xx/5xx 실패 판정을 OSRM과 반대로 잡음**: OSRM은 "경로 없음"(`NoRoute`/`NoSegment`)을 400으로
  줘서 4xx를 장애로 세지 않았다. 카카오는 반대로 "경로 없음"을 `HTTP 200 + result_code != 0`으로
  주고, 4xx(401 키 불량, 403 권한, 429 쿼터 초과)는 전부 진짜 장애다 — 특히 429는 호출당 과금 API를
  계속 두드리는 셈이라 물러서는 게 맞다고 판단해 `RoutingFailureBackoff`에 실패로 셌다. 근거:
  이슈 본문이 "카카오 응답의 성공/실패 구분 기준에 맞춰 조정"을 요구했고, 카카오 공식 문서에서
  `result_code` 필드와 별도의 HTTP 상태 체계가 명확히 구분됨을 확인함(공식 문서에 전체 `result_code`
  값 표는 없었으나 `0=성공`, 4xx는 인증/쿼터로 통상 쓰이는 것을 조사로 확인).
- **API 키를 빈 값으로 둘 때 기동을 막지 않음**: 비밀정보라 로컬·CI 상당수는 키 없이 돈다. 여기서
  기동을 막으면 라우팅과 무관한 작업까지 전부 멈춘다 — `routing.base-url` 미기동 시에도 정상 기동한
  #420의 원칙(`RoutingClientIntegrationTest`)을 그대로 이어받았다. 대신 기동 시 경고 로그를 한 번
  남겨, 배포 서버에서 키 등록을 빠뜨리면 "ETA가 조용히 안 나옴"이 아니라 로그로 드러나게 했다.
- **`priority=RECOMMEND`를 명시적으로 씀**: 카카오 기본값과 같지만, 기본값이 바뀌면 ETA가 조용히
  달라지는 값이라 코드에 명시해 뒀다. `TIME`(최단시간)이 아니라 추천 경로를 쓴 것은 라이더가 실제로
  탈 법한 경로가 ETA로도 맞다고 봤기 때문 — 별도로 사람 확인을 받지는 않았다(되돌리기 쉬운 값).
- **vertexes 파싱에서 짝 없는 마지막 값을 조용히 버림**: 카카오 응답이 항상 짝수 개 원소를 준다는
  보장 문서는 없었다. 홀수 개가 오면 인덱스 오류로 추적 API 전체가 500이 되는 것보다, 마지막 좌표
  하나를 버리고 경로선이 한 점 짧아지는 게 낫다고 판단함.

## 일부러 하지 않은 것

- **배포된 OSRM 서버(#416) 인프라 정리**: 이 이슈는 애플리케이션 코드만 다룬다. EC2 인스턴스에서
  OSRM 프로세스를 내리거나 보안그룹을 정리하는 것은 인프라 작업이라 범위 밖 — 별도 이슈 필요.
- **`result_code` 전체 값 표 문서화**: 카카오 공식 문서에서 `0=성공` 외의 개별 코드 의미(예: 104
  = 출발지·도착지 근접)를 완전히 확인하지 못했다. 코드는 "0이 아니면 경로 없음"으로만 처리해
  개별 사유를 구분하지 않는다 — `RoutingClient.findRoute` 계약 자체가 "빈 값 하나로 뭉갠다"고
  이미 확정돼 있어(#420) 이 범위에서는 문제되지 않는다.
- **실제 배포 카카오 API 호출 검증**: 과금 방지 원칙(사람 확인) 때문에 개발 중 실제 API를 한 번도
  부르지 않았다. 응답 스키마는 공식 문서 조사로만 확인했다 — #420이 OSRM PoC 컨테이너로 실물 검증을
  했던 것과 달리, 이번엔 그 검증 수단이 없다. 배포 후 실제 첫 호출에서 스키마가 다르면(공식 문서
  누락 가능성) 조용히 빈 값만 나가고 예외는 안 뜬다 — 로그(`ROUTING_CALL_FAILED`/`ROUTING_NO_ROUTE`)로
  확인해야 한다.
- **호출 한도·요금제 확정**: 카카오 공식 문서·데브톡 어디에도 구체적인 무료 쿼터 수치가 없었다
  ("쿼터 상향은 별도 협의"만 안내). 코드 쪽 방어(429 시 백오프)만 해 뒀고, 실제 한도는 콘솔에서
  확인이 필요하다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `KakaoMobilityRoutingClientTest` | 요청 형식(좌표 순서·`KakaoAK` 헤더·키 없으면 미호출), 정상 응답 파싱(초 단위 duration, 평탄 vertexes 뒤집기), `result_code != 0` 처리, 연결 실패·타임아웃·5xx·401·깨진 JSON 처리, **429를 장애로 세어 백오프가 걸리는지**(OSRM과 판정이 뒤집힌 지점의 회귀 방어), 응답 매핑 방어(null/빈 routes/summary 없음/짝 없는 vertex) |
| 통합 | `RoutingClientIntegrationTest` (기존 파일 수정) | 라우팅 서버·API 키 없이도 컨텍스트가 뜨고 `KakaoMobilityRoutingClient`가 주입되는지, 호출이 예외 없이 빈 값을 돌려주는지 |
| 통합/E2E | `DeliveryTrackingQueryServiceIntegrationTest`, `DeliveryRouteEstimatorTest`, `CustomerDeliveryTrackingE2ETest` (기존 파일, 무변경) | `RoutingClient` 구현체 교체가 상위 호출 경로(#421)를 깨지 않는지 회귀 확인 |

실행 결과:

```text
./gradlew test --tests '*KakaoMobilityRoutingClientTest' --tests '*RoutingFailureBackoffTest'
  → BUILD SUCCESSFUL (16개 신규 케이스 + 기존 백오프 7건, 전부 통과)

./gradlew test --tests '*RoutingClientIntegrationTest' --tests '*DeliveryTracking*' --tests '*DeliveryRouteEstimator*'
  → BUILD SUCCESSFUL (라우팅 통합 1건 + 추적 관련 기존 테스트 24건, 전부 통과)

./gradlew test (전체)
  → BUILD SUCCESSFUL (9분 37초, 실패 0)
```

### 검증하지 못한 것

- 실제 카카오 API 응답 스키마와의 일치 여부(위 「일부러 하지 않은 것」 참고). 배포 후 실제 응답으로
  확인이 필요하다.
- 429 응답의 실제 헤더 형식(예: `Retry-After`)은 확인하지 않았다 — 지금은 백오프 임계·상한이
  고정값이라 헤더를 읽지 않는다. 카카오가 `Retry-After`를 준다면 그 값을 반영하는 게 더 정확할 수
  있으나, 이번 이슈에서는 OSRM 시절 로직을 그대로 재사용하는 선에서 멈췄다.

## 새로 생긴 미결 사항

- **`result_code` 전체 표를 확인하지 못했다.** 코드는 "0이 아니면 실패"로만 다뤄 지금 동작에는
  문제가 없지만, 사유별 로그·지표가 필요해지면 공식 문서(또는 실제 응답 관찰)로 다시 확인해야 한다.
- **429 응답에 `Retry-After` 등 재시도 안내가 오는지, 온다면 고정 백오프 대신 그 값을 쓸지**가
  미결이다.
- **배포된 OSRM 인프라(#416) 정리 여부**가 미결이다 — 걷어낼지, 다른 용도로 재사용할지(예: 카카오
  장애 시 폴백) 별도 논의가 필요하다.
- **실제 카카오 콘솔의 쿼터·요금제 수치**를 이 작업에서 확인하지 못했다. 배포 전 콘솔에서 직접
  확인이 필요하다.

## 2026-08-10 재작업 — 카카오 전환 철회, OSRM 유지 + 러시아워 보정

- 이슈: [#431](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/431) (재사용, 제목·본문 정정)
- 브랜치: `feature/431-kakao-routing-client` (동일)

### 무엇이 바뀌었나

카카오모빌리티 길찾기 API를 실제로 쓸 수 없는 상황이 되어, 위 2026-08-09 작업을 되돌리고 방향을
바꿨다. `KakaoMobilityRoutingClient`를 삭제하고 git 이력에서 `OsrmRoutingClient`(#420 당시 버전)를
복원했다. 동시에 `RoutingClient` 계약을 `Optional<Route>`(duration/distanceMeters/path)에서
`Optional<Duration>`으로 좁혔다 — 유일한 소비자 `DeliveryRouteEstimator`가 duration만 쓰고 있었다
(grep으로 확인). `Route` 값 타입은 삭제했다.

OSRM은 자유흐름 기준이라 실시간 정체를 반영하지 않는다. 이를 보완하기 위해
`DeliveryRouteEstimator`에 러시아워 duration 배수 보정을 추가했다 — 평일 07-09시·18-20시
(Asia/Seoul)에 raw duration에 ×1.3을 곱한다.

### 사람이 고른 선택

#### 1. 이슈 #431 재사용 여부

- **물었던 것**: 카카오 전환이 무산됐을 때 새 이슈를 팔지, #431을 정정해서 재사용할지.
- **고른 것**: #431 재사용, 본문·제목 정정.
- **근거**: 문제(라우팅 클라이언트 구현체 선택)가 동일하고, 잘못된 "사람 확인" 기록을 바로잡는
  것 자체가 이 작업의 일부라 별도 이슈로 분리하면 정정 맥락이 흩어진다(사람 확인, 2026-08-10).
- **영향**: GitHub 이슈 #431 본문 상단에 정정 섹션 추가, 원문은 보존. 제목을
  "라우팅 클라이언트 OSRM 유지 + 러시아워 duration 보정 (카카오 전환 철회)"로 변경. 코멘트로
  정정 사실 공지.

#### 2. 러시아워 시간대와 배수

- **물었던 것**: 어느 시간대에, 얼마를 곱할지.
- **선택지**: 시간대 — 평일 출퇴근(07-09, 18-20시) / 출근만(07-09시) / 매일 동일 시간대(주말 포함).
  배수 — 1.2배(보수적) / 1.3배(균형) / 1.5배(공격적).
- **고른 것**: 평일 출퇴근(07-09, 18-20시), 1.3배.
- **근거**: 실측 데이터가 없는 상태의 잠정값. 가장 흔한 정의로 시작해 부하·실측 후 재조정하기로
  함(사람 확인, 2026-08-10).
- **영향**: `DeliveryRouteEstimator.RUSH_HOUR_MULTIPLIER`(1.3), `MORNING_RUSH_START/END`(07:00-09:00),
  `EVENING_RUSH_START/END`(18:00-20:00), `SEOUL`(`Asia/Seoul`, 서버 OS 타임존에 의존하지 않도록 명시).

#### 3. `RoutingClient` 계약을 `Duration` 만으로 좁힐지

- **물었던 것**: `Route`(duration/distance/path)를 유지할지, `Duration`만 남길지. 카카오 전환
  때는 "라우팅 호출 한 번에 같이 오니 좁혀도 아끼는 게 없다"는 논리로 `Route`를 유지했었다.
- **선택지**: (A) `Duration`으로 축소 (B) `Route` 유지, 값만 안 씀.
- **고른 것**: (A).
- **근거**: 실제 소비자(`DeliveryRouteEstimator`)를 grep으로 확인한 결과 `distanceMeters`·`path`
  호출자가 코드베이스 어디에도 없었다. OSRM은 `overview=false`로 경로 좌표 자체를 요청하지 않을
  수 있어(카카오에는 없던 옵션), 응답 파싱 코드도 함께 줄어든다(사람 확인, 2026-08-10).
- **영향**: `Route.java` 삭제. `RoutingClient.findRoute()` 반환 타입이
  `Optional<Route>` → `Optional<Duration>`으로 바뀌어 `DeliveryTrackingQueryService.arrivalAt()`도
  같이 수정됨.

### 스스로 판단한 것

- **타임아웃을 OSRM #420 당시 값(연결 300ms/읽기 700ms)으로 그대로 복원**: 카카오 전환 때 1s/2s로
  늘렸던 이유(공인망 HTTPS TLS 핸드셰이크)가 OSRM에는 해당하지 않는다 — VPC 내부 co-locate
  배포(#416)이고 #407 PoC 실측이 10ms 내외였다는 근거가 그대로 유효하다.
- **러시아워 판정을 `Asia/Seoul` 명시 타임존으로 계산**: 서버 OS/컨테이너 기본 타임존에 맡기면
  배포 환경에 따라 조용히 틀려질 수 있다(예: 컨테이너가 UTC 기본이면 러시아워가 9시간 밀린다).
  프로젝트에 기존 `Clock` 빈 주입 관례가 없어 새로 만들지 않고, `targetOf()`와 같은 패턴으로
  `applyRushHourMultiplier(Duration, LocalDateTime)`을 정적 순수 함수로 분리해 스프링 없이
  테스트 가능하게 했다.
- **`DeliveryTrackingQueryServiceIntegrationTest`의 ETA 시간창 검증을 완화**: 러시아워 보정이
  실제 시각(테스트 실행 시각)에 따라 걸릴 수 있어, 기존 "정확히 +420초" 검증이 그 시각대에
  테스트를 돌리면 깨지는 잠재적 플레이키였다. 상한을 +546초(420×1.3)까지 넓혀 실행 시각과
  무관하게 통과하도록 했다.

### 일부러 하지 않은 것

- **`Clock` 빈 도입**: 러시아워 판정을 테스트하기 쉽게 하려면 유용하지만, 이 저장소에 그런
  관례가 전혀 없어(grep 결과 0건) 이 이슈 하나를 위해 새 패턴을 들이는 것은 과하다고 판단했다.
  대신 정적 순수 함수 분리로 같은 효과를 냈다.
- **배포된 OSRM 인프라(#416) 사이징 재검토**: 코드가 다시 OSRM을 부르게 됐다는 사실만 반영했고,
  실제 트래픽에 사이징이 맞는지는 이 이슈 범위 밖이다.

### 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `OsrmRoutingClientTest` | 좌표 순서·`overview=false`, duration 파싱, 경로없음(200/4xx)이 백오프에 안 걸리는지, 연결 실패, 5xx 연속 실패 시 백오프 |
| 단위 | `DeliveryRouteEstimatorTest` | 상태별 목적지 선택(기존), 러시아워 배수 적용/미적용/주말 제외/경계값(정적 함수 직접 테스트) |
| 통합 | `RoutingClientIntegrationTest` | 라우팅 서버 없이도 컨텍스트 기동, 구현체가 `OsrmRoutingClient`로 배선됨 |
| 통합 | `DeliveryTrackingQueryServiceIntegrationTest` | ETA 계산 경로 회귀(시간창 완화) |
| E2E | `CustomerDeliveryTrackingE2ETest` | 무변경, 회귀 확인 |

실행 결과:

```text
./gradlew test --tests '*OsrmRoutingClientTest' --tests '*RoutingFailureBackoffTest' \
  --tests '*RoutingClientIntegrationTest' --tests '*DeliveryRouteEstimatorTest' \
  --tests '*DeliveryTrackingQueryServiceIntegrationTest' --tests '*CustomerDeliveryTrackingE2ETest'
  → BUILD SUCCESSFUL, 38 tests, 0 failures (로컬 Docker MySQL/Redis 기동 후)

./gradlew test (전체)
  → BUILD SUCCESSFUL, 608 tests, 0 failures
```

### 새로 생긴 미결 사항

- 러시아워 배수(1.3)·시간대(07-09, 18-20시)가 실측 없는 잠정값이다 — 실제 배송 데이터로 재조정 필요.
- 배포된 OSRM 서버(#416)가 계속 쓰이게 됐으니, 사이징이 지금 트래픽에 맞는지 별도 확인 필요.
- **"사람 확인" 태그를 실제 확인 없이 기록하는 문제가 이 이슈에서 실제로 발생했다** — 2026-08-09
  작업의 카카오 승인 관련 "사람 확인"이 근거 없이 기록됐고, 그대로 이슈 본문·CLAUDE.md·워크로그·
  코드 Javadoc 네 곳에 퍼진 뒤에야 발견됐다. 이 태그를 쓸 때는 실제로 그 세션에서 사람이 확인한
  내용인지 다시 확인할 것.
