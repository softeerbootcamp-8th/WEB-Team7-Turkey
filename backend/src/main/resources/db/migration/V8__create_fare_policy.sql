-- 거리 단위당 요금을 직접 저장하는 요금 정책. 거리 구간별 규칙을 별도 행으로 관리하지 않는다.
-- active_policy_marker 는 status='ACTIVE' 일 때만 1, 그 외에는 NULL 인 생성 컬럼이다.
-- MySQL UNIQUE 는 NULL 을 다건 허용하므로, 이 컬럼의 UNIQUE 가 곧
-- "활성 요금 정책은 최대 1건" 제약이 된다.
--
-- VIRTUAL 키워드를 붙이지 않는 이유: MySQL 생성 컬럼의 기본값이 VIRTUAL 이라 동작이
-- 동일한 반면, 로컬 개발용 H2(MySQL 호환 모드)는 이 키워드를 파싱하지 못해 마이그레이션이
-- 실패한다. 키워드를 생략하면 운영(MySQL)과 로컬(H2) 양쪽에서 같은 스키마가 만들어진다.

CREATE TABLE fare_policy (
    fare_policy_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    policy_version VARCHAR(30) NOT NULL,
    base_fare BIGINT UNSIGNED NOT NULL,
    distance_unit_meters INT UNSIGNED NOT NULL DEFAULT 1000,
    distance_unit_fare BIGINT UNSIGNED NOT NULL,
    max_delivery_distance_meters INT UNSIGNED NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    effective_from DATETIME(3) NOT NULL,
    effective_to DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    active_policy_marker TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END
        ),

    CONSTRAINT pk_fare_policy PRIMARY KEY (fare_policy_id),
    CONSTRAINT uk_fare_policy_version UNIQUE (policy_version),
    CONSTRAINT uk_fare_policy_active UNIQUE (active_policy_marker),
    CONSTRAINT ck_fare_policy_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_fare_policy_base_fare
        CHECK (base_fare > 0),
    CONSTRAINT ck_fare_policy_distance_unit
        CHECK (distance_unit_meters > 0),
    CONSTRAINT ck_fare_policy_distance_fare
        CHECK (distance_unit_fare > 0),
    CONSTRAINT ck_fare_policy_max_distance
        CHECK (max_delivery_distance_meters > 0),
    CONSTRAINT ck_fare_policy_effective_period
        CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '기본요금 및 거리 단가 정책';
