# 04. 프론트엔드 API 매핑 (초안)

프론트엔드 화면이 **실제로 렌더하는 데이터와 액션**을 근거로 역산한 백엔드 REST/SSE API 초안이다.

> ⚠️ **초안 / 미확정**
> - 모든 화면은 현재 정적 목업(하드코딩)이며 네트워크 호출이 아직 0건이다. 경로·스키마·HTTP 메서드는 UI에서 역산한 **제안값**이다.
> - 정본은 백엔드 OpenAPI 스펙 확정 후 갱신한다. 충돌 시 우선순위는 ADR → ERD/DDL → [도메인 정책](02-domain-policy.md) → 기능 명세 → 본 문서 순.
> - 확정되면 `frontend/orval.config.ts`의 `input.target`을 springdoc(`/v3/api-docs`)로 교체 → `pnpm generate:api`로 훅 생성.

## 전제

- 인증: **쿠키 기반 서버 세션** (`withCredentials: true`). 쿠키엔 세션 식별자만, 세션 원본은 Redis.
- 도메인 패키지: `{customer, rider, order, matching, location, payment, common}`.
- 실시간 위치는 REST가 아니라 **SSE** → Orval 대상 아님, `shared/hooks/useTrackingStream`으로 분리.
- 근거 강도: **[구현]** = 실제 렌더된 화면 기반, **[추론]** = stub 화면이라 경로/이름에서만 추론.

## 배송 상태 머신 (참조)

```
WAITING → ASSIGNED → MOVING_TO_PICKUP → PICKED_UP → DELIVERING → COMPLETED
                                                              ↘ (배차 전) CANCELED
```
라이더 상태: `UNAVAILABLE` · `AVAILABLE` · `BUSY` (배송 상태와 분리).

---

## 0. 인증 · 세션 (common / customer · rider)

| Method | Path | 용도 | 근거 |
|---|---|---|---|
| POST | `/api/{customer\|rider}/login` | 로그인, 세션 쿠키 발급 `{userId, password}` | [구현] login |
| POST | `/api/auth/logout` | 로그아웃(세션 파기) | [추론] account/settings |
| GET | `/api/{customer\|rider}/me` | 세션 검증 + 프로필/역할/상태 반환 | [추론] `_authed.tsx` 가드 |
| POST | `/api/{customer\|rider}/signup` | 가입 (아래 payload) | [구현] signup |
| GET | `/api/{customer\|rider}/check-id?userId=` | 아이디 중복 확인 → 가용 여부 | [구현] signup "중복 확인" |
| POST | `/api/auth/phone/send` | 휴대폰 인증번호 전송 `{phone}` | [구현] signup "인증번호 전송" |
| POST | `/api/auth/phone/verify` | 인증번호 확인 `{phone, code}` | [추론] 코드 입력 필드 미구현 |
| POST | `/api/auth/find-id` | 아이디 찾기 | [추론] login/find-account |
| POST | `/api/auth/reset-password` | 비밀번호 재설정 | [추론] login/find-account |

**signup payload**
- 공통: `{ userId, password, passwordConfirm, name, phone, agreements: { serviceTerms(필수), privacy(필수), marketing(선택) } }`
- 라이더 추가: `{ vehicleType: WALK|BICYCLE|MOTORCYCLE|CAR, vehicleNumber(도보·자전거는 미입력) }` — *rider 스펙 확정 시 검증 확정*
- 정책: 한 계정은 CUSTOMER 또는 RIDER 하나의 역할만.

---

## 1. 배송요청 — order 도메인 (고객)

| Method | Path | 용도 | 근거 · 정책 |
|---|---|---|---|
| POST | `/api/orders/quote` | 요금 견적 `{itemSize, pickup, dropoff, options}` → `{baseFee, totalFee, etaText}` | [구현] new.tsx "요금 API 소비 후 활성화" TODO |
| POST | `/api/orders` | 배송요청 생성+결제 | [구현] new.tsx "결제하기". **고객 진행중 1건 제한** 검증 |
| GET | `/api/orders?accountType=` | 이용기록 목록 | [구현] deliveries/index (탭: 전체/개인/가족) |
| GET | `/api/orders/{id}` | 상세 조회 | [구현] deliveries/$id |
| GET | `/api/orders/{id}` (tracking) | 추적 뷰: 진행 상태·단계 타임스탬프·결제수단 | [구현] $id/tracking |
| PATCH | `/api/orders/{id}/cancel` | 주문 취소 → CANCELED. **WAITING(배차 전)만 허용** | [구현] tracking "주문취소" |

**주요 응답 필드**
- 목록 항목: `id, date, status, method(차량·상품), pickupAddr, dropoffAddr, amount`
- 상세: `orderNo, pickup{name,addr}, dropoff{name,addr}, reservedAt, deliveryWindow, method, storageLocation, proofPhoto, fee`
- 추적: `status, pickupGoalTime, steps[{label,at}], orderNo, pickup, dropoff, callType, payment{amount,method}`

**생성 payload**: `{ itemSize, highValue(>50만원 flag), riderInstructions, pickup{name,contact,address,detailAddress}, dropoff{...}, paymentAmount }`

품목 크기 옵션(초소형/소형/중형/대형 + 접수 제한)은 정적 상수 또는 `GET /api/orders/item-sizes`.

---

## 2. 주소 — customer / location 도메인

| Method | Path | 용도 |
|---|---|---|
| GET | `/api/addresses/search?q=` | 키워드/도로명 검색 → `{roadAddress, placeName, lat, lng}` (외부 지오코딩 연동) |
| GET | `/api/customer/addresses/recent` | 최근 주소 목록 `{roadAddress, label}` |
| DELETE | `/api/customer/addresses/recent/{id}` | 최근 주소 단건 삭제 |
| DELETE | `/api/customer/addresses/recent` | 최근 주소 전체 삭제 |

근거: [구현] `deliveries/-components/AddressSearch.tsx`.

---

## 3. 포인트 · 결제 — payment 도메인

| Method | Path | 용도 | 근거 |
|---|---|---|---|
| GET | `/api/payment/points` | 잔액(+부족액 계산) | [구현] customer/points, charge |
| POST | `/api/payment/points/charge` | 충전 `{amount, paymentMethod: creditCard\|kakaoPay\|...}` | [구현] points/charge |
| POST | `/api/payment/points/use` | 포인트 결제(주문 연계) `{orderId, points}` | [구현] customer/points "결제하기" |
| GET | `/api/rider/points` | 라이더 정산 요약: 출금가능/보유 포인트, 예상 고용·산재보험료 | [구현] rider/points |
| GET | `/api/rider/points/transactions?period=YYYY-MM&type=` | 정산 내역(월별 필터) | [구현] rider/points |
| POST | `/api/rider/points/withdraw` | 출금 신청 | [구현] rider/points "출금 신청 API 연결" TODO |

결제는 MVP에서 포인트 기반/모킹 우선(실 PG 연동 아님).

---

## 4. 라이더 배차 · 배송 — rider / matching 도메인

| Method | Path | 용도 | 정책 |
|---|---|---|---|
| PATCH | `/api/rider/status` | 운행 상태 토글 UNAVAILABLE↔AVAILABLE | [구현] rider home "퀵 시작하기" |
| GET | `/api/rider/requests?sort=&radius=` | 배차 가능 요청 목록 | [구현] requests/index |
| GET | `/api/rider/requests/{id}` | 요청 상세 | [구현] requests/$id |
| POST | `/api/rider/requests/{id}/accept` | **배차확정(원자적)** | 핵심 동시성 로직 (아래) |
| POST | `/api/rider/requests/{id}/skip` | 넘기기 | [구현] requests/$id "넘기기" |
| GET | `/api/rider/delivery/current` | 진행 중 배송 1건 조회 | [구현] delivery 5단계 공용 |
| POST | `/api/rider/delivery/{id}/transition` | 단계 전이 (아래 표) | [구현] delivery 스테이지 버튼 |
| POST | `/api/rider/delivery/{id}/complete` (multipart) | **배송완료(원자적)** + 인수 사진 업로드 | [구현] complete 스테이지 |

**배차확정 원자성** (`accept`): 배송 `WAITING→ASSIGNED` + 라이더 `AVAILABLE→BUSY` + 배차 관계 생성 (1 트랜잭션). 하나의 요청엔 최대 1명, 라이더는 동시 1건. 경쟁 실패 요청은 **명확한 실패 결과**(부분 성공 없음).

**배송완료 원자성** (`complete`): 배송 `DELIVERING→COMPLETED` + 라이더 `BUSY→AVAILABLE` + 정산 내역 생성 (1 트랜잭션).

**단계 전이 매핑**

| 스테이지 | 버튼 | 전이 |
|---|---|---|
| goto_pickup | 픽업 출발하기 | ASSIGNED→MOVING_TO_PICKUP |
| before_pickup | 픽업 완료하기 | MOVING_TO_PICKUP→PICKED_UP |
| while_pickup | 배송 출발하기 | PICKED_UP→DELIVERING |
| after_pickup → complete | 배송 완료하기 | DELIVERING→COMPLETED (완료 원자적) |

**delivery 응답 필드**: `orderNo, pickup{addr,detail,receiver,phone}, dropoff{addr,detail,receiver,phone}, deadline, remainingDistance, pickupInstruction, itemInfo, earnings{total, breakdown[]}`

> MVP 범위: 배차 이후(ASSIGNED 이상) 취소·라이더 배차 포기 제외.

---

## 5. 라이더 이력 · 정산 — rider / settlement 도메인

| Method | Path | 용도 |
|---|---|---|
| GET | `/api/rider/history?page=` | 완료 이력(페이지네이션) + 주간 총수입. 각 건: 일시/상태/출발·도착/거리/금액 |
| GET | `/api/rider/history/{id}` | 상세: 경로, 품목, 고객 요청사항, 상태 타임라인, 정산 분해(기본운임+거리·날씨 할증-수수료) |
| GET | `/api/rider/history/{id}/receipt` | 영수증 |

근거: [구현] rider/history, history/$deliveryId.

---

## 6. 계정 · 알림 — account 화면군 (전부 [추론], stub)

| Method | Path | 용도 |
|---|---|---|
| GET / PATCH | `/api/account/me` | 프로필 조회 / 수정 |
| PATCH | `/api/account/password` | 비밀번호 변경 |
| GET / PATCH | `/api/account/settings` | 설정 토글 |
| DELETE | `/api/account/me` | 회원 탈퇴 |
| GET / PATCH | `/api/account/notifications` | 알림 목록 조회 / 읽음·수신설정 |

---

## 7. 실시간 — location 도메인 (SSE, Orval 제외)

- **고객 추적**: `GET /api/location/stream/{orderId}` (SSE) → 라이더 위치 `{lat, lng, heading, ts}` + 상태/단계 전이 push. `TrackingMap`(현재 빈 컴포넌트)에서 소비, `useTrackingStream`으로 분리. 근거: [구현] tracking "실제 지도(SSE 실시간 위치) 연결" TODO.
- **라이더 위치 발행**: 진행 중 배송 동안 위치 업로드 — **위치가 실제 변경됐을 때만** 이벤트. CANCELED 발생 시 라이더에게 push.
- **(검토)** `/rider/requests` 신규 요청 실시간 피드도 SSE 후보 — 정책 미확정.

다중 인스턴스이므로 위치 갱신을 처리한 인스턴스가 **Redis Pub/Sub으로 발행**하고, 해당 주문의 SSE 연결을
들고 있는 인스턴스가 고객에게 전송한다(2026-07-29 결정,
[Discussion #246](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/246)).

**프론트 영향은 없다.** 재연결 시 이벤트 재생 대신 최신 스냅샷으로 복구하는 설계(#79)라 재연결이 직전과
다른 인스턴스에 붙어도 성립한다. 전달이 at-most-once라 이벤트가 드물게 유실될 수 있는데, 그것도 다음 위치
이벤트와 재연결 스냅샷이 덮으므로 훅 구현에 추가 처리가 필요하지 않다.

---

## 미확정 / 후속 확인 항목

- 요금 견적/최종 요금 차이 허용 정책(허용하기로 결정됨)과 견적 API 응답 구조
- 배송 완료 인증 데이터 구조(사진/수령인/인증코드 채택 범위) → `complete` payload 확정
- 정산 생성 시점·실패 처리 → `complete` 트랜잭션 경계
- 포인트 선차감 vs 결제 승인 시점 → `orders`/`points/use` 연계
- API 멱등성 정책(중복 accept/complete 요청) → 요청 식별값 기준
- 주소·좌표 컬럼 구조, SSE 타임아웃·재연결·heartbeat·중복 연결
- 프론트 Origin ↔ API Origin 분리 시 CORS·쿠키(`Secure`/`HttpOnly`/`SameSite`) 설정
