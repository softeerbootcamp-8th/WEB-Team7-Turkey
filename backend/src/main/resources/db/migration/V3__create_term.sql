-- 버전별 회원가입 약관
-- target_role 로 공통/고객/라이더 약관을 구분하고, (term_code, target_role, version) 로 버전별 유일성을 보장한다.
-- version 컬럼은 약관 문서의 버전 문자열이며 낙관적 락과 무관하다.

CREATE TABLE term (
    term_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    term_code VARCHAR(50) NOT NULL,
    target_role VARCHAR(20) NOT NULL,
    title VARCHAR(150) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    version VARCHAR(30) NOT NULL,
    is_required TINYINT(1) NOT NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    effective_from DATETIME(3) NOT NULL,
    effective_to DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_term PRIMARY KEY (term_id),
    CONSTRAINT uk_term_code_role_version
        UNIQUE (term_code, target_role, version),
    CONSTRAINT ck_term_target_role
        CHECK (target_role IN ('COMMON', 'CUSTOMER', 'RIDER')),
    CONSTRAINT ck_term_required
        CHECK (is_required IN (0, 1)),
    CONSTRAINT ck_term_active
        CHECK (is_active IN (0, 1)),
    CONSTRAINT ck_term_effective_period
        CHECK (effective_to IS NULL OR effective_to > effective_from),

    KEY idx_term_active_role (is_active, target_role, effective_from)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '버전별 회원가입 약관';
