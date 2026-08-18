# 라이더 콜 목록 정렬·필터 백엔드 정리 작업 기록

- 이슈: [#522](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/522)
- 브랜치: `feature/522-rider-request-sort-filter-cleanup`
- 범위: backend (+ 프론트 itemType 요청 파라미터 전환)
- 작성일: 2026-08-14

## 무엇을 만들었나

`#509`(페이지네이션) 작업 중 나눈 대화에서 확정된 세 가지를 `GET /api/rider/requests` 계약에 반영했다:
1. 정렬 기준에 `DELIVERY_DISTANCE`(픽업→도착지 배송거리, 오름차순) 추가.
2. 물품 종류(itemType) 필터를 서버 파라미터로 이관 — 지금까지 프론트 클라이언트 필터
   (`filterRequestsByItem`)로만 있던 것을 제거하고 요청 파라미터로 전환.
3. `sortDirection` 요청 파라미터를 완전히 제거 — 방향은 정렬 기준별 고정값(FARE만 내림차순,
   나머지는 오름차순)으로만 동작.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/rider/requests` | `sort`에 `DELIVERY_DISTANCE` 추가, `itemType` 필터 추가, `sortDirection` 파라미터 제거, 커서에 `afterDeliveryDistanceMeters` 추가 | 400(정렬 기준·커서 불일치 등 기존과 동일) |

### 화면

`rider/_authed/requests/index.tsx` — itemType을 더 이상 클라이언트에서 거르지 않고 요청
파라미터(`itemType`, `'ALL'`이면 생략)로 보낸다. 반경·좌표와 마찬가지로 물품 종류가 바뀌면
커서·누적 목록을 초기화하고 첫 페이지부터 다시 쌓는다. 정렬 기준 선택 UI(3개 프리셋)는 이번
범위가 아니다 — 아래 "일부러 하지 않은 것" 참고.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. itemType 필터 — 단일 선택 vs 다중 선택

- **물었던 것**: 지금 화면(단일 select + 'ALL')과 똑같이 단일 선택만 지원할지, 여러 물품 종류를
  동시에 볼 수 있게 다중 선택을 지원할지.
- **선택지**:
  - (A) 단일 선택 — 지금 화면과 정확히 일치, 파라미터 하나(optional enum), 구현 단순.
  - (B) 다중 선택 — 유연하지만 지금 화면에 그런 요구가 없고, 콤마 구분 파싱 + SQL/자바 `IN`절
    처리로 구현이 커진다.
- **고른 것**: (A)
- **근거**: 지금 화면 요구를 넘어서는 유연성을 미리 만들 이유가 없다는 판단.
- **영향**: `RiderDeliveryRequestFilter.itemType()`은 단일 `ItemType`이고, 필터 판정
  (`withinItemType`)은 단순 동등 비교다. 다중 선택이 필요해지면 이 필드 타입부터 다시 열어야 한다.

### 2. `sortDirection` 제거 범위

- **물었던 것**: 요청 파라미터만 없애고 서비스 내부에 오버라이드 능력(메서드 시그니처)은 남겨둘지,
  파라미터+오버라이드 능력을 통째로 없앨지.
- **선택지**:
  - (A) 통째로 제거 — API 표면·검증 비용 최소화, 안 쓰는 테스트(`shouldOverrideDefaultDirection`)도
    함께 삭제. 나중에 다시 필요해지면 git 히스토리에서 복원.
  - (B) 파라미터만 제거, 내부 오버라이드는 유지 — 나중에 파라미터만 다시 노출하기 조금 더 쉽지만,
    지금 아무도 안 쓰는 분기를 계속 유지·검증해야 한다.
- **고른 것**: (A)
- **근거**: 라이더가 실제로 고를 정렬 프리셋 3개(가까운순/비싼순/짧은순)가 전부 방향까지 고정이라,
  오버라이드가 영영 필요 없어졌다는 판단.
- **영향**: `SortDirection.from(String)`(파싱 메서드) 삭제, 서비스 메서드 시그니처에서
  `sortDirectionParam` 제거, `defaultDirectionFor(effectiveSort)`를 항상 사용. 기존
  `shouldOverrideDefaultDirection` 단위 테스트 삭제.

## 스스로 판단한 것

- **커서 필드 이름을 `afterDeliveryDistanceMeters`로 새로 만듦(`afterDistanceMeters` 재사용
  안 함)**: `afterDistanceMeters`는 이미 "라이더→픽업지 거리"라는 뜻으로 고정돼 있다(#509 논의
  중 "이름이 모호하다"고 짚었던 바로 그 필드). 배송거리(픽업→도착지)용 커서를 같은 이름으로
  겹쳐 쓰면 다시 같은 혼란이 생기므로, 새 이름을 만들었다.
- **`DeliveryRequestSort.DELIVERY_DISTANCE`의 기본 방향을 ASC로 묶음**: 기존 관례(FARE만
  DESC, 나머지는 ASC)를 그대로 확장한 것 — "짧은 배송거리가 먼저"가 자연스러운 기본값이라
  새로 고민할 지점이 아니었다.
- **`keysetPaginationSurvivesConcurrentInsertBetweenPages` 통합 테스트를 FARE 오름차순에서
  내림차순 시나리오로 다시 씀**: 이 테스트는 원래 `sortDirection=ASC` 오버라이드로 FARE를
  오름차순으로 만들어 검증했는데, 오버라이드 자체가 없어져 더 이상 그 경로를 못 탄다. FARE의
  유일한(고정) 방향이 DESC이므로, 같은 "페이지 사이 삽입에도 keyset이 중복 없이 동작하는지"
  검증을 내림차순 기준으로 값·기대 순서만 바꿔 유지했다.
- **`FARE` DESC·`DELIVERY_DISTANCE` ASC + keyset 커서 조합 테스트를 새로 추가함**: `#509` 작업
  중 "이 조합을 검증하는 테스트가 없다"고 확인했던 gap을 여기서 메웠다(단위 테스트
  `shouldPaginateFareDescendingWithCursor`, `shouldPaginateDeliveryDistanceAscendingWithCursor`).
  `comparatorFor`/`isAfterCursor`가 "주 키만 방향 따라 뒤집고 tiebreaker(`deliveryId`)는 항상
  오름차순"이라는 같은 규칙을 공유해야 한다는 게 코드 추적으로는 맞아 보였는데, 이 테스트로
  실측 확인함(통과).
- **E2E에 itemType·DELIVERY_DISTANCE 케이스를 각 1개씩 추가**: 단위/통합 테스트는 서비스
  메서드를 직접 부르지만, `itemType`을 `@RequestParam ItemType itemType`으로 스프링 기본
  enum 바인딩에 맡겼기 때문에(다른 기존 파라미터들의 관례를 그대로 따름 — `OrderStatus`,
  `PointTransactionType`, `ProofType` 전부 커스텀 파싱 없이 이 방식) 실제 HTTP 계층에서
  바인딩이 되는지는 E2E가 아니면 못 잡는다.
- **Orval 재생성 시 `rider-delivery.ts`(배송 완료 API)에도 무관한 diff가 같이 나옴**: `dev`에
  이미 병합된 배송 완료 인증사진 presigned URL 업로드 기능(`6171536`)의 프론트 생성물이
  그동안 재생성 안 된 채 뒤처져 있었다(이 이슈와 무관, 브랜치 분기 전부터 있던 드리프트 — 내
  브랜치와 `dev`의 해당 백엔드 컨트롤러 파일은 diff 0으로 확인함). 생성물은 손으로 일부만
  골라내지 않는다는 원칙("자동 생성물, 수정 금지")이라 통째로 다시 만들 수밖에 없었고, 사람
  확인 후 이번 커밋에 함께 포함하기로 함.

## 일부러 하지 않은 것

- **정렬 기준 선택 UI(3개 프리셋: 가까운순/비싼순/짧은순)**: `#510`의 범위다. 이번 이슈는
  백엔드 계약과 itemType 프론트 연동까지만 다룬다.
- **itemType 다중 선택**: 위 "사람이 고른 선택 1번" 참고. 필요해지면 별도 이슈.
- **`radiusMeters` 상한 도입**: CLAUDE.md에 이미 남아 있는 별개 미결 사안이라 손대지 않았다.
- **`#510` 이슈 본문 갱신**: `#510`은 `sortDirection` 토글 UI를 만들자는 전제로 쓰여 있는데,
  그 파라미터 자체가 이제 없다. `#510` 착수 전에 본문을 이번 결정(3개 프리셋, itemType은 이미
  서버 필터, DELIVERY_DISTANCE 추가됨)에 맞게 고쳐야 한다 — 이번 작업에서는 안 건드림.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderDeliveryRequestServiceTest.FilterSortPaginationTest` | itemType 필터 제외, FARE 내림차순(고정)+커서, DELIVERY_DISTANCE 오름차순+커서, 커서-정렬기준 불일치(DELIVERY_DISTANCE) 거부 |
| 통합 | `RiderDeliveryRequestServiceIntegrationTest` | 운임·배송거리 필터 실제 DB 검증(파라미터 순서만 갱신), FARE 내림차순 keyset이 페이지 사이 삽입에도 중복 없이 동작 |
| E2E | `RiderDeliveryRequestE2ETest` | itemType 필터 실제 HTTP 바인딩·필터링, sort=DELIVERY_DISTANCE 실제 HTTP 정렬 |

실행 결과:

```text
./gradlew test → BUILD SUCCESSFUL, 669 tests completed, 0 failed
cd frontend && pnpm typecheck → 오류 없음
cd frontend && pnpm test → Test Files 28 passed (28), Tests 220 passed (220)
cd frontend && pnpm build → 빌드 성공
```

### 검증하지 못한 것

- 프론트에서 itemType이 실제로 요청 파라미터로 나가는지는 사용자가 직접 브라우저 Network
  탭으로 확인하기로 했고(타입체크·빌드·백엔드 E2E로 계약 자체는 검증됨), 이 대화 안에서
  스크린샷 등으로 재확인하지는 않았다.
- 정렬 기준 선택 UI가 아직 없어(`#510` 범위), `DELIVERY_DISTANCE`·`itemType` 조합이 실제
  화면에서 사용자 시나리오로 동작하는 모습은 `#510` 완료 후에나 확인 가능하다.

## 새로 생긴 미결 사항

- `#510` 이슈 본문이 지금 계약과 안 맞는다(위 "일부러 하지 않은 것" 참고) — 착수 전 갱신 필요.
