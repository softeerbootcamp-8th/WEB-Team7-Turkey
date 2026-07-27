-- 주문 생성·완료 시점의 운임 산정 스냅샷
-- 주문당 ESTIMATE(예상)/FINAL(최종) 각 최대 1건 (uk_order_fare_type).
-- fare_policy 가 이후 바뀌어도 주문 시점의 산정 근거를 그대로 보존하는 불변(append-only) 레코드.
-- total_fare = base_fare + distance_fare + item_surcharge 를 DB CHECK 로 강제한다.
-- uk_order_fare_pair(order_id, fare_snapshot_id)는 4단계 rider_settlement 가 참조할
-- 복합 FK 대상이므로 함께 만든다(같은 주문의 스냅샷만 정산에 묶이도록 보장).

CREATE TABLE order_fare_snapshot (
    fare_snapshot_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    fare_policy_id BIGINT UNSIGNED NOT NULL,
    fare_type VARCHAR(10) NOT NULL,
    policy_version VARCHAR(30) NOT NULL,
    calculation_distance_meters INT UNSIGNED NOT NULL,
    base_fare BIGINT UNSIGNED NOT NULL,
    distance_fare BIGINT UNSIGNED NOT NULL,
    item_surcharge BIGINT UNSIGNED NOT NULL,
    total_fare BIGINT UNSIGNED NOT NULL,
    calculated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_order_fare_snapshot PRIMARY KEY (fare_snapshot_id),
    CONSTRAINT uk_order_fare_type UNIQUE (order_id, fare_type),
    CONSTRAINT uk_order_fare_pair UNIQUE (order_id, fare_snapshot_id),
    CONSTRAINT fk_order_fare_order
        FOREIGN KEY (order_id) REFERENCES delivery_order (order_id),
    CONSTRAINT fk_order_fare_policy
        FOREIGN KEY (fare_policy_id) REFERENCES fare_policy (fare_policy_id),
    CONSTRAINT ck_order_fare_type
        CHECK (fare_type IN ('ESTIMATE', 'FINAL')),
    CONSTRAINT ck_order_fare_distance
        CHECK (calculation_distance_meters > 0),
    CONSTRAINT ck_order_fare_total
        CHECK (total_fare = base_fare + distance_fare + item_surcharge),

    KEY idx_order_fare_policy (fare_policy_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '주문 생성·완료 시점의 운임 산정 스냅샷';
