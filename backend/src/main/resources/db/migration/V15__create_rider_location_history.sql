-- DELIVERING 구간의 선별된 실제 운행 위치.
--
-- delivery_status 컬럼을 두지 않는다 — 이 테이블은 DELIVERING 구간만 저장한다는 삽입 정책으로
-- 고정되므로, 모든 행이 같은 값을 갖는 컬럼을 둘 이유가 없다.
--
-- 라이더의 "최신 위치"는 Redis 가 정본이고, 여기에는 이력으로 남길 가치가 있는 지점만 선별해
-- 넣는다(전 지점을 다 넣지 않는다). 선별 기준(시간·이동거리)은 서비스 계층 정책이다.
--
-- uk_rider_location_order_time 은 같은 주문의 같은 측정 시각이 두 번 저장되는 것을 막는다 —
-- 모바일 클라이언트가 같은 좌표 배치를 재전송해도 중복 행이 생기지 않는다(멱등성).
-- 이 유니크 인덱스가 (order_id, measured_at) 순서라 "주문별 시각순 조회"의 인덱스 역할도 하고,
-- fk_rider_location_order 가 필요로 하는 order_id 선두 인덱스도 겸한다.
--
-- rider_id 에는 별도 인덱스를 만들지 않는다(조회 축이 주문별 시각순이므로).
-- 다만 InnoDB 는 FK 컬럼에 인덱스가 없으면 제약 이름으로 인덱스를 자동 생성하므로,
-- 실제로는 fk_rider_location_rider 용 인덱스가 하나 생긴다. 여기서 명시 인덱스를 생략하는 것은
-- 그 자동 인덱스와 중복되는 두 번째 인덱스를 만들지 않겠다는 뜻이다.

CREATE TABLE rider_location_history (
    location_history_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    rider_id BIGINT UNSIGNED NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    accuracy_meters DECIMAL(7,2) NULL,
    measured_at DATETIME(3) NOT NULL,
    stored_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_rider_location_history PRIMARY KEY (location_history_id),
    CONSTRAINT uk_rider_location_order_time UNIQUE (order_id, measured_at),
    CONSTRAINT fk_rider_location_order
        FOREIGN KEY (order_id) REFERENCES delivery_order (order_id),
    CONSTRAINT fk_rider_location_rider
        FOREIGN KEY (rider_id) REFERENCES rider_profile (member_id),
    CONSTRAINT ck_rider_location_lat
        CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_rider_location_lon
        CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_rider_location_accuracy
        CHECK (accuracy_meters IS NULL OR accuracy_meters >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'DELIVERING 구간의 선별된 실제 운행 위치';
