-- 주문당 배송 완료 인증
-- 라이더가 배송 완료 시 남기는 인증 기록. 주문당 최대 1건(uk_delivery_proof_order).
-- 실제 이미지 바이너리는 저장하지 않고 proof_value 에 참조값(URL/키/코드)만 보관한다.
-- proof_type 은 PHOTO/RECIPIENT_CONFIRMATION/AUTH_CODE 중 하나(ck_delivery_proof_type).

CREATE TABLE delivery_proof (
    delivery_proof_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    rider_id BIGINT UNSIGNED NOT NULL,
    proof_type VARCHAR(30) NOT NULL,
    proof_value VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_delivery_proof PRIMARY KEY (delivery_proof_id),
    CONSTRAINT uk_delivery_proof_order UNIQUE (order_id),
    CONSTRAINT fk_delivery_proof_order
        FOREIGN KEY (order_id) REFERENCES delivery_order (order_id),
    CONSTRAINT fk_delivery_proof_rider
        FOREIGN KEY (rider_id) REFERENCES rider_profile (member_id),
    CONSTRAINT ck_delivery_proof_type
        CHECK (proof_type IN ('PHOTO', 'RECIPIENT_CONFIRMATION', 'AUTH_CODE')),

    KEY idx_delivery_proof_rider (rider_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '주문당 배송 완료 인증';
