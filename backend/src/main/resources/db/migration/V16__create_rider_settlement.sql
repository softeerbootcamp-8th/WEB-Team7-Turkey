-- 완료 주문당 라이더 정산.
--
-- 별도의 정산 상태 컬럼을 두지 않는다 — 행의 존재 자체가 "정산 완료"를 의미한다.
-- 성공하면 INSERT 되고, 실패하면 배송 완료 트랜잭션 전체가 롤백되어 행이 남지 않는다.
--
-- final_fare_snapshot_id 는 단독 FK 가 아니라 (order_id, final_fare_snapshot_id) 복합 FK 로
-- order_fare_snapshot(order_id, fare_snapshot_id)를 참조한다. 단독으로 걸면 "A 주문의 정산이
-- B 주문의 운임 스냅샷을 가리키는" 조합이 DB 수준에서 막히지 않기 때문이다.
-- (대상 유니크는 V12 의 uk_order_fare_pair 다.)
--
-- uk_rider_settlement_order: 주문당 정산 최대 1건 → 배송 완료 재시도에도 중복 정산이 없다.
-- uk_rider_settlement_snapshot: 하나의 운임 스냅샷이 두 정산에 재사용되지 않는다.

CREATE TABLE rider_settlement (
    settlement_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    rider_id BIGINT UNSIGNED NOT NULL,
    final_fare_snapshot_id BIGINT UNSIGNED NOT NULL,
    settlement_amount BIGINT UNSIGNED NOT NULL,
    settled_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_rider_settlement PRIMARY KEY (settlement_id),
    CONSTRAINT uk_rider_settlement_order UNIQUE (order_id),
    CONSTRAINT uk_rider_settlement_snapshot UNIQUE (final_fare_snapshot_id),
    CONSTRAINT fk_rider_settlement_order
        FOREIGN KEY (order_id) REFERENCES delivery_order (order_id),
    CONSTRAINT fk_rider_settlement_rider
        FOREIGN KEY (rider_id) REFERENCES rider_profile (member_id),
    CONSTRAINT fk_rider_settlement_fare
        FOREIGN KEY (order_id, final_fare_snapshot_id)
        REFERENCES order_fare_snapshot (order_id, fare_snapshot_id),
    CONSTRAINT ck_rider_settlement_amount
        CHECK (settlement_amount > 0),

    KEY idx_rider_settlement_rider (rider_id, settled_at DESC)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '완료 주문당 라이더 정산';
