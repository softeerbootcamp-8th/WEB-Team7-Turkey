-- 회원별 현재 포인트 잔액
-- member 와 PK(member_id)를 공유한다: point_wallet.member_id 는 PK이면서 member 로의 FK.
-- 낙관적 락(version) 컬럼을 두지 않는다(팀 정책상 @Version 전면 폐기).
-- 잔액 차감/환불의 동시성은 서비스의 조건부 UPDATE(balance >= :amt)와
-- point_transaction.request_key 멱등성으로 보장한다.

CREATE TABLE point_wallet (
    member_id BIGINT UNSIGNED NOT NULL,
    balance BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_point_wallet PRIMARY KEY (member_id),
    CONSTRAINT fk_point_wallet_member
        FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '회원별 현재 포인트 잔액';
