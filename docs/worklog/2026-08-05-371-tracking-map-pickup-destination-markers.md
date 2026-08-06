# 주문 추적 화면 픽업·도착지 지도 마커 작업 기록

- 이슈: [#371](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/371)
- 브랜치: `feature/371-tracking-map-pickup-destination-markers`
- 범위: frontend
- 작성일: 2026-08-05

## 무엇을 만들었나

고객 추적 화면(`/customer/deliveries/$deliveryId/tracking`)의 지도 영역이 라이더가 배차되어
실시간 위치가 오는 상태(`isTrackable`)일 때만 뜨고, 그 전(`WAITING`)과 종료 후
(`COMPLETED`/`CANCELED`)는 회색 placeholder 텍스트만 보여주던 것을 고쳤다. 이제 상태와 무관하게
픽업·도착지 좌표가 있으면 항상 지도에 마커 2개 + 경로선(폴리라인)을 그린다. 라이더가 배차되면
같은 지도 위에 실시간 위치 마커가 추가로 뜬다.

새 백엔드 작업은 없다 — 픽업·도착지 좌표(`DeliveryDetailResponse.pickup/destination`)는
`useGetDelivery`가 이미 조회하고 있었고, 두 좌표를 마커+경로선으로 그리는 로직도 라이더용 콜
상세 화면(`RequestRouteMap.tsx`)에 이미 있어 같은 패턴을 그대로 옮겨왔다.

### API

해당 없음. 기존 `GET /api/customer/deliveries/{deliveryId}` 응답의 `pickup`/`destination`
좌표를 화면에서 처음으로 실제 사용했을 뿐이다.

### 화면

- `TrackingMap`(`.../$deliveryId/-components/TrackingMap.tsx`)에 `pickup`/`destination`,
  `isTrackable` prop을 추가해 정적 마커+경로선을 그리는 effect를 새로 넣었다.
- `tracking.tsx`의 `isTrackable ? <TrackingMap/> : <placeholder>` 분기를 없애고 항상
  `TrackingMap`을 렌더링한다.
- **(브라우저 검증 중 발견해 추가로 고침) `tracking.tsx`가 `useGetDeliveryTracking`을 완전히
  버리고 `useGetDelivery`만으로 렌더링하도록 바꿨다.** 아래 "브라우저 검증에서 드러난 것" 참고 —
  `useGetDeliveryTracking`은 WAITING/COMPLETED/CANCELED에서 항상 409였고, 화면은 그 에러를
  전체 화면 에러 카드로 처리하고 있어서 마커 코드까지 도달하지 못했다.
- 픽업·도착지 마커에 `kakao.maps.MarkerImage`로 색을 입혀 구분했다(파란색/빨간색, 화면 하단
  주문 상세의 색상과 동일).
- 마커 두 개가 항상 화면에 다 들어오도록 `map.setBounds()`(패딩 포함)로 바꿨다 — 아래 참고.
- 지도 컨테이너에 `isolate`를 추가했다 — 아래 참고.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

계획 단계(Plan mode)에서 두 가지를 확인받았다.

### 1. 경로선(폴리라인) 포함 여부

- **물었던 것**: 요청 문구("마커로 표시")대로 마커 2개만 그릴지, 라이더 화면(`RequestRouteMap`)처럼
  둘을 잇는 경로선까지 그릴지.
- **고른 것**: 포함.
- **근거**: `RequestRouteMap`을 그대로 재사용하면 추가 코드 없이 따라오고, 두 지점의 관계를
  시각적으로 더 분명하게 보여준다.

### 2. 적용 범위(WAITING만 vs 전체 상태)

- **물었던 것**: "주문 생성 완료" 문구에 맞춰 `WAITING`에만 지도를 띄울지, `COMPLETED`·`CANCELED`
  까지 포함해 `isTrackable` 분기 자체를 없앨지.
- **고른 것**: 전체 상태 통일.
- **근거**: 셋 다 같은 `!isTrackable` 분기를 타고 있어 하나만 골라내면 분기가 늘어난다. 완료·취소된
  주문도 경로를 볼 수 있는 쪽이 더 자연스럽고, 기존 placeholder 텍스트("배차를 기다리고 있어요" 등)는
  화면 하단 헤드라인(`HEADLINE_BY_STATUS`)에 이미 같은 정보가 있어 지워도 정보 손실이 없다.

## 스스로 판단한 것

- **`RequestRouteMap`을 옮기지 않고 `TrackingMap`을 확장**: `RequestRouteMap`은 정적 마커
  전용이라 로직이 거의 그대로 재사용 가능했지만, 실시간 라이더 마커와 같은 지도 인스턴스 위에
  같이 떠야 해서 두 지도를 나란히 둘 수 없었다. 파일을 옮겨 공용화하는 대신(사용처가 2곳뿐이라
  아직 그 정도 추상화가 필요하지 않다고 판단) `TrackingMap`에 마커+경로선 effect를 추가했다.
- **`mapReady` state 추가**: 기존 라이더 위치 effect는 `location`이 SSE로 늦게(지도 로드보다
  한참 뒤) 들어온다는 전제로 `mapRef.current` null 체크만으로 경쟁을 사실상 피해가고 있었다.
  하지만 픽업·도착지 좌표는 `useGetDelivery`가 마운트 즉시 요청해 카카오맵 SDK 스크립트 로드보다
  먼저 끝날 수도 있다 — 그 경우 지도가 아직 없어 마커가 영영 안 그려진다. `loadKakaoMaps()`가
  끝나는 시점에 `mapReady`를 true로 세팅하고 두 effect의 의존성 배열에 추가해 경쟁을 없앴다.
- **경로선 effect를 `location`에 의존시키지 않음**: 처음엔 라이더 위치가 없을 때만 픽업·도착지
  중간점으로 센터링하려고 `location`을 이 effect의 의존성에 넣었는데, 그러면 라이더 위치가 5초
  주기로 갱신될 때마다 마커·경로선을 지우고 다시 그리게 된다. 센터링을 마커 생성 시점에 한 번만
  하도록 분리해 `location`을 의존성에서 뺐다 — 라이더 위치가 들어오면 기존 `panTo` effect가
  자연스럽게 지도를 따라가므로 충돌하지 않는다.
- **"라이더 위치를 기다리는 중입니다" 오버레이에 `isTrackable` 조건 추가**: 이 문구는 원래
  `isTrackable`일 때만 렌더링되던 컴포넌트 안에 있어 `!location`만으로 충분했다. 이제
  `WAITING`(라이더 자체가 없음)에서도 이 컴포넌트가 렌더링되므로, `isTrackable`이 아닐 때는
  이 문구가 뜨지 않도록 막았다 — 그렇지 않으면 배차 전인데 "라이더를 기다린다"는 오해를 준다.
- **`kakao-maps.d.ts`에 `setLevel` 추가**: 이 타입 선언 파일은 "실제로 쓰는 만큼만" 선언하는
  방침이라(`RequestRouteMap`은 지도 생성 시 `level`만 넘기고 이후 바꾼 적이 없어 없었음) 경로선
  센터링에 필요한 `setLevel`이 빠져 있었다. 실제 카카오맵 SDK에 있는 메서드라 그대로 추가했다.
- **좌표 없는 경우 에러 배너를 추가하지 않음**: `RequestRouteMap`은 좌표가 없으면 안내 문구를
  보여주지만, `TrackingMap`은 조용히 마커만 생략한다. 주문 생성 시 `AddressRequest`가 위경도를
  필수로 받아 정상 흐름에서는 발생하지 않는 경로라 방어 코드를 추가하지 않았다.
- **픽업·도착지 마커 색상 구분(추가 요청)**: 두 마커가 기본 이미지로 동일해 구분이 안 된다는
  피드백을 받아 `kakao.maps.MarkerImage`로 커스텀 핀을 만들었다. 외부 아이콘 CDN에 의존하지
  않고 인라인 SVG를 base64 data URI로 인코딩해 썼다 — 새 이미지 에셋을 커밋하지 않아도 되고,
  카카오 문서 예제가 쓰는 외부 PNG보다 이 프로젝트의 "외부 플레이스홀더 이미지 커밋 금지" 관례에
  더 맞는다고 판단했다. 색상은 화면 하단 주문 상세에서 이미 쓰던 픽업(파란색 `#3B82F6`)·도착지
  (빨간색 `#EF4444`) 구분을 그대로 재사용했다. `kakao-maps.d.ts`에 `MarkerImage`/`Size`/`Point`
  타입을 추가했다(같은 최소 선언 방침).

## 브라우저 검증에서 드러난 것

계획 단계에서는 코드만 읽고 "`isTrackable` 분기를 없애면 WAITING/COMPLETED/CANCELED도 정상
렌더링된다"고 가정했다. 실제로 로컬 백엔드를 띄우고 계정 가입 → 배송요청 생성 → 추적 화면
진입까지 브라우저로 직접 확인하면서 이 가정이 틀렸다는 걸 발견했다.

### 1. `useGetDeliveryTracking`이 WAITING/COMPLETED/CANCELED에 항상 409를 반환한다

`DeliveryTrackingAccessService.authorizeTracking`(`backend/.../order/service/`)는 SSE 구독과
같은 게이트를 쓴다 — `OrderStatus.isTrackable()`이 아닌 상태는 무조건 409(코드에도
`// FIXME: 웨이팅도 이 화면 보고 싶어요` 라는 기존 주석이 있다). `tracking.tsx`는
`trackingQuery.isError`를 전체 화면 에러 카드("배송 정보를 불러오지 못했습니다…")로 처리하고
있어서, WAITING 주문을 막 만들고 이 화면에 들어가면 **지도 영역에 도달하기도 전에 에러 화면이
떴다.** 코드에 있던 `tracking.status === 'WAITING'` 같은 분기는 사실상 죽은 코드였다(그 값을
담은 응답이 성공하는 경우가 없었다).

`DeliveryDetailResponse`(`useGetDelivery`, 게이트 없음)가 `status`/`steps`/`riderName`/
`riderPhoneNumber`/`fare` 등 `tracking`이 주던 값을 전부 가지고 있어서(`estimatedArrivalAt`
제외 — 문서에 이미 "산정 근거 없어 항상 null"이라고 적혀 있던 필드라 잃을 것이 없었다),
`useGetDeliveryTracking` 호출 자체를 지우고 `detailQuery` 하나로 통합했다. `isTrackable`도
`detail?.status`에서 뽑는다. 결과적으로 요청 수가 하나 줄고, 이 화면이 상태와 무관하게 항상
뜨게 됐다 — 원래 이슈가 요구하던 "상태 무관 렌더링"이 이 수정 없이는 애초에 불가능했다.

**백엔드 쪽 `authorizeTracking`의 409 게이트 자체는 SSE 구독 판정으로는 여전히 올바르다
(WAITING인 주문에 실시간 위치 스트림을 열어줄 이유가 없다) — 고치지 않았다.** 프론트가 이
엔드포인트에 상태 표시까지 의존하고 있던 것이 문제였을 뿐이다.

### 2. 고정 zoom level로는 먼 거리 픽업·도착지가 화면 밖으로 나간다

`level: 7` + 중간점 센터링으로 첫 구현을 했는데, 실제 두 지점(예: 서울시청↔강남, 직선거리
8.8km)으로 확인해보니 지도 영역이 `h-64`(256px, 넓고 낮은 모바일 카드 비율)라 두 마커가 위아래로
화면 밖에 걸렸다. `kakao.maps.LatLngBounds` + `map.setBounds(bounds, 40, 40, 60, 40)`로 바꿔
컨테이너 크기·비율에 맞게 자동으로 줌·중심을 잡도록 했다(패딩은 핀 이미지 높이·하단 라벨과
안 겹치게). `kakao-maps.d.ts`에 `LatLngBounds`, `Map.setBounds`를 추가했다.

### 3. 지도 내부 레이어가 지도 위 오버레이(안내 문구·라벨)를 가려버린다

지도 컨테이너(`containerRef`) div에 `position`을 안 줬더니(그냥 `w-full h-full`), 카카오맵이
내부적으로 z-index를 준 레이어(폴리라인 SVG 등)가 이 컨테이너의 포지셔닝 컨텍스트를 뚫고 나가
같은 부모 안의 형제 엘리먼트("픽업 · 도착 위치" 라벨, "라이더 위치를 기다리는 중입니다" 문구)를
덮어버렸다(`document.elementFromPoint`로 실측 확인). 지도 컨테이너에 `isolate`(CSS
`isolation: isolate`)를 추가해 새 스태킹 컨텍스트를 만들어 카카오 내부 레이어를 그 안에 가뒀다 —
서드파티 위젯을 끼워 넣을 때 흔한 문제라 앞으로 새 지도 컴포넌트를 추가할 때도 같이 붙이는 게 안전하다.

### 검증 방법

로컬 DB에 요금 정책이 `INACTIVE`(주문 생성 자체가 항상 막혀 있었음 — 로컬 개발 첫 셋업에서
누구든 겪을 문제로 보여 `ACTIVE`로 바꿔 두었다)만 있어 활성화하고, 테스트 고객·라이더 계정을
API로 만들어 실제 주문 생성 → (자동 취소 타임아웃 확인용) 방치 → 라이더 배차까지 실제로
수행한 뒤 Playwright 헤드리스 브라우저로 스크린샷을 찍어 확인했다. 이 과정에서 팀의 부하테스트용
도커 컨테이너(`backend-app-1~3`, 포트 8080-8082)가 dev 최신 마이그레이션(v18)보다 오래된
이미지(v17)였던 것도 같이 발견했다(주문 생성 API가 200/201을 반환하면서도 본문이 비어 있고 DB에
아무것도 안 쓰이는 이상 증상으로 드러남 — 재현·원인 파악에 시간을 많이 씀). 확인 후 로컬
`./gradlew bootRun`으로 전환해 검증했고, 끝나고 도커 컨테이너를 원래대로
(`docker compose --profile app up -d --scale app=3`) 복구했다. **포트 배정이 8080-8089 범위
안에서 재배정돼(`app-1→8089, app-2→8081, app-3→8080`) 원래와 정확히 같은 매핑은 아니다** —
포트 범위 바인딩이라 `docker start`/`up`마다 남는 포트 중에서 할당되는 것으로 보인다. 특정
인스턴스를 특정 포트로 겨냥하는 로드테스트 스크립트가 있다면 확인 필요.

## 일부러 하지 않은 것

- **단위 테스트 미작성**: `TrackingMap`/`RequestRouteMap` 둘 다 카카오 SDK를 직접 다루는 명령형
  코드라 기존에도 테스트가 없다. 이번 변경도 같은 관례를 따랐다.
- **`/customer/deliveries/$deliveryId/`(지도 없는 순수 텍스트 상세 화면)는 손대지 않음**: 이슈
  범위가 "주문 생성 완료 후" 화면(= tracking 화면)으로 한정돼 있어, 별도 화면인 주문 상세
  페이지는 이번 변경에 포함하지 않았다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| - | - | 신규 테스트 없음(위 「일부러 하지 않은 것」 참고) |

실행 결과:

```text
pnpm typecheck → 통과
pnpm build → 통과
```

브라우저 수동 확인(Playwright 헤드리스, 위 "브라우저 검증에서 드러난 것" 참고):

- WAITING(막 생성): 픽업(파란)·도착지(빨간) 마커 + 경로선 + "픽업 · 도착 위치" 라벨 표시 확인.
- ASSIGNED(라이더 배차 후): 위 마커에 더해 헤드라인 "라이더가 배정됐어요", 라이더 이름·전화번호,
  "실시간 연결됨" SSE 상태 표시 확인.
- CANCELED(배차 대기 타임아웃 자동취소, `DeliveryTimeoutService`): 헤드라인 "배송이 취소됐어요"와
  함께 마커가 여전히 뜨는 것 확인 — 취소된 주문도 경로를 볼 수 있다는 계획대로 동작.
- 콘솔 에러 없음.

### 검증하지 못한 것

- COMPLETED 상태는 실제로 만들어보지 않았다(픽업 인증까지 라이더 플로우를 다 태워야 해서 시간상
  생략) — WAITING/CANCELED와 같은 코드 경로(`!isTrackable` 분기 없음)라 위험은 낮다고 판단.
- MOVING_TO_PICKUP/PICKED_UP/DELIVERING(라이더 위치가 실제로 갱신되며 지도가 따라가는지)은
  마커 확인 목적상 생략했다 — 라이더 위치 relay 로직 자체는 이번에 건드리지 않았다.

## 새로 생긴 미결 사항

- **`DeliveryTrackingAccessService`의 `// FIXME: 웨이팅도 이 화면 보고 싶어요` 코멘트가 이번
  프론트 수정으로 실질적으로 해소됐다.** 화면은 이제 `useGetDeliveryTracking`에 의존하지 않아
  WAITING도 정상적으로 보여준다. 다만 그 백엔드 엔드포인트·FIXME 코멘트 자체는 지우지 않았다 —
  SSE 구독 판정으로는 여전히 유효한 게이트이고, 이번 이슈 범위(프론트) 밖이라 백엔드 담당자
  판단이 필요하다. 코멘트를 지울지, 아니면 "화면 표시용 조회와 SSE 구독 판정을 같은 API로 묻는
  게 처음부터 잘못이었다"는 설명을 남길지 별도 확인 필요.
- 로컬 부하테스트 도커 컨테이너(`backend-app-1~3`)가 dev 브랜치보다 오래된 이미지(마이그레이션
  v17)로 떠 있었다 — `docker compose --profile app up -d --build --scale app=3`로 재빌드하지
  않으면 이후 로컬 검증에서 같은 "성공 응답인데 본문이 비고 DB에 안 남는" 증상이 재발할 수 있다.
