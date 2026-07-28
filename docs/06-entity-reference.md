# 엔티티 레퍼런스

Turkey(퀵배송 매칭 서비스) 백엔드에 구현된 JPA 엔티티·임베더블을 도메인별로 정리한 레퍼런스다. 속성 제약은 엔티티 애너테이션·팩토리 검증·Flyway 마이그레이션(`V*.sql`)의 CHECK/UNIQUE/NOT NULL/FK 를 종합해 반영했다.

## 목차

- [회원 (member)](#회원-member)
  - [Member (`member`)](#member-member)
  - [Term (`term`)](#term-term)
  - [MemberTermAgreement (`member_term_agreement`)](#membertermagreement-member_term_agreement)
- [주문 (order)](#주문-order)
  - [DeliveryOrder (`delivery_order`)](#deliveryorder-delivery_order)
  - [OrderStatusHistory (`order_status_history`)](#orderstatushistory-order_status_history)
  - [DeliveryProof (`delivery_proof`)](#deliveryproof-delivery_proof)
  - [FarePolicy (`fare_policy`)](#farepolicy-fare_policy)
  - [ItemTypeSurcharge (`item_type_surcharge`)](#itemtypesurcharge-item_type_surcharge)
  - [OrderFareSnapshot (`order_fare_snapshot`)](#orderfaresnapshot-order_fare_snapshot)
  - [Address (임베더블)](#address-임베더블)
  - [Contact (임베더블)](#contact-임베더블)
- [결제·정산 (payment)](#결제정산-payment)
  - [PointWallet (`point_wallet`)](#pointwallet-point_wallet)
  - [PointCharge (`point_charge`)](#pointcharge-point_charge)
  - [PointTransaction (`point_transaction`)](#pointtransaction-point_transaction)
  - [RiderSettlement (`rider_settlement`)](#ridersettlement-rider_settlement)
- [라이더 (rider)](#라이더-rider)
  - [RiderProfile (`rider_profile`)](#riderprofile-rider_profile)
  - [RiderPayoutAccount (`rider_payout_account`)](#riderpayoutaccount-rider_payout_account)
  - [RiderWithdrawal (`rider_withdrawal`)](#riderwithdrawal-rider_withdrawal)
- [위치 (location)](#위치-location)
  - [RiderLocationHistory (`rider_location_history`)](#riderlocationhistory-rider_location_history)
- [열거형(enum) 요약](#열거형enum-요약)

---

## 회원 (member)

### Member (`member`)

**엔티티 설명**: 공통 회원 계정으로 인증(로그인)의 주체다. 역할별 부가 정보는 `rider_profile` 등 별도 테이블이 가진다. 상태 변경은 setter 가 아니라 행위 메서드(`withdraw` 등)로만 수행한다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| id | member_id | Long | PK | IDENTITY 자동 생성 |
| loginId | login_id | String | 로그인 아이디 | not null, length 50, updatable=false, UNIQUE(uk_member_login_id) |
| passwordHash | password_hash | String | 비밀번호 해시 | not null, length 100 |
| name | name | String | 이름 | not null, length 50 |
| phoneNumber | phone_number | String | 전화번호 | not null, length 20, UNIQUE(uk_member_phone_number) |
| role | role | MemberRole | 회원 역할 | not null, length 20, updatable=false, enum: CUSTOMER/RIDER, CHECK(ck_member_role): role ∈ {CUSTOMER, RIDER} |
| status | status | MemberStatus | 계정 상태 | not null, length 20, enum: ACTIVE/SUSPENDED/WITHDRAWN, CHECK(ck_member_status): status ∈ {ACTIVE, SUSPENDED, WITHDRAWN}, 기본 ACTIVE |
| createdAt | created_at | LocalDateTime | 생성 시각(UTC) | not null, updatable=false |
| updatedAt | updated_at | LocalDateTime | 수정 시각(UTC) | not null |
| withdrawnAt | withdrawn_at | LocalDateTime | 탈퇴 시각 | nullable, CHECK(ck_member_withdrawn_at): WITHDRAWN ⟺ withdrawn_at NOT NULL |

| 메서드 | 설명 |
|---|---|
| static create(loginId, passwordHash, name, phoneNumber, role) | 회원 생성. status 를 ACTIVE 로 시작한다 |
| withdraw() | 탈퇴 처리. 이미 WITHDRAWN 이면 예외. status=WITHDRAWN 과 withdrawnAt 을 함께 세팅한다 |
| changePassword(newPasswordHash) | 비밀번호 해시 교체 |
| isActive() | status 가 ACTIVE 인지 반환 |
| onCreate() @PrePersist | createdAt/updatedAt 을 UTC 로 기록 |
| onUpdate() @PreUpdate | updatedAt 을 UTC 로 갱신 |

### Term (`term`)

**엔티티 설명**: 버전별 회원가입 약관이다. 같은 `term_code` 라도 문구가 바뀌면 새 version 행을 추가하며 `(term_code, target_role, version)` 이 유일하다. 회원 동의가 특정 행(버전)을 가리키므로 원문은 사실상 불변이다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| id | term_id | Long | PK | IDENTITY 자동 생성 |
| termCode | term_code | String | 약관 코드 | not null, length 50, updatable=false, UNIQUE 조합(uk_term_code_role_version) |
| targetRole | target_role | TermTargetRole | 적용 대상 | not null, length 20, updatable=false, enum: COMMON/CUSTOMER/RIDER, CHECK(ck_term_target_role): target_role ∈ {COMMON, CUSTOMER, RIDER}, UNIQUE 조합 |
| title | title | String | 약관 제목 | not null, length 150 |
| content | content | String | 약관 본문 | not null, MEDIUMTEXT |
| version | version | String | 문서 버전 문자열(낙관적 락 아님) | not null, length 30, updatable=false, UNIQUE 조합 |
| required | is_required | boolean | 필수 동의 여부 | not null, TINYINT(1), updatable=false, CHECK(ck_term_required): is_required ∈ {0, 1} |
| active | is_active | boolean | 노출/사용 가능 여부 | not null, TINYINT(1), CHECK(ck_term_active): is_active ∈ {0, 1}, 기본 true |
| effectiveFrom | effective_from | LocalDateTime | 적용 시작 | not null |
| effectiveTo | effective_to | LocalDateTime | 적용 종료 | nullable, CHECK(ck_term_effective_period): NULL 또는 effective_from 이후 |
| createdAt | created_at | LocalDateTime | 생성 시각(UTC) | not null, updatable=false |

| 메서드 | 설명 |
|---|---|
| static create(termCode, targetRole, title, content, version, required, effectiveFrom, effectiveTo) | 신규 약관 버전 등록. active=true 로 생성 |
| deactivate() | 노출 중단(active=false) |
| isEffectiveAt(at) | 해당 시점에 활성이며 유효기간 내인지 판단 |
| onCreate() @PrePersist | createdAt 을 UTC 로 기록(불변 이력이라 updated_at 없음) |

### MemberTermAgreement (`member_term_agreement`)

**엔티티 설명**: 회원의 약관 버전별 동의 이력이다. 특정 term 행에 대한 동의/미동의를 기록하는 불변(append-only) 레코드이며, `(member, term)` 조합이 유니크라 같은 버전에 중복 기록되지 않는다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| id | agreement_id | Long | PK | IDENTITY 자동 생성 |
| member | member_id | FK→Member | 동의 회원 | @ManyToOne(LAZY), optional=false, updatable=false, UNIQUE 조합(uk_member_term_agreement) |
| term | term_id | FK→Term | 대상 약관 버전 | @ManyToOne(LAZY), optional=false, updatable=false, UNIQUE 조합 |
| agreed | agreed | boolean | 동의 여부 | not null, TINYINT, updatable=false, CHECK(ck_agreement_agreed): agreed ∈ {0, 1} |
| agreedAt | agreed_at | LocalDateTime | 동의 시각(UTC) | not null, updatable=false |

| 메서드 | 설명 |
|---|---|
| static create(member, term, agreed) | 특정 약관 버전에 대한 동의 이력 생성 |
| onCreate() @PrePersist | agreedAt 을 UTC 로 기록(불변 이력이라 updated_at 없음) |

---

## 주문 (order)

### DeliveryOrder (`delivery_order`)

**엔티티 설명**: 배송 요청 및 현재 배송 상태를 담는 애그리거트 루트다. 상태 흐름은 `WAITING → ASSIGNED → MOVING_TO_PICKUP → PICKED_UP → DELIVERING → COMPLETED` 이고 취소는 배차 전(WAITING)에서만 가능하다. 전이는 setter 가 아니라 행위 메서드로만 일어나며, 허용 여부는 `OrderStatus.canTransitionTo` 에 위임한다. 주소·연락처·물품 종류는 주문 시점 값을 스냅샷으로 보관한다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| id | order_id | Long | PK | IDENTITY 자동 생성 |
| customer | customer_id | FK→Member | 주문 고객 | @ManyToOne(LAZY), optional=false, updatable=false, 팩토리 null 금지 |
| assignedRider | assigned_rider_id | FK→RiderProfile | 배정 라이더(배차 전 null) | @ManyToOne(LAZY), assign() 에서만 세팅, CHECK(ck_delivery_assignment): WAITING·CANCELED 면 assigned_rider_id/assigned_at NULL, ASSIGNED 이상이면 둘 다 NOT NULL |
| status | status | OrderStatus | 배송 상태 | not null, length 30, enum 7종, CHECK(ck_delivery_status): status ∈ {WAITING, ASSIGNED, MOVING_TO_PICKUP, PICKED_UP, DELIVERING, COMPLETED, CANCELED}, 기본 WAITING |
| requestKey | request_key | String | 요청 멱등키(UUID) | not null, CHAR(36), length 36, updatable=false, UNIQUE(customer_id,request_key) |
| itemType | item_type | ItemType | 물품 종류 | not null, length 30, updatable=false, enum 5종, CHECK(ck_delivery_item_type): item_type ∈ {DOCUMENT, SMALL_PARCEL, MEDIUM_PARCEL, LARGE_PARCEL, FOOD} |
| straightDistanceMeters | straight_distance_meters | int | 직선거리(m) | not null, updatable=false, CHECK(ck_delivery_distance): straight_distance_meters > 0, 팩토리 양수 검증 |
| pickup | pickup_* | Address(@Embedded) | 픽업지 주소 | 좌표 CHECK(ck_delivery_pickup_lat): pickup_latitude ∈ [-90, 90], CHECK(ck_delivery_pickup_lon): pickup_longitude ∈ [-180, 180], 컬럼 updatable=false |
| destination | destination_* | Address(@Embedded) | 도착지 주소 | 좌표 CHECK(ck_delivery_dest_lat): destination_latitude ∈ [-90, 90], CHECK(ck_delivery_dest_lon): destination_longitude ∈ [-180, 180], 컬럼 updatable=false |
| sender | sender_* | Contact(@Embedded) | 보내는 사람 | not null, updatable=false |
| recipient | recipient_* | Contact(@Embedded) | 받는 사람 | not null, updatable=false |
| requestedAt | requested_at | LocalDateTime | 요청 시각(UTC) | not null, updatable=false |
| assignedAt | assigned_at | LocalDateTime | 배차 시각 | nullable, ck_delivery_assignment 조합 |
| movingToPickupAt | moving_to_pickup_at | LocalDateTime | 픽업 이동 시작 시각 | nullable |
| pickedUpAt | picked_up_at | LocalDateTime | 수령 시각 | nullable |
| deliveringAt | delivering_at | LocalDateTime | 배송 시작 시각 | nullable |
| completedAt | completed_at | LocalDateTime | 완료 시각 | nullable, CHECK(ck_delivery_completed_at): COMPLETED ⟺ NOT NULL |
| canceledAt | canceled_at | LocalDateTime | 취소 시각 | nullable, CHECK(ck_delivery_canceled_at): CANCELED ⟺ NOT NULL |
| cancelReason | cancel_reason | String | 취소 사유 | nullable, length 255 |
| updatedAt | updated_at | LocalDateTime | 수정 시각(UTC) | not null |

DB 전용 생성 컬럼(엔티티 미매핑): `active_customer_id`(WAITING~DELIVERING 시 customer_id, UNIQUE→고객 진행 중 1건), `active_rider_id`(ASSIGNED~DELIVERING 시 assigned_rider_id, UNIQUE→라이더 진행 중 1건).

| 메서드 | 설명 |
|---|---|
| static request(customer, requestKey, itemType, straightDistanceMeters, pickup, destination, sender, recipient) | 배송요청 생성. 필수값 검증 후 WAITING 으로 시작 |
| assign(rider) | WAITING→ASSIGNED. assignedRider/assignedAt 을 함께 세팅 |
| startMovingToPickup() | ASSIGNED→MOVING_TO_PICKUP |
| pickUp() | MOVING_TO_PICKUP→PICKED_UP |
| startDelivering() | PICKED_UP→DELIVERING |
| complete() | DELIVERING→COMPLETED |
| cancel(reason) | WAITING→CANCELED. canceledAt/cancelReason 세팅 |
| transitionTo(target) private | canTransitionTo 검증 후 status 변경(허용 안 되면 예외) |
| onCreate() @PrePersist | requestedAt/updatedAt 을 UTC 로 기록 |
| onUpdate() @PreUpdate | updatedAt 을 UTC 로 갱신 |

### OrderStatusHistory (`order_status_history`)

**엔티티 설명**: 배송 상태 전이 불변(append-only) 이력이다. 상태가 바뀔 때마다 한 행씩 기록하며 수정/삭제되지 않는다. `(order, request_key)` 유니크로 동일 전이 요청 재전송을 멱등 처리한다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| id | status_history_id | Long | PK | IDENTITY 자동 생성 |
| order | order_id | FK→DeliveryOrder | 대상 주문 | @ManyToOne(LAZY), optional=false, updatable=false, UNIQUE 조합(uk_order_status_request) |
| previousStatus | previous_status | OrderStatus | 전이 직전 상태(최초면 null) | nullable, length 30, updatable=false, CHECK(ck_order_status_previous): NULL 또는 배송 상태 7종 중 하나 |
| newStatus | new_status | OrderStatus | 전이 후 상태 | not null, length 30, updatable=false, CHECK(ck_order_status_new): new_status ∈ 배송 상태 7종, CHECK(ck_order_status_changed): previous_status IS NULL 또는 previous ≠ new |
| action | action | String | 유발 행위(자유 문자열) | not null, length 40, updatable=false, 팩토리 공백/40자 초과 금지 |
| actor | actor_member_id | FK→Member | 전이 주체(SYSTEM 이면 null) | @ManyToOne(LAZY), updatable=false, CHECK(ck_order_status_actor): SYSTEM 이면 actor_member_id NULL, CUSTOMER/RIDER 면 NOT NULL |
| actorType | actor_type | ActorType | 주체 종류 | not null, length 20, updatable=false, enum: CUSTOMER/RIDER/SYSTEM, CHECK(ck_order_status_actor_type): actor_type ∈ {CUSTOMER, RIDER, SYSTEM} |
| reason | reason | String | 사유 | nullable, length 255, updatable=false |
| requestKey | request_key | String | 멱등키(UUID) | not null, CHAR(36), length 36, updatable=false, UNIQUE 조합 |
| changedAt | changed_at | LocalDateTime | 기록 시각(UTC) | not null, updatable=false |

| 메서드 | 설명 |
|---|---|
| static create(order, previousStatus, newStatus, action, actorType, actor, reason, requestKey) | 전이 이력 생성. 필수값·SYSTEM↔actor 쌍(ck_order_status_actor)·동일 상태 전이 금지 검증 |
| onCreate() @PrePersist | changedAt 을 UTC 로 기록(불변 이력이라 updated_at 없음) |

### DeliveryProof (`delivery_proof`)

**엔티티 설명**: 주문당 배송 완료 인증 기록이다. 주문당 최대 1건(uk_delivery_proof_order)이며 생성 후 변경되지 않는 불변(append-only) 레코드다. 이미지 바이너리는 저장하지 않고 `proof_value` 에 참조값(URL/키/코드)만 보관한다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| id | delivery_proof_id | Long | PK | IDENTITY 자동 생성 |
| order | order_id | FK→DeliveryOrder | 대상 주문 | @ManyToOne(LAZY), optional=false, updatable=false, UNIQUE(uk_delivery_proof_order) |
| rider | rider_id | FK→RiderProfile | 인증 라이더 | @ManyToOne(LAZY), optional=false, updatable=false |
| proofType | proof_type | ProofType | 인증 방식 | not null, length 30, updatable=false, enum: PHOTO/RECIPIENT_CONFIRMATION/AUTH_CODE, CHECK(ck_delivery_proof_type): proof_type ∈ {PHOTO, RECIPIENT_CONFIRMATION, AUTH_CODE} |
| proofValue | proof_value | String | 인증 참조값 | not null, length 500, updatable=false, 팩토리 공백/500자 초과 금지 |
| createdAt | created_at | LocalDateTime | 생성 시각(UTC) | not null, updatable=false |

| 메서드 | 설명 |
|---|---|
| static create(order, rider, proofType, proofValue) | 배송 완료 인증 생성. 필수값·길이 검증 |
| onCreate() @PrePersist | createdAt 을 UTC 로 기록(불변 이력이라 updated_at 없음) |

### FarePolicy (`fare_policy`)

**엔티티 설명**: 거리 단위당 요금을 직접 저장하는 요금 정책의 애그리거트 루트다. `policy_version` 단위로 관리되고 동시에 ACTIVE 인 정책은 최대 1개다(DB 생성 컬럼 + UNIQUE 로 강제). 한 번 비활성화된 버전은 재활성화할 수 없고, 물품 할증은 `addSurcharge()` 로만 추가한다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| id | fare_policy_id | Long | PK | IDENTITY 자동 생성 |
| policyVersion | policy_version | String | 정책 버전 | not null, length 30, updatable=false, UNIQUE(uk_fare_policy_version), 팩토리 공백/30자 초과 금지 |
| baseFare | base_fare | long | 기본요금 | not null, updatable=false, CHECK(ck_fare_policy_base_fare): base_fare > 0, 팩토리 양수 검증 |
| distanceUnitMeters | distance_unit_meters | int | 거리 단위(m) | not null, updatable=false, CHECK(ck_fare_policy_distance_unit): distance_unit_meters > 0, DB 기본 1000 |
| distanceUnitFare | distance_unit_fare | long | 거리 단가 | not null, updatable=false, CHECK(ck_fare_policy_distance_fare): distance_unit_fare > 0 |
| maxDeliveryDistanceMeters | max_delivery_distance_meters | int | 최대 배송 거리(m) | not null, updatable=false, CHECK(ck_fare_policy_max_distance): max_delivery_distance_meters > 0 |
| status | status | FarePolicyStatus | 적용 상태 | not null, length 20, enum: ACTIVE/INACTIVE, CHECK(ck_fare_policy_status): status ∈ {ACTIVE, INACTIVE}, 기본 INACTIVE |
| effectiveFrom | effective_from | LocalDateTime | 적용 시작 | not null, updatable=false |
| effectiveTo | effective_to | LocalDateTime | 적용 종료 | nullable, CHECK(ck_fare_policy_effective_period): effective_to IS NULL 또는 effective_to > effective_from |
| createdAt | created_at | LocalDateTime | 생성 시각(UTC) | not null, updatable=false |
| surcharges | (item_type_surcharge.fare_policy_id) | List\<ItemTypeSurcharge\> | 물품 할증 목록 | @OneToMany(mappedBy=farePolicy), cascade ALL, orphanRemoval=true, LAZY |

DB 전용 생성 컬럼(엔티티 미매핑): `active_policy_marker`(ACTIVE 시 1, UNIQUE→활성 정책 최대 1건).

| 메서드 | 설명 |
|---|---|
| static create(policyVersion, baseFare, distanceUnitMeters, distanceUnitFare, maxDeliveryDistanceMeters, effectiveFrom) | 요금 정책 생성. 각 값 양수/길이 검증, INACTIVE 로 시작 |
| addSurcharge(itemType, surchargeAmount) | 물품 할증 추가. 음수 금액·중복 itemType 거부 후 ItemTypeSurcharge 생성 |
| activate() | INACTIVE→ACTIVE. 이미 비활성화된(effectiveTo 있는) 버전은 거부 |
| deactivate() | ACTIVE→INACTIVE. effectiveTo=now(UTC), effectiveFrom 이후여야 함 |
| getSurcharges() | 수정 불가능한(unmodifiable) 뷰 반환 |
| requireStatus(required, action) private | 현재 상태가 요구 상태와 다르면 예외 |
| onCreate() @PrePersist | createdAt 을 UTC 로 기록 |

### ItemTypeSurcharge (`item_type_surcharge`)

**엔티티 설명**: 요금 정책 버전별 물품 종류 할증이다. FarePolicy 없이는 존재 의미가 없는 하위 구성요소라 생성 경로가 package-private 으로 제한되며 `FarePolicy.addSurcharge()` 를 통해서만 만들어진다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| id | item_type_surcharge_id | Long | PK | IDENTITY 자동 생성 |
| farePolicy | fare_policy_id | FK→FarePolicy | 소속 정책 | @ManyToOne(LAZY), optional=false, updatable=false, UNIQUE 조합(uk_item_surcharge_policy_type) |
| itemType | item_type | ItemType | 물품 종류 | not null, length 30, updatable=false, enum 5종, CHECK(ck_item_surcharge_item_type): item_type ∈ {DOCUMENT, SMALL_PARCEL, MEDIUM_PARCEL, LARGE_PARCEL, FOOD}, UNIQUE 조합 |
| surchargeAmount | surcharge_amount | long | 할증 금액 | not null, updatable=false, DB 기본 0, 루트에서 음수 금지 검증 |
| createdAt | created_at | LocalDateTime | 생성 시각(UTC) | not null, updatable=false |

| 메서드 | 설명 |
|---|---|
| static create(farePolicy, itemType, surchargeAmount) package-private | FarePolicy.addSurcharge() 전용 생성 |
| onCreate() @PrePersist | createdAt 을 UTC 로 기록 |

### OrderFareSnapshot (`order_fare_snapshot`)

**엔티티 설명**: 주문 생성·완료 시점의 운임 산정 스냅샷이다. 요금 정책이 바뀌어도 주문 당시 산정 근거를 보존하는 불변(append-only) 레코드이며, 주문당 ESTIMATE/FINAL 각 최대 1건이다. `total_fare` 는 세 구성요소의 합으로 팩토리에서 직접 계산해 세팅한다(외부 입력 불가).

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| id | fare_snapshot_id | Long | PK | IDENTITY 자동 생성 |
| order | order_id | FK→DeliveryOrder | 대상 주문 | @ManyToOne(LAZY), optional=false, updatable=false, UNIQUE 조합(uk_order_fare_type, uk_order_fare_pair) |
| farePolicy | fare_policy_id | FK→FarePolicy | 산정 정책 | @ManyToOne(LAZY), optional=false, updatable=false |
| fareType | fare_type | FareType | 스냅샷 종류 | not null, length 10, updatable=false, enum: ESTIMATE/FINAL, CHECK(ck_order_fare_type): fare_type ∈ {ESTIMATE, FINAL}, UNIQUE(order_id,fare_type) |
| policyVersion | policy_version | String | 정책 버전 스냅샷 | not null, length 30, updatable=false, 팩토리 공백/30자 초과 금지 |
| calculationDistanceMeters | calculation_distance_meters | Integer | 산정 거리(m) | not null, updatable=false, CHECK(ck_order_fare_distance): calculation_distance_meters > 0, 팩토리 양수 검증 |
| baseFare | base_fare | Long | 기본요금 | not null, updatable=false, UNSIGNED, 팩토리 0 이상 |
| distanceFare | distance_fare | Long | 거리요금 | not null, updatable=false, UNSIGNED, 팩토리 0 이상 |
| itemSurcharge | item_surcharge | Long | 물품 할증 | not null, updatable=false, UNSIGNED, 팩토리 0 이상 |
| totalFare | total_fare | Long | 총 운임 | not null, updatable=false, CHECK(ck_order_fare_total): total_fare = base_fare + distance_fare + item_surcharge |
| calculatedAt | calculated_at | LocalDateTime | 산정 시각(UTC) | not null, updatable=false |

| 메서드 | 설명 |
|---|---|
| static create(order, farePolicy, fareType, policyVersion, calculationDistanceMeters, baseFare, distanceFare, itemSurcharge) | 운임 스냅샷 생성. 필수/양수/음수불가 검증 후 totalFare 를 합으로 자동 계산 |
| onCreate() @PrePersist | calculatedAt 을 UTC 로 기록(불변 이력이라 updated_at 없음) |

### Address (임베더블)

**엔티티 설명**: 주문에 스냅샷으로 저장되는 주소 값 타입(도로명+상세+우편번호+좌표)이다. `@Embeddable` 이며 `DeliveryOrder` 의 `pickup`·`destination` 에 `@Embedded` 되고, 실제 컬럼명은 사용처의 `@AttributeOverride` 로 지정된다.

| 속성(필드) | 컬럼(오버라이드 전 기본) | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| roadAddress | roadAddress | String | 도로명 주소 | not null, length 255, 팩토리 공백 금지 |
| detailAddress | detailAddress | String | 상세 주소(선택) | nullable, length 255 |
| postalCode | postalCode | String | 우편번호 | not null, length 10, 팩토리 공백 금지 |
| latitude | latitude | BigDecimal | 위도 | not null, precision 10, scale 7, 범위 -90~90 검증, scale 7 정규화 |
| longitude | longitude | BigDecimal | 경도 | not null, precision 10, scale 7, 범위 -180~180 검증, scale 7 정규화 |

| 메서드 | 설명 |
|---|---|
| static of(roadAddress, detailAddress, postalCode, latitude, longitude) | 주소 값 생성. 필수 텍스트·좌표 범위 검증 |
| normalize(value, name, min, max) private | 좌표 범위 검증 후 DB 컬럼과 같은 scale(7) HALF_UP 로 맞춤 |

### Contact (임베더블)

**엔티티 설명**: 주문에 스냅샷으로 저장되는 연락처 값 타입(이름+전화번호)이다. `@Embeddable` 이며 `DeliveryOrder` 의 `sender`·`recipient` 에 `@Embedded` 되고, 컬럼명은 `@AttributeOverride` 로 지정된다. 회원 정보가 바뀌어도 과거 주문 값이 유지되도록 값을 복사해 보관한다.

| 속성(필드) | 컬럼(오버라이드 전 기본) | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| name | name | String | 이름 | not null, length 50, 팩토리 공백 금지 |
| phoneNumber | phoneNumber | String | 전화번호 | not null, length 20, 팩토리 공백 금지 |

| 메서드 | 설명 |
|---|---|
| static of(name, phoneNumber) | 연락처 값 생성. 이름·전화번호 공백 금지 검증 |

---

## 결제·정산 (payment)

### PointWallet (`point_wallet`)

**엔티티 설명**: 회원별 현재 포인트 잔액이다. `member` 와 PK(member_id)를 공유(@MapsId)하며 회원당 지갑 1개다. 낙관적 락을 두지 않고, 경쟁 갱신은 서비스 계층의 조건부 UPDATE 가 담당한다. 잔액의 정본이며 `PointTransaction` 이 그 변화를 원장으로 남긴다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| memberId | member_id | Long | PK(공유 PK) | member.id 에서 @MapsId 로 파생 |
| member | member_id | FK→Member | 소속 회원 | @OneToOne(LAZY), @MapsId, optional=false, FK(fk_point_wallet_member) |
| balance | balance | long | 포인트 잔액 | not null, UNSIGNED, DB 기본 0 |
| updatedAt | updated_at | LocalDateTime | 수정 시각(UTC) | not null (created_at 없음) |

| 메서드 | 설명 |
|---|---|
| static create(member) | 잔액 0 인 지갑 생성 |
| credit(amount) | 잔액 증가(양수 검증) |
| debit(amount) | 잔액 감소(양수 검증, 잔액 부족 시 예외) |
| requirePositive(amount) private | 금액 양수 검증 |
| onCreate() @PrePersist / onUpdate() @PreUpdate | updatedAt 을 UTC 로 기록/갱신 |

### PointCharge (`point_charge`)

**엔티티 설명**: 고객 포인트 충전 결제 및 전액 환불 상태다. `PENDING` 에서 시작해 approve→PAID / fail→FAILED / cancel→CANCELED 로 갈리고, PAID 는 refund→REFUNDED 로만 전이한다(부분 환불 없음). 상태별 금액·시각 조합은 DB `ck_point_charge_state_values` 와 전이 메서드가 함께 강제한다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| id | point_charge_id | Long | PK | IDENTITY 자동 생성 |
| customer | customer_id | FK→Member | 충전 고객 | @ManyToOne(LAZY), optional=false, updatable=false, UNIQUE 조합(uk_point_charge_customer_request) |
| chargeRequestKey | charge_request_key | String | 충전 멱등키(UUID) | not null, CHAR(36), length 36, updatable=false, UNIQUE 조합 |
| paymentMethod | payment_method | PaymentMethod | 결제 수단 | not null, length 20, updatable=false, enum: CARD/BANK_TRANSFER, CHECK(ck_point_charge_method): payment_method ∈ {CARD, BANK_TRANSFER} |
| paymentProvider | payment_provider | String | PG 제공자 | nullable, length 30 |
| providerPaymentKey | provider_payment_key | String | PG 승인키 | nullable, length 100, UNIQUE(uk_point_charge_provider_payment) |
| providerRefundKey | provider_refund_key | String | PG 환불키 | nullable, length 100, UNIQUE(uk_point_charge_provider_refund) |
| requestedAmount | requested_amount | long | 요청 금액 | not null, updatable=false, UNSIGNED, CHECK(ck_point_charge_requested_amount): requested_amount > 0, 팩토리 양수 검증 |
| approvedAmount | approved_amount | Long | 승인 금액 | nullable, CHECK(ck_point_charge_state_values): PENDING/FAILED/CANCELED=미승인·미환불, PAID=승인(approved_amount=requested_amount)·미환불, REFUNDED=승인·전액환불 |
| refundedAmount | refunded_amount | long | 환불 금액 | not null, UNSIGNED, DB 기본 0, CHECK(ck_point_charge_refund_amount): refunded_amount ≤ COALESCE(approved_amount, 0) |
| status | status | PointChargeStatus | 결제 상태 | not null, length 20, enum 5종, CHECK(ck_point_charge_status): status ∈ {PENDING, PAID, FAILED, CANCELED, REFUNDED} |
| issuerCode | issuer_code | String | 발급사 코드 | nullable, length 20 |
| maskedPaymentMethod | masked_payment_method | String | 마스킹 수단 | nullable, length 100 |
| failureReason | failure_reason | String | 실패 사유 | nullable, length 255 |
| refundReason | refund_reason | String | 환불 사유 | nullable, length 255 |
| requestedAt | requested_at | LocalDateTime | 요청 시각(UTC) | not null, updatable=false |
| approvedAt | approved_at | LocalDateTime | 승인 시각 | nullable, CHECK(ck_point_charge_state_values): PAID/REFUNDED 면 NOT NULL, 그 외 NULL |
| refundedAt | refunded_at | LocalDateTime | 환불 시각 | nullable, CHECK(ck_point_charge_state_values): REFUNDED 면 NOT NULL, 그 외 NULL |
| updatedAt | updated_at | LocalDateTime | 수정 시각(UTC) | not null |

| 메서드 | 설명 |
|---|---|
| static request(customer, chargeRequestKey, paymentMethod, requestedAmount, paymentProvider) | 충전 요청 생성(양수 검증), PENDING 시작, refundedAmount=0 |
| approve(providerPaymentKey, issuerCode, maskedPaymentMethod) | PENDING→PAID. approvedAmount=requestedAmount, approvedAt 세팅 |
| fail(failureReason) | PENDING→FAILED |
| cancel() | PENDING→CANCELED |
| refund(providerRefundKey, refundReason) | PAID→REFUNDED. refundedAmount=approvedAmount, refundedAt 세팅 |
| requireStatus(required, action) private | 현재 상태 검증 |
| onCreate() @PrePersist / onUpdate() @PreUpdate | requestedAt/updatedAt 기록·갱신(UTC) |

### PointTransaction (`point_transaction`)

**엔티티 설명**: 회원 포인트 증감 불변(append-only) 원장이다. 잔액을 직접 바꾸지 않고 확정된 변화를 기록만 하며, 수정 메서드도 updated_at 도 없다. `direction`·`balanceAfter` 를 인자로 받지 않고 유형·방향·금액에서 파생해 DB 의 세 CHECK 를 구조적으로 보장한다. 소스별 팩토리가 자기 FK 하나만 세팅한다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| id | point_transaction_id | Long | PK | IDENTITY 자동 생성 |
| wallet | member_id | FK→PointWallet | 대상 지갑 | @ManyToOne(LAZY), optional=false, updatable=false, FK→point_wallet(member_id) |
| transactionType | transaction_type | PointTransactionType | 거래 유형 | not null, length 30, updatable=false, enum 7종, CHECK(ck_point_transaction_type): transaction_type ∈ {CHARGE, CHARGE_REFUND, ORDER_USE, ORDER_REFUND, SETTLEMENT, WITHDRAWAL, WITHDRAWAL_REFUND} |
| direction | direction | PointDirection | 증감 방향(유형에서 파생) | not null, length 10, updatable=false, enum: CREDIT/DEBIT, CHECK(ck_point_transaction_direction): direction ∈ {CREDIT, DEBIT}, CHECK(ck_point_transaction_type_direction): CHARGE·ORDER_REFUND·SETTLEMENT·WITHDRAWAL_REFUND ⇒ CREDIT, CHARGE_REFUND·ORDER_USE·WITHDRAWAL ⇒ DEBIT |
| amount | amount | long | 거래 금액 | not null, updatable=false, UNSIGNED, CHECK(ck_point_transaction_amount): amount > 0, 팩토리 양수 검증 |
| balanceBefore | balance_before | long | 변경 전 잔액 | not null, updatable=false, UNSIGNED, 팩토리 0 이상 |
| balanceAfter | balance_after | long | 변경 후 잔액(계산값) | not null, updatable=false, UNSIGNED, CHECK(ck_point_transaction_balance): CREDIT 면 balance_after = balance_before + amount, DEBIT 면 balance_before = balance_after + amount |
| requestKey | request_key | String | 멱등키(UUID) | not null, CHAR(36), length 36, updatable=false, 전역 UNIQUE(uk_point_transaction_request) |
| deliveryOrder | delivery_order_id | FK→DeliveryOrder | 주문 소스 | @ManyToOne(LAZY), nullable, updatable=false, UNIQUE(delivery_order_id,type), CHECK(ck_point_transaction_source): ORDER_USE/ORDER_REFUND 이면 NOT NULL, 그 외 NULL |
| pointCharge | point_charge_id | FK→PointCharge | 충전 소스 | @ManyToOne(LAZY), nullable, updatable=false, UNIQUE(point_charge_id,type) |
| riderWithdrawal | rider_withdrawal_id | FK→RiderWithdrawal | 출금 소스 | @ManyToOne(LAZY), nullable, updatable=false, UNIQUE(rider_withdrawal_id,type) |
| riderSettlement | rider_settlement_id | FK→RiderSettlement | 정산 소스 | @ManyToOne(LAZY), nullable, updatable=false, UNIQUE(rider_settlement_id,type) |
| createdAt | created_at | LocalDateTime | 생성 시각(UTC) | not null, updatable=false |

소스 규칙(ck_point_transaction_source): 유형에 맞는 FK 정확히 하나만 NOT NULL, 나머지는 NULL.

| 메서드 | 설명 |
|---|---|
| static forCharge(wallet, type, amount, balanceBefore, requestKey, pointCharge) | CHARGE/CHARGE_REFUND 원장. 소스 종류 검증, pointCharge 세팅 |
| static forOrder(wallet, type, amount, balanceBefore, requestKey, deliveryOrder) | ORDER_USE/ORDER_REFUND 원장 |
| static forSettlement(wallet, amount, balanceBefore, requestKey, riderSettlement) | SETTLEMENT 원장(유형 고정) |
| static forWithdrawal(wallet, type, amount, balanceBefore, requestKey, riderWithdrawal) | WITHDRAWAL/WITHDRAWAL_REFUND 원장 |
| requireSource(type, expected) private | 팩토리와 유형의 소스 종류 일치 검증 |
| computeBalanceAfter(direction, before, amount) private | 방향·금액으로 변경 후 잔액 도출(DEBIT 잔액부족 거부) |
| onCreate() @PrePersist | createdAt 을 UTC 로 기록(불변 원장이라 updated_at 없음) |

### RiderSettlement (`rider_settlement`)

**엔티티 설명**: 완료 주문당 라이더 정산이다. 별도 상태 컬럼 없이 행의 존재 자체가 완료된 정산을 의미하는 불변 레코드다(전이 메서드·updated_at 없음). 정산 근거는 반드시 같은 주문의 FINAL 운임 스냅샷이어야 하며, 주문·라이더를 스냅샷에서 파생시켜 어긋난 조합을 원천 차단한다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| id | settlement_id | Long | PK | IDENTITY 자동 생성 |
| finalFareSnapshot | order_id, final_fare_snapshot_id | FK→OrderFareSnapshot | 근거 FINAL 스냅샷(복합 FK) | @ManyToOne(LAZY), optional=false, updatable=false, 복합 FK(fk_rider_settlement_fare), UNIQUE(final_fare_snapshot_id) |
| order | order_id | FK→DeliveryOrder | 대상 주문(읽기전용) | @ManyToOne(LAZY), insertable/updatable=false, UNIQUE(uk_rider_settlement_order) |
| rider | rider_id | FK→RiderProfile | 정산 대상 라이더 | @ManyToOne(LAZY), optional=false, updatable=false, 스냅샷의 주문에서 파생 |
| settlementAmount | settlement_amount | long | 정산 금액 | not null, updatable=false, UNSIGNED, CHECK(ck_rider_settlement_amount): settlement_amount > 0, 팩토리 양수 검증 |
| settledAt | settled_at | LocalDateTime | 정산 시각(UTC) | not null, updatable=false |

| 메서드 | 설명 |
|---|---|
| static settle(finalFareSnapshot, settlementAmount) | 정산 생성. FINAL 여부·주문 존재·배차 여부·양수 검증, 주문/라이더를 스냅샷에서 파생 |
| onCreate() @PrePersist | settledAt 을 UTC 로 기록 |

---

## 라이더 (rider)

### RiderProfile (`rider_profile`)

**엔티티 설명**: 라이더 운행 프로필이다. `member` 와 PK(member_id)를 공유(@MapsId)하며 자체 식별자를 두지 않는다. 운행 상태 전이는 행위 메서드로만 수행하고 허용되지 않은 전이는 예외로 거부하며, 상태가 바뀌면 `status_changed_at` 을 함께 갱신한다. 낙관적 락을 두지 않고 배차 정합성은 조건부 UPDATE 와 delivery_order UNIQUE 로 지킨다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| memberId | member_id | Long | PK(공유 PK) | member.id 에서 @MapsId 로 파생 |
| member | member_id | FK→Member | 소속 회원 | @OneToOne(LAZY), @MapsId, optional=false, FK(fk_rider_profile_member) |
| operatingStatus | operating_status | OperatingStatus | 운행 상태 | not null, length 20, enum: UNAVAILABLE/AVAILABLE/BUSY, CHECK(ck_rider_operating_status): operating_status ∈ {UNAVAILABLE, AVAILABLE, BUSY}, 기본 UNAVAILABLE |
| statusChangedAt | status_changed_at | LocalDateTime | 상태 변경 시각 | not null |
| createdAt | created_at | LocalDateTime | 생성 시각(UTC) | not null, updatable=false |
| updatedAt | updated_at | LocalDateTime | 수정 시각(UTC) | not null |

| 메서드 | 설명 |
|---|---|
| static create(member) | 프로필 생성. UNAVAILABLE 로 시작 |
| goOnline() | UNAVAILABLE→AVAILABLE |
| goOffline() | AVAILABLE→UNAVAILABLE(BUSY 에서는 불가) |
| assign() | AVAILABLE→BUSY(배차 확정) |
| release() | BUSY→AVAILABLE(배송 완료) |
| isAvailable() | AVAILABLE 인지 반환 |
| transitionTo(target, allowedFrom...) private | 허용 출발 상태에서만 전이하고 statusChangedAt 갱신(아니면 예외) |
| onCreate() @PrePersist / onUpdate() @PreUpdate | createdAt/updatedAt 기록·갱신(UTC) |

### RiderPayoutAccount (`rider_payout_account`)

**엔티티 설명**: 라이더 활성 출금 계좌다. `rider_profile` 과 PK(rider_id=member_id)를 공유(@MapsId)하며 라이더당 활성 계좌 1개다. 계좌번호 원문은 저장하지 않고 암호문 바이트만 보관하며, 화면 표시는 마스킹 값을 사용한다. 계좌 변경은 새 행이 아니라 in-place 교체다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| riderId | rider_id | Long | PK(공유 PK) | rider.memberId 에서 @MapsId 로 파생 |
| rider | rider_id | FK→RiderProfile | 소속 라이더 | @OneToOne(LAZY), @MapsId, optional=false, FK(fk_rider_payout_account_rider) |
| bankCode | bank_code | String | 은행 코드 | not null, length 20 |
| accountNumberCiphertext | account_number_ciphertext | byte[] | 계좌번호 암호문 | not null, VARBINARY(512) |
| maskedAccountNumber | masked_account_number | String | 마스킹 계좌번호 | not null, length 50 |
| accountHolderName | account_holder_name | String | 예금주명 | not null, length 50 |
| createdAt | created_at | LocalDateTime | 생성 시각(UTC) | not null, updatable=false |
| updatedAt | updated_at | LocalDateTime | 수정 시각(UTC) | not null |

| 메서드 | 설명 |
|---|---|
| static register(rider, bankCode, accountNumberCiphertext, maskedAccountNumber, accountHolderName) | 출금 계좌 최초 등록 |
| changeAccount(bankCode, accountNumberCiphertext, maskedAccountNumber, accountHolderName) | 계좌 in-place 교체 |
| onCreate() @PrePersist / onUpdate() @PreUpdate | createdAt/updatedAt 기록·갱신(UTC) |

### RiderWithdrawal (`rider_withdrawal`)

**엔티티 설명**: 라이더 포인트 출금 요청 및 실패 복구 상태다. `PENDING` 에서 시작해 complete→COMPLETED 또는 fail→FAILED 로 끝나며, 한 번 처리된 출금은 재처리하지 않는다(재시도는 새 request_key). 선차감 모델이라 fail() 은 `points_restored` 를 세우고, 실제 포인트 복구는 같은 트랜잭션에서 수행해야 한다. 계좌 정보는 요청 시점 스냅샷을 복사해 보관한다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| id | withdrawal_id | Long | PK | IDENTITY 자동 생성 |
| rider | rider_id | FK→RiderProfile | 출금 라이더 | @ManyToOne(LAZY), optional=false, updatable=false, UNIQUE 조합(uk_rider_withdrawal_request) |
| requestKey | request_key | String | 멱등키(UUID) | not null, CHAR(36), length 36, updatable=false, UNIQUE 조합 |
| amount | amount | long | 출금 금액 | not null, updatable=false, UNSIGNED, CHECK(ck_rider_withdrawal_amount): amount > 0, 팩토리 양수 검증 |
| bankCodeSnapshot | bank_code_snapshot | String | 은행 코드 스냅샷 | not null, length 20, updatable=false |
| maskedAccountNumberSnapshot | masked_account_number_snapshot | String | 마스킹 계좌 스냅샷 | not null, length 50, updatable=false |
| accountHolderNameSnapshot | account_holder_name_snapshot | String | 예금주명 스냅샷 | not null, length 50, updatable=false |
| status | status | WithdrawalStatus | 출금 상태 | not null, length 20, enum: PENDING/COMPLETED/FAILED, CHECK(ck_rider_withdrawal_status): status ∈ {PENDING, COMPLETED, FAILED}, 기본 PENDING |
| failureReason | failure_reason | String | 실패 사유 | nullable, length 255 |
| pointsRestored | points_restored | boolean | 포인트 복구 여부 | not null, TINYINT, CHECK(ck_rider_withdrawal_restored): points_restored ∈ {0, 1}, CHECK(ck_rider_withdrawal_state_values): FAILED ⟺ points_restored=1 |
| requestedAt | requested_at | LocalDateTime | 요청 시각(UTC) | not null, updatable=false |
| processedAt | processed_at | LocalDateTime | 처리 시각 | nullable, CHECK(ck_rider_withdrawal_state_values): PENDING 이면 NULL, COMPLETED·FAILED 면 NOT NULL |

| 메서드 | 설명 |
|---|---|
| static request(account, requestKey, amount) | 출금 요청 생성. 계좌에서 라이더·스냅샷 복사, 양수 검증, PENDING 시작, pointsRestored=false |
| complete() | PENDING→COMPLETED. processedAt 세팅(복구 플래그 false 유지) |
| fail(failureReason) | PENDING→FAILED. pointsRestored=true, processedAt 세팅 |
| requirePending(action) private | PENDING 이 아니면 예외 |
| onCreate() @PrePersist | requestedAt 을 UTC 로 기록(updated_at 없음) |

---

## 위치 (location)

### RiderLocationHistory (`rider_location_history`)

**엔티티 설명**: DELIVERING 구간의 선별된 실제 운행 위치를 남기는 불변(append-only) 레코드다(전이 메서드·updated_at 없음). 최신 위치의 정본은 Redis 이고 이 테이블에는 이력으로 남길 지점만 선별해 넣는다(선별·삽입 정책은 서비스 계층). 라이더는 주문의 배정 라이더에서 파생하며, `(order_id, measured_at)` 유니크로 같은 좌표 배치 재전송을 멱등 처리한다.

| 속성(필드) | 컬럼 | 타입 | 설명 | 제약·조건 |
|---|---|---|---|---|
| id | location_history_id | Long | PK | IDENTITY 자동 생성 |
| order | order_id | FK→DeliveryOrder | 대상 주문 | @ManyToOne(LAZY), optional=false, updatable=false, UNIQUE 조합(uk_rider_location_order_time) |
| rider | rider_id | FK→RiderProfile | 운행 라이더(주문에서 파생) | @ManyToOne(LAZY), optional=false, updatable=false |
| latitude | latitude | BigDecimal | 위도 | not null, precision 10, scale 7, updatable=false, CHECK(ck_rider_location_lat): latitude ∈ [-90, 90], scale 정규화 |
| longitude | longitude | BigDecimal | 경도 | not null, precision 10, scale 7, updatable=false, CHECK(ck_rider_location_lon): longitude ∈ [-180, 180], scale 정규화 |
| accuracyMeters | accuracy_meters | BigDecimal | 위치 정확도(m) | nullable, precision 7, scale 2, updatable=false, CHECK(ck_rider_location_accuracy): accuracy_meters IS NULL 또는 accuracy_meters ≥ 0 |
| measuredAt | measured_at | LocalDateTime | 단말 측정 시각 | not null, updatable=false, UNIQUE 조합 |
| storedAt | stored_at | LocalDateTime | 서버 저장 시각(UTC) | not null, updatable=false |

| 메서드 | 설명 |
|---|---|
| static record(order, latitude, longitude, accuracyMeters, measuredAt) | 위치 1건 기록. 주문·배차·측정시각 검증, 라이더를 주문에서 파생, 좌표/정확도 정규화 |
| normalizeCoordinate(value, name, min, max) private | 좌표 범위 검증 후 scale(7) HALF_UP 정규화 |
| normalizeAccuracy(value) private | null 허용, 음수 거부, scale(2) HALF_UP 정규화 |
| onCreate() @PrePersist | storedAt 을 UTC 로 기록 |

---

## 열거형(enum) 요약

| enum | 도메인 | 허용값 |
|---|---|---|
| MemberRole | member | CUSTOMER, RIDER |
| MemberStatus | member | ACTIVE, SUSPENDED, WITHDRAWN |
| TermTargetRole | member | COMMON, CUSTOMER, RIDER |
| OrderStatus | order | WAITING, ASSIGNED, MOVING_TO_PICKUP, PICKED_UP, DELIVERING, COMPLETED, CANCELED |
| ActorType | order | CUSTOMER, RIDER, SYSTEM |
| ProofType | order | PHOTO, RECIPIENT_CONFIRMATION, AUTH_CODE |
| FareType | order | ESTIMATE, FINAL |
| FarePolicyStatus | order | ACTIVE, INACTIVE |
| ItemType | order | DOCUMENT, SMALL_PARCEL, MEDIUM_PARCEL, LARGE_PARCEL, FOOD |
| PaymentMethod | payment | CARD, BANK_TRANSFER |
| PointChargeStatus | payment | PENDING, PAID, FAILED, CANCELED, REFUNDED |
| PointDirection | payment | CREDIT, DEBIT |
| PointTransactionType | payment | CHARGE(CREDIT/CHARGE), CHARGE_REFUND(DEBIT/CHARGE), ORDER_USE(DEBIT/ORDER), ORDER_REFUND(CREDIT/ORDER), SETTLEMENT(CREDIT/SETTLEMENT), WITHDRAWAL(DEBIT/WITHDRAWAL), WITHDRAWAL_REFUND(CREDIT/WITHDRAWAL) |
| OperatingStatus | rider | UNAVAILABLE, AVAILABLE, BUSY |
| WithdrawalStatus | rider | PENDING, COMPLETED, FAILED |

`PointTransactionType` 은 각 값이 `(방향, 소스종류)` 를 함께 가진다(위 괄호 표기). 방향은 `direction()`, 소스는 `sourceKind()` 로 파생되며 DB 의 type↔direction / source CHECK 와 대응한다.
