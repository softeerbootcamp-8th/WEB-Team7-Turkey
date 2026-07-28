# 퀵서비스 배달 플랫폼 ERD 상세 문서

기준 파일: `퀵서비스3_erdcloud.sql` (2026-07-27 기준)
목적: 테이블별 컬럼 존재 이유와 테이블 간 관계의 이유를 정리하여, 프로젝트 참여자가 스키마 설계 의도를 빠르게 파악할 수 있도록 함.

## 0. 전체 구조와 FK 원칙

전체 18개 테이블은 4개 영역으로 나뉜다: 회원/약관, 주문/배송, 포인트/정산, 알림.

DB에 명시적으로 걸린 FK 제약은 26개다(Flyway 마이그레이션 `V1~V17` 기준, 복합키 FK 1개 포함).

| FK | 참조 대상 |
|---|---|
| `rider_profile.member_id` | `member.member_id` |
| `rider_payout_account.rider_id` | `rider_profile.member_id` |
| `point_wallet.member_id` | `member.member_id` |
| `member_term_agreement.member_id` | `member.member_id` |
| `member_term_agreement.term_id` | `term.term_id` |
| `point_charge.customer_id` | `member.member_id` |
| `item_type_surcharge.fare_policy_id` | `fare_policy.fare_policy_id` |
| `delivery_order.customer_id` | `member.member_id` |
| `delivery_order.assigned_rider_id` | `rider_profile.member_id` |
| `rider_withdrawal.rider_id` | `rider_profile.member_id` |
| `order_fare_snapshot.order_id` | `delivery_order.order_id` |
| `order_fare_snapshot.fare_policy_id` | `fare_policy.fare_policy_id` |
| `order_status_history.order_id` | `delivery_order.order_id` |
| `order_status_history.actor_member_id` | `member.member_id`(NULL 허용) |
| `delivery_proof.order_id` | `delivery_order.order_id` |
| `delivery_proof.rider_id` | `rider_profile.member_id` |
| `rider_location_history.order_id` | `delivery_order.order_id` |
| `rider_location_history.rider_id` | `rider_profile.member_id` |
| `rider_settlement.order_id` | `delivery_order.order_id` |
| `rider_settlement.rider_id` | `rider_profile.member_id` |
| `rider_settlement.(order_id, final_fare_snapshot_id)` | `order_fare_snapshot.(order_id, fare_snapshot_id)`(복합키) |
| `point_transaction.member_id` | `point_wallet.member_id` |
| `point_transaction.delivery_order_id` | `delivery_order.order_id`(NULL 허용) |
| `point_transaction.point_charge_id` | `point_charge.point_charge_id`(NULL 허용) |
| `point_transaction.rider_withdrawal_id` | `rider_withdrawal.withdrawal_id`(NULL 허용) |
| `point_transaction.rider_settlement_id` | `rider_settlement.settlement_id`(NULL 허용) |

DB 제약이 걸려 있지 않은 컬럼은 다음 하나뿐이다.

- `rider_withdrawal`의 `bank_code_snapshot`/`masked_account_number_snapshot`/`account_holder_name_snapshot` — `rider_payout_account`의 값을 신청 시점에 복제한 스냅숏이라 실시간 참조가 아니다. 계좌 정보가 나중에 바뀌어도 신청 당시 값을 그대로 보존해야 하므로 의도적으로 FK를 걸지 않았다.

`point_transaction`의 4개 원인 참조 컬럼(`delivery_order_id`/`point_charge_id`/`rider_withdrawal_id`/`rider_settlement_id`)은 각각 개별 FK 제약이 걸려 있지만(모두 NULL 허용), "이 중 정확히 1개만 값이 있어야 한다"는 폴리모픽 제약 자체는 여러 컬럼에 걸친 조건이라 DB가 아니라 애플리케이션이 책임진다.

각 테이블의 "관계" 표에서는 이런 컬럼이 실제로 몇 대 몇 관계인지(1:1 / 1:N / N:M)와 왜 그런 카디널리티인지를 함께 적는다. 표기 기준은 다음과 같다.

- **1:1** — 양쪽 다 최대 1개까지만 연결. 보통 PK를 그대로 FK로 공유하는 확장 테이블 관계, 또는 "이벤트 1건당 결과가 1건만 나온다"고 업무적으로 정해진 경우
- **1:N** — 기준 테이블 1행이 상대 테이블의 여러 행과 연결. 표에서는 "기준 테이블 → 상대 테이블" 방향을 1:N으로, 그 반대 방향(자식 테이블 기준)은 N:1로 적어 어느 쪽이 "여러 개를 가질 수 있는 쪽"인지 명확히 한다
- **N:M** — 양쪽 모두 여러 개와 연결 가능. 이 스키마에서는 연결 테이블(`member_term_agreement`)이 있는 경우에만 나타난다

member 테이블을 중심으로 한 관계는 아래 이미지로도 확인할 수 있다.

아래 모든 테이블의 "예시 데이터"는 하나로 이어지는 시나리오다. 고객 김민준(member_id=1)과 이지은(member_id=3)이 라이더 박서준(member_id=2)에게 배송을 맡기는 상황을 처음부터 끝까지 따라가면서, 같은 `member_id`, `order_id` 등이 여러 테이블에 걸쳐 어떻게 이어지는지 확인할 수 있다.

---

## 1. 회원/약관 영역

### 1.1 member

회원가입한 모든 사용자(고객/라이더)를 하나의 테이블로 통합 관리하는 기준 테이블.

| 컬럼 | 존재 이유 |
|---|---|
| `member_id` (PK) | 로그인 식별자 변경 가능성과 외부 노출을 피하기 위한 내부 대체키 |
| `login_id` | 로그인용 식별자. `uk_member_login_id` UNIQUE로 중복 가입을 막는다 |
| `password_hash` | 비밀번호 원문 대신 해시만 저장(보안) |
| `name`, `phone_number` | 본인 확인 및 연락 용도. `phone_number`는 `uk_member_phone_number` UNIQUE로 중복 가입을 막는다 |
| `role` | `CUSTOMER`/`RIDER` 중 하나(`ck_member_role`). 역할별 테이블을 나누지 않고 공통 테이블+역할 컬럼으로 관리(단일 테이블 상속 방식). ADMIN 역할은 없다 |
| `status` | 계정 상태: `ACTIVE`/`SUSPENDED`/`WITHDRAWN`(`ck_member_status`) |
| `created_at`, `updated_at` | 감사(audit) |
| `withdrawn_at` | 탈퇴 시점만 기록하는 소프트 삭제. row를 실제 삭제하면 주문·정산 이력의 참조가 끊기므로 삭제 대신 시점만 남김. `status='WITHDRAWN'`일 때만 값이 있어야 한다는 것을 `ck_member_withdrawn_at`이 강제한다 |

**예시 데이터**

| member_id | login_id | name | role | status |
|---|---|---|---|---|
| 1 | minjun.kim | 김민준 | CUSTOMER | ACTIVE |
| 2 | seojun.park | 박서준 | RIDER | ACTIVE |
| 3 | jieun.lee | 이지은 | CUSTOMER | ACTIVE |
| 4 | hana.jung | 정하나 | RIDER | ACTIVE |

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| rider_profile | member_id (PK=FK, 명시적) | 1:1 | 라이더 확장정보는 라이더 역할인 회원마다 정확히 1개만 존재 |
| point_wallet | member_id (PK=FK, 명시적) | 1:1 | 모든 회원은 포인트 지갑을 정확히 1개만 가짐 |
| delivery_order | customer_id | 1:N | 한 고객이 여러 번 주문할 수 있고, 주문 1건은 고객 1명에게만 속함 |
| delivery_order | assigned_rider_id | 1:N | 한 라이더가 여러 주문을 순차적으로 수행하고, 주문 1건의 배정 라이더는 최대 1명 |
| point_transaction | member_id | 1:N | 한 회원에게 충전·정산·출금 등 여러 건의 포인트 거래가 발생 |
| member_notification | member_id | 1:N | 한 회원에게 여러 알림이 발송됨 |
| order_status_history | actor_member_id | 1:N (NULL 허용) | 한 사람이 여러 상태변경 행위를 수행할 수 있고, 시스템이 자동으로 바꿀 땐 NULL |
| member_term_agreement | member_id | 1:N | 한 회원이 여러 약관에 각각 동의하고, 약관이 새 버전으로 바뀔 때마다 그 버전에 대한 동의 행이 추가로 쌓임 |
| point_charge | customer_id | 1:N | 한 고객이 여러 번 포인트를 충전 |

> member 테이블 관계도는 위 이미지를 참고하십시오.

### 1.2 rider_profile

`member` 중 라이더 역할에게만 필요한 확장 정보.

| 컬럼 | 존재 이유 |
|---|---|
| `member_id` (PK=FK) | member과 1:1이므로 별도 PK 없이 FK를 그대로 PK로 사용 |
| `operating_status` | 현재 운행 가능 여부. 배차 로직에서 조회 |
| `status_changed_at` | 상태 유지 시간 계산·모니터링용 |
| `created_at`, `updated_at` | 감사 |

낙관적 락(`version`)은 두지 않는다(팀 정책상 `@Version` 전면 폐기). 배차 시 라이더 상태 갱신의 동시성은 조건부 UPDATE(`WHERE operating_status = 'AVAILABLE'`)로 보장한다.

**예시 데이터**

| member_id | operating_status |
|---|---|
| 2 (박서준) | AVAILABLE |
| 4 (정하나) | UNAVAILABLE |

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| member | member_id (PK=FK, 명시적) | 1:1 | 위 member 섹션과 동일한 관계의 반대 방향 |
| rider_payout_account | rider_id (PK=FK, 명시적) | 1:1 | 정산 계좌는 라이더당 정확히 1개만 등록(계좌 여러 개 등록을 막는 정책으로 추정) |
| delivery_order | assigned_rider_id | 1:N | 한 라이더가 여러 주문을 수행 |
| rider_settlement | rider_id | 1:N | 한 라이더에게 여러 건의 정산이 발생 |
| rider_withdrawal | rider_id | 1:N | 한 라이더가 여러 번 출금을 신청 |
| rider_location_history | rider_id | 1:N | 배송마다, 그리고 배송 중에도 반복적으로 위치가 기록됨 |
| delivery_proof | rider_id | 1:N | 한 라이더가 여러 건의 배송 증빙을 남김 |

### 1.3 rider_payout_account

라이더의 정산 계좌 정보. PK를 `rider_id`로 둬서 "라이더 1명당 계좌 1개"라는 규칙을 스키마로 강제한다.

| 컬럼 | 존재 이유 |
|---|---|
| `rider_id` (PK=FK) | rider_profile과 1:1 |
| `bank_code` | 은행 구분 |
| `account_number_ciphertext` | 계좌번호를 평문이 아닌 암호문(VARBINARY)으로 저장(금융정보 보안) |
| `masked_account_number` | 화면 표시용 마스킹 값을 별도 보관해 매번 복호화하지 않도록 함 |
| `account_holder_name` | 예금주 확인 |
| `created_at`, `updated_at` | 감사 |

**예시 데이터**

| rider_id | bank_code | masked_account_number | account_holder_name |
|---|---|---|---|
| 2 (박서준) | KB국민은행 | 110-****-5678 | 박서준 |
| 4 (정하나) | 신한은행 | 140-****-1234 | 정하나 |

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| rider_profile | rider_id (PK=FK, 명시적) | 1:1 | 위 rider_profile 섹션과 동일한 관계의 반대 방향 |
| rider_withdrawal | (직접 FK 없음, 값 복제) | 1:N | 계좌는 1개지만 그 계좌로 여러 번 출금을 신청할 수 있고, 신청마다 그 시점의 계좌 정보를 `_snapshot` 컬럼에 복사해 이후 계좌 변경과 무관하게 이력을 보존 |

### 1.4 term

약관을 버전·대상 역할별로 관리하는 테이블.

| 컬럼 | 존재 이유 |
|---|---|
| `term_id` (PK) | 식별자 |
| `term_code` | 약관 종류(이용약관/개인정보처리방침 등) 구분. 버전이 바뀌어도 코드는 유지 |
| `target_role` | 대상 구분: `COMMON`/`CUSTOMER`/`RIDER`(`ck_term_target_role`). `COMMON`이 고객·라이더 공통 약관이다 |
| `title`, `content`(MEDIUMTEXT) | 약관 본문은 길어질 수 있어 대용량 텍스트 타입 사용 |
| `version` | 개정판 구분. `(term_code, target_role, version)`이 `uk_term_code_role_version`로 유일해야 한다 |
| `is_required` | 필수/선택 동의 여부 |
| `is_active` | 현재 유효 버전인지 |
| `effective_from`/`effective_to` | 여러 버전이 시간에 따라 공존하도록. `effective_to`가 있으면 `effective_from`보다 뒤여야 한다(`ck_term_effective_period`) |
| `created_at` | 감사 |

**예시 데이터**

| term_id | term_code | target_role | title | version | is_required |
|---|---|---|---|---|---|
| 1 | SERVICE_TERMS | COMMON | 이용약관 | 1.0 | 1 |
| 2 | PRIVACY_POLICY | COMMON | 개인정보처리방침 | 1.2 | 1 |
| 3 | RIDER_TRANSPORT_TERMS | RIDER | 라이더 운송약관 | 1.0 | 1 |

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| member_term_agreement | term_id | 1:N | 한 약관에 여러 회원의 동의 기록이 쌓임 |
| member | (member_term_agreement를 통한 간접 관계) | N:M | 한 회원이 여러 약관에 동의하고, 한 약관에 여러 회원이 동의하므로 양쪽 다 여러 개 → N:M. `member_term_agreement`가 이 N:M을 1:N + 1:N 두 개로 풀어주는 연결 테이블 |

### 1.5 member_term_agreement

회원과 약관 사이의 동의 이력.

| 컬럼 | 존재 이유 |
|---|---|
| `agreement_id` (PK) | 식별자 |
| `member_id` | member을 가리키는 FK(명시적) |
| `term_id` | term을 가리키는 FK(명시적) |
| `agreed` | 동의/거부 여부(거부도 기록 가능) |
| `agreed_at` | 동의 시각 |

**예시 데이터**

| agreement_id | member_id | term_id | agreed |
|---|---|---|---|
| 1 | 1 (김민준) | 1 (이용약관) | 1 |
| 2 | 1 (김민준) | 2 (개인정보처리방침) | 1 |
| 3 | 2 (박서준) | 3 (라이더 운송약관) | 1 |

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| member | member_id | N:1 | 이 테이블 입장에서 여러 행이 회원 1명을 가리킴(회원 쪽에서 보면 1:N) |
| term | term_id | N:1 | 이 테이블 입장에서 여러 행이 약관 1개를 가리킴(약관 쪽에서 보면 1:N) |

두 관계를 합치면 `member`–`term`은 N:M이고, 이 테이블이 그 사이를 잇는 연결 테이블이다. `(member_id, term_id)`는 `uk_member_term_agreement`로 유일하므로, 같은 `term` 행(=같은 약관 버전)에 대한 중복 동의 기록은 만들어지지 않는다. "이력"은 같은 `term_code`가 새 버전(새 `term_id`)으로 바뀔 때마다 그 버전에 대한 동의가 새 행으로 쌓이는 구조를 말한다.

---

## 2. 주문/배송 영역

### 2.1 delivery_order

주문/배송 영역의 허브가 되는 핵심 테이블.

| 컬럼 | 존재 이유 |
|---|---|
| `order_id` (PK) | 식별자 |
| `customer_id` | FK(명시적) member(요청 고객) |
| `assigned_rider_id` (NULL 허용) | 배정 전 상태를 표현하기 위해 NULL 허용 |
| `status` | 주문 상태 머신. `WAITING → ASSIGNED → MOVING_TO_PICKUP → PICKED_UP → DELIVERING → COMPLETED`, 그리고 `CANCELED`(배차 전에만) 7개 값 중 하나(`ck_delivery_status`) |
| `request_key` (CHAR36) | 클라이언트 중복 주문 생성을 막는 idempotency key |
| `item_type` | `DOCUMENT`/`SMALL_PARCEL`/`MEDIUM_PARCEL`/`LARGE_PARCEL`/`FOOD` 중 하나(`ck_delivery_item_type`). `item_type_surcharge`와 매칭되어 추가요금 결정 |
| `straight_distance_meters` | 좌표 기반 직선거리, 요금 계산 기준으로 추정 |
| `pickup_*`, `destination_*` (도로명주소/상세주소/우편번호/위경도) | 지도 표시, 라이더 내비게이션, 거리 기반 요금 계산에 사용. 상세주소는 없을 수 있어 NULL 허용 |
| `sender_*`, `recipient_*` | 실제 물건을 보내는/받는 사람이 로그인한 고객과 다를 수 있어 별도 저장(대리 접수 대응) |
| `requested_at` ~ `canceled_at` (단계별 타임스탬프) | 현재 주문의 마지막 상태 시각을 빠르게 조회하기 위한 비정규화. 전체 변경 이력은 `order_status_history`가 담당 |
| `cancel_reason` | 취소 사유 |
| `updated_at` | 감사 |
| `active_customer_id`, `active_rider_id` (NULL 허용) | 배정된 주체(`customer_id`/`assigned_rider_id`)와 별도로, 현재 실시간으로 이 주문에 연결되어 있는 주체를 추적하기 위한 컬럼으로 추정(라이더 교체 등 시나리오 대응) |

낙관적 락(`version`)은 두지 않는다(팀 정책상 `@Version` 전면 폐기). 배차 확정의 동시성은 조건부 UPDATE(`WHERE status = 'WAITING'`)와 `active_customer_id`/`active_rider_id`의 UNIQUE 제약으로 보장한다.

**예시 데이터**

| order_id | customer_id | assigned_rider_id | status | item_type |
|---|---|---|---|---|
| 1001 | 1 (김민준) | 2 (박서준) | COMPLETED | DOCUMENT |
| 1002 | 3 (이지은) | 2 (박서준) | DELIVERING | LARGE_PARCEL |
| 1003 | 1 (김민준) | NULL | WAITING | SMALL_PARCEL |
| 1004 | 3 (이지은) | 2 (박서준) | COMPLETED | FOOD |

1003은 아직 라이더가 배정되지 않아 `assigned_rider_id`가 NULL인 상태를 보여준다.

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| member | customer_id | N:1 | 이 테이블 입장에서 여러 주문이 고객 1명을 가리킴 |
| member(rider_profile) | assigned_rider_id | N:1 | 이 테이블 입장에서 여러 주문이 라이더 1명을 가리킴 |
| order_status_history | order_id | 1:N | 주문 1건에 상태변화 이벤트가 여러 번 발생 |
| order_fare_snapshot | order_id | 1:N | 견적 시점 스냅샷, 확정 시점 스냅샷처럼 주문 1건에 여러 스냅샷이 생길 수 있음 |
| rider_location_history | order_id | 1:N | 배송 중 위치가 반복적으로 기록됨 |
| delivery_proof | order_id | 1:1 | 주문당 완료 인증은 최대 1건(`uk_delivery_proof_order`) |
| rider_settlement | order_id | 1:1 | 완료된 주문 1건당 정산은 1번만 발생한다고 가정 |
| point_transaction | delivery_order_id | 1:N | 결제 차감 1건, 취소 시 환불 1건처럼 주문과 관련된 포인트 거래가 여러 건 생길 수 있음 |
| member_notification | order_id | 1:N | 배정완료·도착알림 등 주문 관련 알림이 여러 번 발송됨 |

### 2.2 order_status_history

주문 상태 변화를 낱개로 기록하는 append-only 감사 로그.

| 컬럼 | 존재 이유 |
|---|---|
| `status_history_id` (PK) | 식별자 |
| `order_id` | FK(명시적) delivery_order |
| `previous_status` (NULL 허용) | 최초 생성 시에는 이전 상태가 없음 |
| `new_status` | 변경된 상태 |
| `action` | 상태보다 더 세분화된 이벤트명(예: ASSIGN, CANCEL) |
| `actor_member_id` (NULL 허용), `actor_type` | 시스템에 의한 자동 변경(NULL)과 사람에 의한 변경을 모두 표현 |
| `reason` (NULL 허용) | 취소 등 사유 |
| `request_key` | 상태변경 요청의 idempotency key |
| `changed_at` | 변경 시각 |

**예시 데이터** (order_id=1001의 상태 변화)

| status_history_id | order_id | previous_status | new_status | action | actor_member_id | actor_type |
|---|---|---|---|---|---|---|
| 1 | 1001 | WAITING | ASSIGNED | ASSIGN | 2 (박서준) | RIDER |
| 2 | 1001 | ASSIGNED | PICKED_UP | PICKUP | 2 (박서준) | RIDER |
| 3 | 1001 | PICKED_UP | COMPLETED | COMPLETE | 2 (박서준) | RIDER |

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| delivery_order | order_id | N:1 | 이 테이블 입장에서 여러 이력 행이 주문 1건을 가리킴(주문 쪽에서 보면 1:N) |
| member | actor_member_id | N:1 (NULL 허용) | 이 테이블 입장에서 여러 이력 행이 행위자 1명을 가리키고, 시스템 자동 처리는 NULL |

`delivery_order`의 각 타임스탬프 컬럼은 "현재값", 이 테이블은 "전체 히스토리"로 역할이 나뉜다.

### 2.3 fare_policy

기본요금·거리요금을 버전 단위로 관리하는 정책 테이블.

| 컬럼 | 존재 이유 |
|---|---|
| `fare_policy_id` (PK) | 식별자 |
| `policy_version` | 버전 구분 |
| `base_fare` | 기본요금 |
| `distance_unit_meters`(기본 1000) | "1km당 얼마" 식 계산 단위 |
| `distance_unit_fare` | 단위 거리당 요금 |
| `max_delivery_distance_meters` | 배송 가능 최대거리 제한 |
| `status` | 정책 상태: `ACTIVE`/`INACTIVE` 2개뿐(`ck_fare_policy_status`). "초안/만료" 같은 별도 단계는 없다 |
| `effective_from`/`effective_to` | 여러 버전이 시간에 따라 교체 |
| `created_at` | 감사 |
| `active_policy_marker` (NULL 허용) | `status='ACTIVE'`일 때만 값(1)을 갖는 생성 컬럼. MySQL UNIQUE는 NULL을 다건 허용하므로, 이 컬럼의 UNIQUE(`uk_fare_policy_active`)가 곧 "활성 정책은 최대 1개" 제약이 된다 |

**예시 데이터**

| fare_policy_id | policy_version | base_fare | distance_unit_fare | status | active_policy_marker |
|---|---|---|---|---|---|
| 1 | v1.0 | 3000 | 500 | ACTIVE | 1 |
| 2 | v0.9 | 2800 | 450 | INACTIVE | NULL |

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| item_type_surcharge | fare_policy_id | 1:N | 정책 하나에 품목 유형별 추가요금 행이 여러 개 생김 |
| order_fare_snapshot | fare_policy_id | 1:N | 정책 하나가 여러 주문의 요금 계산에 반복해서 사용됨 |

### 2.4 item_type_surcharge

특정 요금정책에 종속된 품목별 추가요금표.

| 컬럼 | 존재 이유 |
|---|---|
| `item_type_surcharge_id` (PK) | 식별자 |
| `fare_policy_id` | FK(명시적) fare_policy |
| `item_type` | `DOCUMENT`/`SMALL_PARCEL`/`MEDIUM_PARCEL`/`LARGE_PARCEL`/`FOOD` 중 하나. `delivery_order.item_type`과 매칭 |
| `surcharge_amount` (기본 0) | 추가요금 |
| `created_at` | 감사 |

**예시 데이터** (fare_policy_id=1에 종속)

| item_type_surcharge_id | item_type | surcharge_amount |
|---|---|---|
| 1 | DOCUMENT | 0 |
| 2 | LARGE_PARCEL | 2000 |
| 3 | FOOD | 1500 |

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| fare_policy | fare_policy_id | N:1 | 여러 추가요금 행이 정책 1개를 가리킴(정책 쪽에서 보면 1:N) |

### 2.5 order_fare_snapshot

주문 시점에 적용된 정책과 계산 결과를 고정 저장.

| 컬럼 | 존재 이유 |
|---|---|
| `fare_snapshot_id` (PK) | 식별자 |
| `order_id` | FK(명시적) delivery_order |
| `fare_policy_id` | FK(명시적) fare_policy(어떤 정책 버전으로 계산했는지 고정) |
| `fare_type` | `ESTIMATE`(배송요청 생성 시점 예상 요금)/`FINAL`(배송 완료 시점 최종 요금) 둘 중 하나. 주문당 각 종류 최대 1건(`uk_order_fare_type`) |
| `policy_version` | fare_policy의 값을 중복 저장. 정책이 나중에 수정/삭제돼도 계산 당시 버전 문자열을 보존하기 위한 반정규화 |
| `calculation_distance_meters` | 실제 계산에 쓰인 거리(직선거리와 다를 수 있음을 시사) |
| `base_fare`, `distance_fare`, `item_surcharge`, `total_fare` | 계산 breakdown을 모두 저장해 사후 검증 가능하게 함 |
| `calculated_at` | 계산 시각 |

**예시 데이터**

| fare_snapshot_id | order_id | fare_type | base_fare | distance_fare | item_surcharge | total_fare |
|---|---|---|---|---|---|---|
| 1 | 1001 | ESTIMATE | 3000 | 1500 | 0 | 4500 |
| 2 | 1001 | FINAL | 3000 | 1500 | 0 | 4500 |
| 3 | 1002 | ESTIMATE | 3000 | 1500 | 2000 | 6500 |
| 4 | 1004 | FINAL | 3000 | 1000 | 1500 | 5500 |

order_id=1001은 ESTIMATE(예상 견적)와 FINAL(확정 금액)이 각각 스냅샷 1건씩 남아 주문 1건에 스냅샷이 여러 개 생기는 이유를 보여준다.

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| delivery_order | order_id | N:1 | 여러 스냅샷(견적/확정 등)이 주문 1건을 가리킴(주문 쪽에서 보면 1:N) |
| fare_policy | fare_policy_id | N:1 | 여러 스냅샷이 정책 1개를 가리킴(정책 쪽에서 보면 1:N) |
| rider_settlement | final_fare_snapshot_id | 1:1 | 정산은 확정된 스냅샷 1개를 기준으로 1번만 계산됨 |

### 2.6 rider_location_history

배송 중 라이더 위치를 주기적으로 기록하는 로그성 테이블.

| 컬럼 | 존재 이유 |
|---|---|
| `location_history_id` (PK) | 식별자 |
| `order_id`, `rider_id` | FK(명시적, 각각 개별 제약). 배송 중인 주문+라이더 조합 |
| `latitude`, `longitude`(DECIMAL 10,7) | GPS 정밀도 확보 |
| `accuracy_meters` (NULL 허용) | GPS 오차범위(신뢰도 판단) |
| `measured_at` | 클라이언트 측 실제 측정 시각 |
| `stored_at` | 서버 저장 시각. 네트워크 지연/배치전송으로 측정 시각과 달라질 수 있어 분리 |

**예시 데이터**

| location_history_id | order_id | rider_id | latitude | longitude | measured_at |
|---|---|---|---|---|---|
| 1 | 1001 | 2 (박서준) | 37.497970 | 127.027620 | 13:02:00 |
| 2 | 1001 | 2 (박서준) | 37.501235 | 127.029877 | 13:05:30 |
| 3 | 1002 | 2 (박서준) | 37.510000 | 127.040000 | 14:10:00 |

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| delivery_order | order_id | N:1 | 위치 기록이 배송 중 반복해서 쌓이므로 주문 1건에 여러 행(주문 쪽에서 보면 1:N) |
| rider_profile | rider_id | N:1 | 같은 이유로 라이더 1명에게 여러 위치 기록이 쌓임(라이더 쪽에서 보면 1:N) |

적재량이 큰 로그 테이블이다.

### 2.7 delivery_proof

배송완료 증빙 자료. 주문당 최대 1건이며(`uk_delivery_proof_order`), 생성 후 변경되지 않는 불변 레코드다.

| 컬럼 | 존재 이유 |
|---|---|
| `delivery_proof_id` (PK) | 식별자 |
| `order_id`, `rider_id` | FK(명시적, 각각 개별 제약) |
| `proof_type` | 인증 방식: `PHOTO`(사진)/`RECIPIENT_CONFIRMATION`(수령인 확인)/`AUTH_CODE`(인증코드) 중 하나 |
| `proof_value` | 실제 이미지 바이너리는 저장하지 않고, 참조값(URL/스토리지 키/코드)만 보관 |
| `created_at` | 감사 |

**예시 데이터**

| delivery_proof_id | order_id | rider_id | proof_type | proof_value |
|---|---|---|---|---|
| 1 | 1001 | 2 (박서준) | PHOTO | https://cdn.example.com/proof/1001-photo.jpg |
| 2 | 1004 | 2 (박서준) | RECIPIENT_CONFIRMATION | RCPT-1004-CONFIRMED |

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| delivery_order | order_id | 1:1 | 주문당 완료 인증은 최대 1건(`uk_delivery_proof_order`) |
| rider_profile | rider_id | N:1 | 라이더 1명이 여러 건의 증빙을 남길 수 있음(라이더 쪽에서 보면 1:N) |

`delivery_order` 완료 처리의 근거자료다.

---

## 3. 포인트/정산 영역

### 3.1 point_wallet

회원의 포인트 잔액을 담는 "현재 스냅샷" 테이블.

| 컬럼 | 존재 이유 |
|---|---|
| `member_id` (PK=FK) | member과 1:1 |
| `balance` (기본 0) | 현재 잔액 |
| `updated_at` | 감사 |

낙관적 락(`version`)은 두지 않는다(팀 정책상 `@Version` 전면 폐기). 잔액 차감·환불의 동시성은 서비스의 조건부 UPDATE(`balance >= :amount`)와 `point_transaction.request_key` 멱등성으로 보장한다.

**예시 데이터** (3.3 point_transaction 예시를 모두 반영한 최종 잔액)

| member_id | balance |
|---|---|
| 1 (김민준) | 15500 |
| 2 (박서준) | 7000 |
| 3 (이지은) | 4500 |

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| member | member_id (PK=FK, 명시적) | 1:1 | 모든 회원은 지갑을 정확히 1개만 가짐 |
| point_transaction | member_id (FK, 명시적) | 1:N | 지갑 1개에 대해 충전·사용·정산 등 여러 건의 거래 이력이 쌓임 |

잔액의 "현재값"은 `point_wallet`, "이력"은 `point_transaction`으로 역할이 나뉜다. 거래들의 `balance_after`를 시간순으로 누적하면 지갑의 현재 `balance`와 일치해야 한다.

### 3.2 point_charge

고객의 포인트 충전 결제 트랜잭션.

| 컬럼 | 존재 이유 |
|---|---|
| `point_charge_id` (PK) | 식별자 |
| `customer_id` | FK(명시적) member |
| `charge_request_key` | 중복 결제를 막는 idempotency key |
| `payment_method`, `payment_provider`, `provider_payment_key`, `provider_refund_key` | 외부 PG사와의 매칭 식별자 |
| `requested_amount`, `approved_amount`(NULL 허용), `refunded_amount`(기본 0) | 요청/승인/환불 금액을 각각 분리해 부분 승인·부분 환불 대응 |
| `status` (기본 PENDING) | 진행 상태: `PENDING`에서 시작해 `PAID`/`FAILED`/`CANCELED`로 갈리고, `PAID`는 `REFUNDED`(전액 환불)로만 전이. 상태별 필드 조합은 `ck_point_charge_state_values`가 강제 |
| `issuer_code`, `masked_payment_method` | 카드 발급사, 마스킹된 결제수단 표시 |
| `failure_reason`, `refund_reason` | 실패/환불 사유 |
| `requested_at`, `approved_at`, `refunded_at`, `updated_at` | 단계별 시각 |

**예시 데이터**

| point_charge_id | customer_id | requested_amount | approved_amount | status | failure_reason |
|---|---|---|---|---|---|
| 1 | 1 (김민준) | 20000 | 20000 | PAID | NULL |
| 2 | 3 (이지은) | 10000 | 10000 | PAID | NULL |
| 3 | 1 (김민준) | 5000 | NULL | FAILED | 카드 한도 초과 |

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| member | customer_id | N:1 | 여러 충전 건이 고객 1명을 가리킴(고객 쪽에서 보면 1:N) |
| point_transaction | point_charge_id | 1:N | 승인 시 입금 거래 1건, 이후 환불 시 별도 거래가 추가될 수 있어 충전 1건에 여러 포인트 거래가 연결될 수 있음 |

충전이 승인되면 `point_transaction`에 입금 건이 기록되고 `point_wallet.balance`가 증가하는 구조로 추정된다.

### 3.3 point_transaction

포인트의 모든 입출금을 기록하는 원장(ledger) 테이블.

| 컬럼 | 존재 이유 |
|---|---|
| `point_transaction_id` (PK) | 식별자 |
| `member_id` | FK(명시적) point_wallet(회원과 1:1인 지갑을 참조) |
| `transaction_type` | `CHARGE`(충전 승인)/`CHARGE_REFUND`(충전 환불)/`ORDER_USE`(배송요청 결제)/`ORDER_REFUND`(주문 취소 환불)/`SETTLEMENT`(정산)/`WITHDRAWAL`(출금)/`WITHDRAWAL_REFUND`(출금 실패 복구) 7종. 유형마다 `direction`과 참조 컬럼 종류가 고정된다(`ck_point_transaction_type_direction`, `ck_point_transaction_source`) |
| `direction` | `CREDIT`(적립)/`DEBIT`(차감) |
| `amount`, `balance_before`, `balance_after` | 매 거래마다 전/후 잔액을 남겨 잔액 이력을 완전히 재구성 가능하게 함(회계 감사 대응) |
| `request_key` | idempotency |
| `delivery_order_id`, `point_charge_id`, `rider_withdrawal_id`, `rider_settlement_id` (모두 NULL 허용) | 이 거래가 어떤 원인 이벤트에서 발생했는지 가리키는 폴리모픽 참조. 넷 중 하나만 값이 채워지는 방식으로, 별도 연결 테이블 대신 컬럼을 나열해 조회는 쉽게 하되 "하나만 값이 있어야 한다"는 제약은 애플리케이션이 책임짐 |
| `created_at` | 감사 |

**예시 데이터**

이 테이블이 가장 헷갈리는 이유는 "4개의 참조 컬럼 중 딱 하나만 채워진다"는 규칙이 글로만 보면 잘 안 그려지기 때문이다. 아래는 앞서 나온 point_charge, delivery_order, rider_settlement, rider_withdrawal 예시가 실제로 point_transaction에 어떻게 한 줄씩 찍히는지를 순서대로 보여준다. `-` 표시는 그 컬럼이 NULL이라는 뜻이다.

| id | member_id | transaction_type | direction | amount | balance_before | balance_after | delivery_order_id | point_charge_id | rider_withdrawal_id | rider_settlement_id | 이 거래가 채워진 이유 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 (김민준) | CHARGE | CREDIT | 20000 | 0 | 20000 | - | 1 | - | - | point_charge#1 충전이 승인됨 |
| 2 | 1 (김민준) | ORDER_USE | DEBIT | 4500 | 20000 | 15500 | 1001 | - | - | - | delivery_order#1001 완료 시 결제 차감 |
| 3 | 2 (박서준) | SETTLEMENT | CREDIT | 4500 | 0 | 4500 | - | - | - | 1 | rider_settlement#1(주문 1001) 확정 반영 |
| 4 | 2 (박서준) | WITHDRAWAL | DEBIT | 3000 | 4500 | 1500 | - | - | 1 | - | rider_withdrawal#1 신청 시 차감 |
| 5 | 2 (박서준) | SETTLEMENT | CREDIT | 5500 | 1500 | 7000 | - | - | - | 2 | rider_settlement#2(주문 1004) 확정 반영 |
| 6 | 2 (박서준) | WITHDRAWAL | DEBIT | 5500 | 7000 | 1500 | - | - | 2 | - | rider_withdrawal#2 신청 시 1차 차감 |
| 7 | 2 (박서준) | WITHDRAWAL_REFUND | CREDIT | 5500 | 1500 | 7000 | - | - | 2 | - | rider_withdrawal#2가 계좌 확인 실패로 되돌아와 포인트 복구 |

여기서 두 가지를 눈으로 확인할 수 있다.

1. 행마다 `delivery_order_id`/`point_charge_id`/`rider_withdrawal_id`/`rider_settlement_id` 중 정확히 1칸만 값이 있고 나머지 3칸은 `-`(NULL)이다. 어떤 원인으로 발생한 거래인지는 "어느 칸에 값이 있는가"로 구분한다.
2. id=6, 7은 `rider_withdrawal_id`가 똑같이 2다. 출금 신청 1건(withdrawal#2)에 대해 "일단 차감(6)" → "실패해서 복구(7)"로 거래가 2건 발생했다. 그래서 `rider_withdrawal`과 `point_transaction`의 관계가 1:1이 아니라 1:N인 것이다.

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| point_wallet | member_id (FK, 명시적) | N:1 | 여러 거래 행이 지갑 1개(=회원 1명)를 가리킴(지갑 쪽에서 보면 1:N) |
| delivery_order | delivery_order_id (FK, 명시적, NULL 허용) | N:1 | 결제 차감·환불처럼 주문 1건에 여러 거래가 생길 수 있음(주문 쪽에서 보면 1:N) |
| point_charge | point_charge_id (FK, 명시적, NULL 허용) | N:1 | 충전 1건에 승인 거래·환불 거래가 각각 생길 수 있음(충전 쪽에서 보면 1:N) |
| rider_withdrawal | rider_withdrawal_id (FK, 명시적, NULL 허용) | N:1 | 출금 신청 시 차감 거래, 실패 시 복구 거래가 따로 생길 수 있음(출금 쪽에서 보면 1:N) |
| rider_settlement | rider_settlement_id (FK, 명시적, NULL 허용) | 1:1 (NULL 허용) | 정산 확정 시 지급 포인트 반영 거래가 정확히 1건만 발생한다고 가정 |

이 4개 컬럼은 모두 NULL을 허용하며, 한 거래 행에는 이 중 정확히 하나만 값이 채워진다. 별도의 연결 테이블을 두지 않고 컬럼을 나열해 조회는 단순하게 유지했지만, "정확히 하나만 값이 있어야 한다"는 제약은 DB가 아니라 애플리케이션 로직이 지켜야 한다.

### 3.4 rider_settlement

완료된 주문에 대해 라이더에게 지급할 정산금. 별도의 정산 상태 컬럼을 두지 않는다 — 행의 존재 자체가 "정산 완료"를 의미한다. 정산 처리가 성공하면 INSERT되고, 실패하면 배송 완료 트랜잭션 전체가 롤백되어 행이 남지 않는다.

| 컬럼 | 존재 이유 |
|---|---|
| `settlement_id` (PK) | 식별자 |
| `order_id`, `rider_id` | FK(명시적, 각각 개별 제약) |
| `final_fare_snapshot_id` | FK(명시적, `order_id`와 결합한 복합키) order_fare_snapshot(어떤 확정금액 스냅샷 기준으로 정산했는지) |
| `settlement_amount` | 실제 지급 금액 |
| `settled_at` | 정산 시각 |

**예시 데이터**

| settlement_id | order_id | rider_id | final_fare_snapshot_id | settlement_amount |
|---|---|---|---|---|
| 1 | 1001 | 2 (박서준) | 2 | 4500 |
| 2 | 1004 | 2 (박서준) | 4 | 5500 |

`settlement_amount`가 각각 `order_fare_snapshot`의 FINAL 스냅샷(`fare_snapshot_id`=2, 4)의 `total_fare`와 같은 값인 것을 확인할 수 있다.

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| delivery_order | order_id | 1:1 | 완료된 주문 1건당 정산은 1번만 발생한다고 가정 |
| rider_profile | rider_id | N:1 | 여러 정산 건이 라이더 1명을 가리킴(라이더 쪽에서 보면 1:N) |
| order_fare_snapshot | final_fare_snapshot_id | 1:1 | `uk_rider_settlement_snapshot` UNIQUE로 스냅샷 1개당 정산 1건이 하드 제약으로 강제된다 |
| point_transaction | rider_settlement_id | 1:1 | 정산 확정 시 포인트 반영 거래가 정확히 1건 발생 |

정산이 확정되면 포인트 원장(`point_transaction`)에 반영된다.

### 3.5 rider_withdrawal

라이더의 정산 포인트 출금 신청.

| 컬럼 | 존재 이유 |
|---|---|
| `withdrawal_id` (PK) | 식별자 |
| `rider_id` | FK(명시적) |
| `request_key` | 중복 출금신청 방지 |
| `amount` | 출금 금액 |
| `bank_code_snapshot`, `masked_account_number_snapshot`, `account_holder_name_snapshot` | `rider_payout_account` 값을 신청 시점에 복사. 이후 계좌 정보가 바뀌어도 당시 송금 대상을 이력으로 보존 |
| `status` (기본 PENDING) | 진행 상태 |
| `failure_reason` (NULL 허용) | 실패 사유 |
| `points_restored` (기본 0) | 출금 실패 시 차감했던 포인트를 복구했는지 여부. 중복 복구를 막는 플래그 |
| `requested_at`, `processed_at`(NULL 허용) | 신청/처리 시각 |

**예시 데이터**

| withdrawal_id | rider_id | amount | status | failure_reason | points_restored |
|---|---|---|---|---|---|
| 1 | 2 (박서준) | 3000 | COMPLETED | NULL | 0 |
| 2 | 2 (박서준) | 5500 | FAILED | 계좌 확인 실패 | 1 |

withdrawal_id=2는 실패했지만 `points_restored=1`이라 차감했던 5500포인트가 되돌아왔다는 뜻이고, 이 복구가 바로 위 point_transaction 예시의 id=7 행이다.

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| rider_profile | rider_id | N:1 | 여러 출금 신청이 라이더 1명을 가리킴(라이더 쪽에서 보면 1:N) |
| rider_payout_account | (직접 FK 없음, 값 복제) | N:1 | 여러 출금 신청이 계좌 1개를 가리킴(계좌 쪽에서 보면 1:N). 신청 시점 계좌 정보를 `_snapshot` 컬럼에 복사해 이후 계좌 변경과 무관하게 이력 보존 |
| point_transaction | rider_withdrawal_id | 1:N | 출금 신청 시 차감 거래 1건, 실패 후 포인트를 복구할 때 복구 거래가 추가로 발생할 수 있음 |

출금 신청 시 포인트 차감이 원장에 기록되고, 실패 시 복구 처리(`points_restored`)와 연동된다.

---

## 4. 알림 영역

### 4.1 member_notification

회원에게 발송되는 알림 로그.

| 컬럼 | 존재 이유 |
|---|---|
| `notification_id` (PK) | 식별자 |
| `member_id` | 논리적 FK member(수신자) |
| `order_id` (NULL 허용) | 주문 관련 알림일 때만 값이 있고, 공통 알림은 NULL |
| `notification_type` | 알림 종류(배정완료, 정산완료 등) |
| `title`, `content` | 알림 내용 |
| `dedup_key` | 같은 이벤트로 인한 중복 발송을 막는 키. 재시도 로직에서 안전하게 재호출 가능 |
| `read_at` (NULL 허용) | 읽음 처리 |
| `created_at` | 발송 시각 |

**예시 데이터**

| notification_id | member_id | order_id | notification_type | title |
|---|---|---|---|---|
| 1 | 1 (김민준) | 1001 | RIDER_ASSIGNED | 라이더가 배정되었습니다 |
| 2 | 1 (김민준) | 1001 | DELIVERY_COMPLETED | 배송이 완료되었습니다 |
| 3 | 2 (박서준) | NULL | PROMOTION | 이번 주 프로모션 안내 |

notification_id=3은 특정 주문과 무관한 공통 알림이라 `order_id`가 NULL인 경우를 보여준다.

**관계**

| 대상 테이블 | 연결 컬럼 | 카디널리티 | 왜 이 카디널리티인가 |
|---|---|---|---|
| member | member_id | N:1 | 여러 알림이 수신자 1명을 가리킴(회원 쪽에서 보면 1:N) |
| delivery_order | order_id (NULL 허용) | N:1 | 여러 알림이 주문 1건을 가리킬 수 있고, 주문과 무관한 공통 알림은 NULL(주문 쪽에서 보면 1:N) |

---

## 5. 전체 흐름 요약

1. 회원가입 및 약관 동의(`member`, `term`, `member_term_agreement`). 라이더는 `rider_profile`, `rider_payout_account`를 추가 등록.
2. 고객이 `delivery_order` 생성 → 이 시점 `fare_policy`+`item_type_surcharge` 기준으로 `order_fare_snapshot`(예상 금액) 산정.
3. 라이더 배정 → `order_status_history`에 상태 전이 기록, `rider_location_history`로 위치 추적.
4. 배송 완료 → `delivery_proof` 등록, 주문 완료 상태로 전이.
5. 정산 → 확정된 `order_fare_snapshot` 기준으로 `rider_settlement` 생성 → `point_transaction`에 반영.
6. 고객은 `point_charge`로 포인트를 충전해 `point_wallet` 잔액을 채우고 주문에 사용, 라이더는 정산된 포인트를 `rider_withdrawal`로 출금 신청.
7. 주요 이벤트마다 `member_notification`이 발송됨.
