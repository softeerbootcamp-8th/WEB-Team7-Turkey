-- 요금 정책 버전별 물품 종류 할증. 물품 크기·무게·수량 기준은 사용하지 않는다.
-- (fare_policy_id, item_type) 유니크로 정책 버전당 물품 종류별 할증을 1건으로 제한한다.

CREATE TABLE item_type_surcharge (
    item_type_surcharge_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    fare_policy_id BIGINT UNSIGNED NOT NULL,
    item_type VARCHAR(30) NOT NULL,
    surcharge_amount BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_item_type_surcharge PRIMARY KEY (item_type_surcharge_id),
    CONSTRAINT uk_item_surcharge_policy_type
        UNIQUE (fare_policy_id, item_type),
    CONSTRAINT fk_item_surcharge_policy
        FOREIGN KEY (fare_policy_id) REFERENCES fare_policy (fare_policy_id),
    CONSTRAINT ck_item_surcharge_item_type
        CHECK (item_type IN (
            'DOCUMENT', 'SMALL_PARCEL', 'MEDIUM_PARCEL', 'LARGE_PARCEL', 'FOOD'
        ))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '요금 정책별 물품 종류 할증';
