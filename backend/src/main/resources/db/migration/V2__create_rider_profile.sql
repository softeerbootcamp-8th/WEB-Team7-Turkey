-- 라이더 운행 프로필
-- member 와 PK(member_id)를 공유한다: rider_profile.member_id 는 PK이면서 member 로의 FK.
-- 라이더 상태(operating_status)와 배송 상태(delivery_order.status)는 분리해서 관리한다.

CREATE TABLE rider_profile (
    member_id BIGINT UNSIGNED NOT NULL,
    operating_status VARCHAR(20) NOT NULL DEFAULT 'UNAVAILABLE',
    status_changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_rider_profile PRIMARY KEY (member_id),
    CONSTRAINT fk_rider_profile_member
        FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT ck_rider_operating_status
        CHECK (operating_status IN ('UNAVAILABLE', 'AVAILABLE', 'BUSY'))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '라이더 운행 프로필';
