-- 배송 상태 전이 불변 이력
-- 배송 상태가 바뀔 때마다 한 행씩 append 하는 불변 로그(수정/삭제 없음).
-- (order_id, request_key) UNIQUE 로 동일 전이 요청 재전송을 멱등 처리한다.
-- actor_type=SYSTEM 이면 actor_member_id 는 NULL, CUSTOMER/RIDER 면 NOT NULL 이어야 한다
-- (ck_order_status_actor). 같은 상태로의 무의미한 전이는 ck_order_status_changed 로 막는다.

CREATE TABLE order_status_history (
    status_history_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    previous_status VARCHAR(30) NULL,
    new_status VARCHAR(30) NOT NULL,
    action VARCHAR(40) NOT NULL,
    actor_member_id BIGINT UNSIGNED NULL,
    actor_type VARCHAR(20) NOT NULL,
    reason VARCHAR(255) NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_order_status_history PRIMARY KEY (status_history_id),
    CONSTRAINT uk_order_status_request UNIQUE (order_id, request_key),
    CONSTRAINT fk_order_status_order
        FOREIGN KEY (order_id) REFERENCES delivery_order (order_id),
    CONSTRAINT fk_order_status_actor
        FOREIGN KEY (actor_member_id) REFERENCES member (member_id),
    CONSTRAINT ck_order_status_previous
        CHECK (
            previous_status IS NULL
            OR previous_status IN (
                'WAITING', 'ASSIGNED', 'MOVING_TO_PICKUP', 'PICKED_UP',
                'DELIVERING', 'COMPLETED', 'CANCELED'
            )
        ),
    CONSTRAINT ck_order_status_new
        CHECK (new_status IN (
            'WAITING', 'ASSIGNED', 'MOVING_TO_PICKUP', 'PICKED_UP',
            'DELIVERING', 'COMPLETED', 'CANCELED'
        )),
    CONSTRAINT ck_order_status_actor_type
        CHECK (actor_type IN ('CUSTOMER', 'RIDER', 'SYSTEM')),
    CONSTRAINT ck_order_status_actor
        CHECK (
            (actor_type = 'SYSTEM' AND actor_member_id IS NULL)
            OR
            (actor_type IN ('CUSTOMER', 'RIDER') AND actor_member_id IS NOT NULL)
        ),
    CONSTRAINT ck_order_status_changed
        CHECK (previous_status IS NULL OR previous_status <> new_status),

    KEY idx_order_status_time (order_id, changed_at),
    KEY idx_order_status_actor (actor_member_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '배송 상태 전이 불변 이력';
