-- 라이더 포인트 출금 요청 및 실패 복구 상태.
--
-- 계좌 정보는 요청 시점 값을 *_snapshot 컬럼에 복사해 보관한다 — rider_payout_account 는
-- 라이더당 1행을 in-place 로 교체하므로(계좌 변경), 참조만 두면 과거 출금 이력이 현재 계좌를
-- 가리키게 된다. 계좌번호 암호문은 스냅샷에 넣지 않는다: 이력 조회에는 마스킹 값이면 충분하고
-- 암호문 사본을 늘리면 유출 표면만 넓어진다.
--
-- 출금은 선차감 모델이다 — 요청 시 포인트를 먼저 차감하고, 실패하면 되돌린다.
-- points_restored 는 그 복구가 끝났는지를 나타내는 플래그이며, 아래 상태별 조합 CHECK 가
-- "FAILED 인데 아직 복구되지 않음" 같은 중간 상태가 커밋되는 것을 DB 수준에서 막는다.
-- (포인트 원장 기록 자체는 point_transaction 몫이다.)
--
-- (rider_id, request_key) 유니크로 동일 출금 요청 재전송을 막는다(point_charge 와 같은 방식).

CREATE TABLE rider_withdrawal (
    withdrawal_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    rider_id BIGINT UNSIGNED NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount BIGINT UNSIGNED NOT NULL,

    bank_code_snapshot VARCHAR(20) NOT NULL,
    masked_account_number_snapshot VARCHAR(50) NOT NULL,
    account_holder_name_snapshot VARCHAR(50) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    failure_reason VARCHAR(255) NULL,
    points_restored TINYINT(1) NOT NULL DEFAULT 0,
    requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    processed_at DATETIME(3) NULL,

    CONSTRAINT pk_rider_withdrawal PRIMARY KEY (withdrawal_id),
    CONSTRAINT uk_rider_withdrawal_request UNIQUE (rider_id, request_key),
    CONSTRAINT fk_rider_withdrawal_rider
        FOREIGN KEY (rider_id) REFERENCES rider_profile (member_id),
    CONSTRAINT ck_rider_withdrawal_amount
        CHECK (amount > 0),
    CONSTRAINT ck_rider_withdrawal_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_rider_withdrawal_restored
        CHECK (points_restored IN (0, 1)),
    CONSTRAINT ck_rider_withdrawal_state_values
        CHECK (
            (
                status = 'PENDING'
                AND processed_at IS NULL
                AND points_restored = 0
            )
            OR
            (
                status = 'COMPLETED'
                AND processed_at IS NOT NULL
                AND points_restored = 0
            )
            OR
            (
                status = 'FAILED'
                AND processed_at IS NOT NULL
                AND points_restored = 1
            )
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '라이더 포인트 출금 요청 및 실패 복구 상태';
