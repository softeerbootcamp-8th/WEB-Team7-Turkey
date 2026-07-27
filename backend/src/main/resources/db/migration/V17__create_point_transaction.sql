-- 회원 포인트 증감 불변 원장.
--
-- 잔액의 정본은 point_wallet.balance 이고, 이 테이블은 그 변화를 한 줄씩 남기는 append-only 원장이다.
-- member_id 는 member 가 아니라 point_wallet 을 참조한다 — 지갑 없는 회원의 원장 행을 원천 차단한다.
--
-- 세 겹의 CHECK 로 잘못된 조합이 커밋되는 것을 막는다:
--  1) ck_point_transaction_type_direction
--     거래 유형마다 증감 방향이 하나로 고정된다(CHARGE 는 항상 CREDIT, ORDER_USE 는 항상 DEBIT ...).
--  2) ck_point_transaction_source
--     소스 FK 4종(delivery_order/point_charge/rider_withdrawal/rider_settlement) 중
--     유형에 맞는 정확히 하나만 채워지고 나머지는 NULL 이어야 한다.
--  3) ck_point_transaction_balance
--     balance_after 가 balance_before 와 amount 로부터 실제로 도출된 값인지 검사한다.
--
-- 중복 원장 방지:
--  - uk_point_transaction_request: request_key 전역 유니크(재전송 멱등성)
--  - 소스별 (source_id, transaction_type) 유니크 4종: 같은 주문에 ORDER_USE 가 두 번,
--    같은 출금에 WITHDRAWAL_REFUND 가 두 번 기록되는 것을 막는다.
--    MySQL UNIQUE 는 NULL 을 다건 허용하므로, 소스가 NULL 인 다른 유형의 행들은 서로 충돌하지 않는다.

CREATE TABLE point_transaction (
    point_transaction_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_id BIGINT UNSIGNED NOT NULL,
    transaction_type VARCHAR(30) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    amount BIGINT UNSIGNED NOT NULL,
    balance_before BIGINT UNSIGNED NOT NULL,
    balance_after BIGINT UNSIGNED NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    delivery_order_id BIGINT UNSIGNED NULL,
    point_charge_id BIGINT UNSIGNED NULL,
    rider_withdrawal_id BIGINT UNSIGNED NULL,
    rider_settlement_id BIGINT UNSIGNED NULL,

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_point_transaction PRIMARY KEY (point_transaction_id),
    CONSTRAINT uk_point_transaction_request UNIQUE (request_key),
    CONSTRAINT uk_point_transaction_order_type
        UNIQUE (delivery_order_id, transaction_type),
    CONSTRAINT uk_point_transaction_charge_type
        UNIQUE (point_charge_id, transaction_type),
    CONSTRAINT uk_point_transaction_withdrawal_type
        UNIQUE (rider_withdrawal_id, transaction_type),
    CONSTRAINT uk_point_transaction_settlement_type
        UNIQUE (rider_settlement_id, transaction_type),

    CONSTRAINT fk_point_transaction_wallet
        FOREIGN KEY (member_id) REFERENCES point_wallet (member_id),
    CONSTRAINT fk_point_transaction_order
        FOREIGN KEY (delivery_order_id) REFERENCES delivery_order (order_id),
    CONSTRAINT fk_point_transaction_charge
        FOREIGN KEY (point_charge_id) REFERENCES point_charge (point_charge_id),
    CONSTRAINT fk_point_transaction_withdrawal
        FOREIGN KEY (rider_withdrawal_id) REFERENCES rider_withdrawal (withdrawal_id),
    CONSTRAINT fk_point_transaction_settlement
        FOREIGN KEY (rider_settlement_id) REFERENCES rider_settlement (settlement_id),

    CONSTRAINT ck_point_transaction_type
        CHECK (transaction_type IN (
            'CHARGE',
            'CHARGE_REFUND',
            'ORDER_USE',
            'ORDER_REFUND',
            'SETTLEMENT',
            'WITHDRAWAL',
            'WITHDRAWAL_REFUND'
        )),
    CONSTRAINT ck_point_transaction_direction
        CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_point_transaction_amount
        CHECK (amount > 0),
    CONSTRAINT ck_point_transaction_balance
        CHECK (
            (
                direction = 'CREDIT'
                AND balance_after = balance_before + amount
            )
            OR
            (
                direction = 'DEBIT'
                AND balance_before = balance_after + amount
            )
        ),
    CONSTRAINT ck_point_transaction_type_direction
        CHECK (
            (
                transaction_type IN (
                    'CHARGE',
                    'ORDER_REFUND',
                    'SETTLEMENT',
                    'WITHDRAWAL_REFUND'
                )
                AND direction = 'CREDIT'
            )
            OR
            (
                transaction_type IN (
                    'CHARGE_REFUND',
                    'ORDER_USE',
                    'WITHDRAWAL'
                )
                AND direction = 'DEBIT'
            )
        ),
    CONSTRAINT ck_point_transaction_source
        CHECK (
            (
                transaction_type IN ('CHARGE', 'CHARGE_REFUND')
                AND point_charge_id IS NOT NULL
                AND delivery_order_id IS NULL
                AND rider_withdrawal_id IS NULL
                AND rider_settlement_id IS NULL
            )
            OR
            (
                transaction_type IN ('ORDER_USE', 'ORDER_REFUND')
                AND delivery_order_id IS NOT NULL
                AND point_charge_id IS NULL
                AND rider_withdrawal_id IS NULL
                AND rider_settlement_id IS NULL
            )
            OR
            (
                transaction_type = 'SETTLEMENT'
                AND rider_settlement_id IS NOT NULL
                AND delivery_order_id IS NULL
                AND point_charge_id IS NULL
                AND rider_withdrawal_id IS NULL
            )
            OR
            (
                transaction_type IN ('WITHDRAWAL', 'WITHDRAWAL_REFUND')
                AND rider_withdrawal_id IS NOT NULL
                AND delivery_order_id IS NULL
                AND point_charge_id IS NULL
                AND rider_settlement_id IS NULL
            )
        ),

    KEY idx_point_transaction_member_time (member_id, created_at DESC)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '회원 포인트 증감 불변 원장';
