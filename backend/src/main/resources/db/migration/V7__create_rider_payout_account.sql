-- 라이더 활성 출금 계좌
-- rider_profile 과 PK(rider_id)를 공유한다: rider_id 는 PK이면서 rider_profile 로의 FK.
-- 계좌번호 원문은 저장하지 않는다. account_number_ciphertext(VARBINARY)에 암호문만 보관하고,
-- 화면 표시는 masked_account_number 만 사용한다.

CREATE TABLE rider_payout_account (
    rider_id BIGINT UNSIGNED NOT NULL,
    bank_code VARCHAR(20) NOT NULL,
    account_number_ciphertext VARBINARY(512) NOT NULL,
    masked_account_number VARCHAR(50) NOT NULL,
    account_holder_name VARCHAR(50) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_rider_payout_account PRIMARY KEY (rider_id),
    CONSTRAINT fk_rider_payout_account_rider
        FOREIGN KEY (rider_id) REFERENCES rider_profile (member_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '라이더 활성 출금 계좌';
