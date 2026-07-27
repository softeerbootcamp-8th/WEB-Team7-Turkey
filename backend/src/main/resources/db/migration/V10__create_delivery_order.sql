-- 배송 요청 및 현재 배송 상태.
-- 주소·연락처·물품 종류는 주문 시점 값을 이 테이블에 스냅샷으로 보관한다 — 회원 정보가 나중에
-- 바뀌어도 과거 주문의 픽업지/수령인은 그대로 남아야 하기 때문이다.
--
-- 낙관적 락(version) 컬럼을 두지 않는다(팀 정책상 @Version 전면 폐기, V2/V5 와 동일).
-- 배차 확정의 동시성은 조건부 UPDATE(WHERE status='WAITING')와 아래 두 UNIQUE 가 함께 보장한다.
--
-- active_customer_id / active_rider_id 는 진행 중일 때만 값을 갖고 그 외에는 NULL 인 생성 컬럼이다.
-- MySQL UNIQUE 는 NULL 을 다건 허용하므로, 이 컬럼들의 UNIQUE 가 곧
--   "고객은 진행 중 배송요청 최대 1건" / "라이더는 진행 중 배송 최대 1건" 제약이 된다.
-- 두 컬럼의 상태 목록이 다른 이유: 배차 전(WAITING)에도 고객의 주문은 진행 중이지만,
-- 라이더는 배차(ASSIGNED)된 뒤부터 묶인다.
--
-- VIRTUAL 키워드를 붙이지 않는 이유는 V8 과 같다 — MySQL 생성 컬럼의 기본값이 VIRTUAL 이라
-- 동작이 동일한 반면, 로컬 개발용 H2(MySQL 호환 모드)는 이 키워드를 파싱하지 못한다.

CREATE TABLE delivery_order (
    order_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    customer_id BIGINT UNSIGNED NOT NULL,
    assigned_rider_id BIGINT UNSIGNED NULL,

    status VARCHAR(30) NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_type VARCHAR(30) NOT NULL,
    straight_distance_meters INT UNSIGNED NOT NULL,

    pickup_road_address VARCHAR(255) NOT NULL,
    pickup_detail_address VARCHAR(255) NULL,
    pickup_postal_code VARCHAR(10) NOT NULL,
    pickup_latitude DECIMAL(10,7) NOT NULL,
    pickup_longitude DECIMAL(10,7) NOT NULL,

    destination_road_address VARCHAR(255) NOT NULL,
    destination_detail_address VARCHAR(255) NULL,
    destination_postal_code VARCHAR(10) NOT NULL,
    destination_latitude DECIMAL(10,7) NOT NULL,
    destination_longitude DECIMAL(10,7) NOT NULL,

    sender_name VARCHAR(50) NOT NULL,
    sender_phone_number VARCHAR(20) NOT NULL,
    recipient_name VARCHAR(50) NOT NULL,
    recipient_phone_number VARCHAR(20) NOT NULL,

    requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    assigned_at DATETIME(3) NULL,
    moving_to_pickup_at DATETIME(3) NULL,
    picked_up_at DATETIME(3) NULL,
    delivering_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    canceled_at DATETIME(3) NULL,
    cancel_reason VARCHAR(255) NULL,

    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    active_customer_id BIGINT UNSIGNED
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN (
                    'WAITING',
                    'ASSIGNED',
                    'MOVING_TO_PICKUP',
                    'PICKED_UP',
                    'DELIVERING'
                ) THEN customer_id
                ELSE NULL
            END
        ),

    active_rider_id BIGINT UNSIGNED
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN (
                    'ASSIGNED',
                    'MOVING_TO_PICKUP',
                    'PICKED_UP',
                    'DELIVERING'
                ) THEN assigned_rider_id
                ELSE NULL
            END
        ),

    CONSTRAINT pk_delivery_order PRIMARY KEY (order_id),
    CONSTRAINT uk_delivery_customer_request
        UNIQUE (customer_id, request_key),
    CONSTRAINT uk_delivery_active_customer
        UNIQUE (active_customer_id),
    CONSTRAINT uk_delivery_active_rider
        UNIQUE (active_rider_id),

    CONSTRAINT fk_delivery_customer
        FOREIGN KEY (customer_id) REFERENCES member (member_id),
    CONSTRAINT fk_delivery_rider
        FOREIGN KEY (assigned_rider_id) REFERENCES rider_profile (member_id),

    CONSTRAINT ck_delivery_status
        CHECK (status IN (
            'WAITING',
            'ASSIGNED',
            'MOVING_TO_PICKUP',
            'PICKED_UP',
            'DELIVERING',
            'COMPLETED',
            'CANCELED'
        )),
    CONSTRAINT ck_delivery_item_type
        CHECK (item_type IN (
            'DOCUMENT', 'SMALL_PARCEL', 'MEDIUM_PARCEL', 'LARGE_PARCEL', 'FOOD'
        )),
    CONSTRAINT ck_delivery_distance
        CHECK (straight_distance_meters > 0),
    CONSTRAINT ck_delivery_pickup_lat
        CHECK (pickup_latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_delivery_pickup_lon
        CHECK (pickup_longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_delivery_dest_lat
        CHECK (destination_latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_delivery_dest_lon
        CHECK (destination_longitude BETWEEN -180 AND 180),

    -- 배차 전(WAITING)과 취소(CANCELED)에는 라이더가 없고, 배차 이후에는 반드시 있다.
    -- 이 제약이 "배차 이후 취소 없음"(OrderStatus 의 전이 규칙)을 DB 수준에서도 강제한다.
    CONSTRAINT ck_delivery_assignment
        CHECK (
            (
                status IN ('WAITING', 'CANCELED')
                AND assigned_rider_id IS NULL
                AND assigned_at IS NULL
            )
            OR
            (
                status IN (
                    'ASSIGNED',
                    'MOVING_TO_PICKUP',
                    'PICKED_UP',
                    'DELIVERING',
                    'COMPLETED'
                )
                AND assigned_rider_id IS NOT NULL
                AND assigned_at IS NOT NULL
            )
        ),
    CONSTRAINT ck_delivery_completed_at
        CHECK (
            (status = 'COMPLETED' AND completed_at IS NOT NULL)
            OR
            (status <> 'COMPLETED' AND completed_at IS NULL)
        ),
    CONSTRAINT ck_delivery_canceled_at
        CHECK (
            (status = 'CANCELED' AND canceled_at IS NOT NULL)
            OR
            (status <> 'CANCELED' AND canceled_at IS NULL)
        ),

    KEY idx_delivery_customer_requested (customer_id, requested_at DESC),
    KEY idx_delivery_rider_completed (assigned_rider_id, completed_at DESC),
    KEY idx_delivery_status_requested (status, requested_at),
    KEY idx_delivery_waiting_location
        (status, pickup_latitude, pickup_longitude)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '배송 요청 및 현재 배송 상태';
