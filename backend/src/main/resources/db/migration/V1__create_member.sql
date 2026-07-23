-- 공통 회원 계정
-- 스키마(turkey) 생성은 DB 프로비저닝 단계에서 수행하고, 마이그레이션은 테이블만 다룬다.

CREATE TABLE member (
    member_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    login_id VARCHAR(50) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    name VARCHAR(50) NOT NULL,
    phone_number VARCHAR(20) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    withdrawn_at DATETIME(3) NULL,

    CONSTRAINT pk_member PRIMARY KEY (member_id),
    CONSTRAINT uk_member_login_id UNIQUE (login_id),
    CONSTRAINT uk_member_phone_number UNIQUE (phone_number),
    CONSTRAINT ck_member_role
        CHECK (role IN ('CUSTOMER', 'RIDER')),
    CONSTRAINT ck_member_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'WITHDRAWN')),
    CONSTRAINT ck_member_withdrawn_at
        CHECK (
            (status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL)
            OR
            (status IN ('ACTIVE', 'SUSPENDED') AND withdrawn_at IS NULL)
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '공통 회원 계정';
