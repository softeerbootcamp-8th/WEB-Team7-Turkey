-- 회원의 약관 버전별 동의 이력
-- (member_id, term_id) 유니크로 같은 약관 버전에 대한 중복 동의 레코드를 막는다.
-- 불변 이력이므로 updated_at 을 두지 않고 agreed_at 만 기록한다.

CREATE TABLE member_term_agreement (
    agreement_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_id BIGINT UNSIGNED NOT NULL,
    term_id BIGINT UNSIGNED NOT NULL,
    agreed TINYINT(1) NOT NULL,
    agreed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_member_term_agreement PRIMARY KEY (agreement_id),
    CONSTRAINT uk_member_term_agreement UNIQUE (member_id, term_id),
    CONSTRAINT fk_agreement_member
        FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_agreement_term
        FOREIGN KEY (term_id) REFERENCES term (term_id),
    CONSTRAINT ck_agreement_agreed
        CHECK (agreed IN (0, 1)),

    KEY idx_agreement_term (term_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '회원의 약관 버전별 동의 이력';
