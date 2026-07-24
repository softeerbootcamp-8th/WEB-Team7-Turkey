-- 고객 포인트 충전 결제 및 전액 환불 상태
-- (customer_id, charge_request_key) 유니크로 동일 충전요청 재전송을 막고(멱등성),
-- provider_payment_key / provider_refund_key 유니크로 PG 중복 승인·환불을 막는다.
-- ck_point_charge_state_values 가 상태별 금액/시각 필드 조합을 강제한다.

CREATE TABLE point_charge (
    point_charge_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    customer_id BIGINT UNSIGNED NOT NULL,
    charge_request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    payment_method VARCHAR(20) NOT NULL,
    payment_provider VARCHAR(30) NULL,
    provider_payment_key VARCHAR(100) NULL,
    provider_refund_key VARCHAR(100) NULL,

    requested_amount BIGINT UNSIGNED NOT NULL,
    approved_amount BIGINT UNSIGNED NULL,
    refunded_amount BIGINT UNSIGNED NOT NULL DEFAULT 0,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    issuer_code VARCHAR(20) NULL,
    masked_payment_method VARCHAR(100) NULL,
    failure_reason VARCHAR(255) NULL,
    refund_reason VARCHAR(255) NULL,

    requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    approved_at DATETIME(3) NULL,
    refunded_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_point_charge PRIMARY KEY (point_charge_id),
    CONSTRAINT uk_point_charge_customer_request
        UNIQUE (customer_id, charge_request_key),
    CONSTRAINT uk_point_charge_provider_payment
        UNIQUE (provider_payment_key),
    CONSTRAINT uk_point_charge_provider_refund
        UNIQUE (provider_refund_key),
    CONSTRAINT fk_point_charge_customer
        FOREIGN KEY (customer_id) REFERENCES member (member_id),
    CONSTRAINT ck_point_charge_method
        CHECK (payment_method IN ('CARD', 'BANK_TRANSFER')),
    CONSTRAINT ck_point_charge_status
        CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'CANCELED', 'REFUNDED')),
    CONSTRAINT ck_point_charge_requested_amount
        CHECK (requested_amount > 0),
    CONSTRAINT ck_point_charge_refund_amount
        CHECK (refunded_amount <= COALESCE(approved_amount, 0)),
    CONSTRAINT ck_point_charge_state_values
        CHECK (
            (
                status IN ('PENDING', 'FAILED', 'CANCELED')
                AND approved_amount IS NULL
                AND approved_at IS NULL
                AND refunded_amount = 0
                AND refunded_at IS NULL
            )
            OR
            (
                status = 'PAID'
                AND approved_amount = requested_amount
                AND approved_at IS NOT NULL
                AND refunded_amount = 0
                AND refunded_at IS NULL
            )
            OR
            (
                status = 'REFUNDED'
                AND approved_amount = requested_amount
                AND approved_at IS NOT NULL
                AND refunded_amount = approved_amount
                AND refunded_at IS NOT NULL
            )
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '고객 포인트 충전 결제 및 전액 환불 상태';
