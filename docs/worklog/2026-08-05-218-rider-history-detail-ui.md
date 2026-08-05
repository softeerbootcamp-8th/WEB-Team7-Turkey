# 운행 기록 상세 화면 API 연동 작업 기록

- 이슈: [#218](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/218) (FE-RIDE-HIST-002, 프론트)
- 브랜치: `feature/218-rider-history-detail-ui` (**`feature/71` 위 스택** — 아래 선택 2)
- 범위: frontend
- 작성일: 2026-08-05
- 상태: **완료** — #379가 dev에 머지된 뒤 feature/218을 dev 위로 rebase해 전체 빌드 green (typecheck·test·build 통과)

## 무엇을 만들었나

라이더 운행 상세 화면(`rider/_authed/history/$deliveryId/index.tsx`)의 정적 시안을 #71 상세 조회 API
(`GET /api/rider/history/{deliveryId}`, 훅 `useGetDeliveryHistoryDetail`)에 연결했다. `$deliveryId`를
읽어 조회하고, 경로·물품·상태 이력·정산 내역 네 구역을 실제 응답으로 바인딩했으며, 로딩·404·에러·
잘못된 id 상태를 처리했다. 순수 변환/포맷/에러 함수는 `-riderHistoryDetail.ts`로 떼어 vitest로 검증했다.

### 화면

- 라우트 `rider/_authed/history/$deliveryId/`(가드 하위, `_authed`) — `useGetDeliveryHistoryDetail` 소비.
- 어댑터 없이 화면에서 훅 직접 소비(상세 화면 관례, `requests/$deliveryId`와 동일). 순수 함수는
  `-riderHistoryDetail.ts`(라벨/포맷/타임라인/에러) + `-riderHistoryDetail.test.ts`.
- 바인딩: 출발/도착 도로명·상세주소, 이동거리, 물품 종류 라벨, 상태 이력 타임라인(steps[]),
  정산 내역(기본운임·거리운임·물품할증·확정운임 + 최종 정산 금액·정산 시각).
- 예외: 404 전용 문구(목록 안내) + `role="alert"` 재시도, 로딩 스피너(`aria-live`), 잘못된 id 안내,
  상태 이력 비면 안내 문구. 401은 공용 인터셉터 위임(#195).

### 스키마 변경

해당 없음. `pnpm generate:api`로 생성물만 갱신(`rider-history.ts`에 상세 훅, `*.schemas.ts`에 상세 타입).

## 사람이 고른 선택

### 1. "정산 대기" 표시 — 생략 + 근거 명시

- **물었던 것**: 이슈가 요구한 "정산 미생성 완료 배송 → '정산 대기' 표시"를 구현할지.
- **선택지**:
  - (A) 생략 + 근거 명시 — 현재 백엔드에선 발생 불가. / 이슈 요구 항목을 형식상 뺌.
  - (B) 방어적 UI — settlementAmount 없을 때 '정산 대기'. / 트리거되지 않는 죽은 분기.
- **고른 것**: (A) 생략.
- **근거**: #71이 완료 배송에 정산이 없으면 200+null이 아니라 **500**을 낸다 — 정산은 배송 완료
  트랜잭션에서 원자적으로 생성되어 COMPLETED엔 항상 존재한다. 그래서 상세 API가 "정산 없는 완료
  배송"을 200으로 주는 경로 자체가 없고, `settlementAmount`도 non-null이라 "정산 대기"를 그릴 신호가 없다.
- **영향**: 정산이 완료와 분리되는 시점에 백엔드(200+null)와 함께 화면 분기를 추가한다. 그 전까지는 미구현.

### 2. 브랜치 전략 — feature/71 위에 스택

- **물었던 것**: #218은 #71에 의존하는데 #71이 아직 dev 미머지(In review). 브랜치를 어디서 딸지.
- **선택지**:
  - (A) feature/71 위 스택 — 상세 훅 생성 즉시 가능. / PR base는 dev, #71 먼저 머지.
  - (B) dev 기준 + #71 선 머지 대기 — 의존이 깔끔. / #71 머지까지 #218 착수 막힘.
- **고른 것**: (A) 스택.
- **근거**: 상세 훅 `useGetDeliveryHistoryDetail` 생성에 #71 백엔드가 필요한데, feature/71 = dev + #71이라
  local 기동→`pnpm generate:api`가 바로 된다. 메모리 규칙(스택 PR은 머지 커밋, 선행 머지 후 리베이스 불필요)과 일치.
- **영향**: #218 PR은 #71 머지 후 dev 기준으로 열거나 스택으로 처리. #71이 먼저 dev에 가야 한다.

### 3. (긴급) regen이 드러낸 stale 클라이언트 — 별도 이슈로 에스컬레이션

- **물었던 것**: #218 필수 regen이 dev의 stale 생성 클라이언트 버그를 드러냈다(고객 취소 #47 화면 2개가
  낡은 훅명 `useCancelDelivery` 사용, 실제 백엔드는 `cancelCustomerDelivery`→`useCancelCustomerDelivery`).
  #218에서 함께 고칠지, 별도 이슈로 뺄지.
- **선택지**:
  - (A) 이 PR에서 함께 정정 — 빌드 즉시 녹색. / #47 화면까지 범위 확장.
  - (B) 중단·별도 이슈 에스컬레이션 — 범위 유지. / regen 커밋해야 #218 성립이라 그 버그 머지까지 빌드 red 대기.
- **고른 것**: (B) 에스컬레이션.
- **근거**: 사람 선택. #218 범위를 #47 화면으로 넓히지 않기로 함.
- **영향**: [#379](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/379) 생성. 그 버그가 dev에
  반영될 때까지 **#218 전체 `pnpm typecheck`/`build`는 red**다(내 파일이 아니라 #47 화면에서 실패). 단위
  테스트(147개)는 전부 통과. #218은 #379 머지 후 재생성·빌드 녹색 확인하고 마무리한다.

## 스스로 판단한 것

- **정산 UI를 시안(날씨할증·수수료)에서 실제 필드로 재구성**: 백엔드 `FareBreakdownResponse`에는 날씨
  할증·수수료 항목이 없다. 기본운임(baseFare)+거리운임(distanceFare)+물품할증(itemSurcharge)=확정운임
  (totalFare) + 별도 `settlementAmount`(최종 정산 금액)으로 매핑했다. 물품 할증은 0이면 행을 숨긴다.
  현재 정책상 settlementAmount == totalFare지만(수수료 정책 미도입), 화면은 두 값을 각각 받아 표시한다.
- **완료 인증(proof)은 이미지 대신 텍스트로**: proofValue가 PHOTO면 로컬 절대경로/S3 URL이라 `<img>`로
  못 그린다. proofType 라벨 + (PHOTO가 아니면) proofValue를 텍스트로 표시했다.
- **지도 영역 축소**: 시안의 지도 placeholder(`TODO: 지도/이미지 연결`)는 지도 SDK 미결(#209)이라
  제거하고 "이동 거리" 텍스트로 대체했다.
- **주문번호를 `#{deliveryId}`로**: 시안의 `ORD-20231025-001`은 백엔드에 없는 형식이라 숫자 id로 표시
  (`requests/$deliveryId`와 동일).
- **완료 시각은 steps의 COMPLETED에서 얻고 없으면 settledAt으로 보완**(헤더 날짜·시각).

## 일부러 하지 않은 것

- **"영수증 보기" 버튼**: 대응 백엔드 API가 없다(`docs/04-frontend-api-map.md` §5 초안뿐, 이슈가 "없으면
  이 이슈에서 완성하지 않고 제외 명시" 지시). 버튼을 제거했다. 후속: 미등록.
- **지도 SDK 연동**: #209와 같은 미결이라 축소.
- **"정산 대기" 분기**: 선택 1대로 생략.
- **컴포넌트 렌더 테스트**: 관례대로 순수 함수만 vitest로 덮고 라우터를 띄우지 않았다. 브라우저 수동
  확인은 #379 해소 후(빌드 녹색) 진행 예정.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `-riderHistoryDetail.test.ts` | 물품/거리/금액/인증 라벨·대체문구, 날짜·시각(잘못된 값 폴백), 타임라인 매핑·시각없는 항목 제외, 완료시각 보완, 404/네트워크/서버/비-axios 에러 문구 |

실행 결과:

```text
pnpm exec vitest run .../-riderHistoryDetail.test.ts → 7 tests passed
pnpm test (전체)                                     → 20 files, 147 tests passed
pnpm typecheck / pnpm build                          → 통과 (#379 dev 머지 후 rebase로 해소)
```

### 검증하지 못한 것

- **브라우저 렌더**(상세 조회·정산 표시): 자동 테스트로 덮지 않았다(프론트 러너는 순수 함수만). 사람이
  로컬(백엔드 local + 프론트 dev 서버)에서 완료 배송 상세를 열어 수동 확인 예정.

## 새로 생긴 미결 사항

- **[#379] dev의 Orval 생성 클라이언트가 백엔드 operationId와 불일치**: 필수 regen이 드러낸 선행 버그.
  #47 고객 취소 화면이 낡은 `useCancelDelivery`를 쓰는데 실제 백엔드는 `cancelCustomerDelivery`
  (→`useCancelCustomerDelivery`)다. CI가 프론트 typecheck/build를 돌리지 않아(백엔드 `-x test`) 조용히
  병합됐다. **해소됨**: #379(PR #381)가 dev에 머지된 뒤 feature/218을 dev 위로 rebase해 빌드 green.
  예방책(프론트 CI 게이트)은 #379에서 계속 논의.
