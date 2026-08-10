-- 로컬 수동 테스트용 전체 샘플 데이터.
-- 주의: Flyway 이력을 제외한 애플리케이션 데이터를 전부 삭제한다.
-- 실행: cd backend && docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/reset-and-seed-local.sql
--
-- 모든 계정 비밀번호: aa
--
-- 고객
--   c1: WAITING, c2: ASSIGNED, c3: MOVING_TO_PICKUP, c4: DELIVERING
--   c5, c6: 진행 중 주문 없음
--   모든 고객은 누적 주문 10건 이상
--
-- 라이더
--   rpending1~3: AVAILABLE(콜 대기), 주문 수 40 / 12 / 0
--   rbusy1~3: BUSY, 주문 수 40 / 12 / 1
--   roffline1~3: UNAVAILABLE(운행 종료), 주문 수 40 / 12 / 0
--   BUSY 라이더는 진행 중 주문이 반드시 필요하므로 rbusy3만 최소 1건이다.

USE turkey;

-- ---------------------------------------------------------------------------
-- 1. 기존 데이터 초기화
-- ---------------------------------------------------------------------------
SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE point_transaction;
TRUNCATE TABLE rider_location_history;
TRUNCATE TABLE delivery_proof;
TRUNCATE TABLE order_status_history;
TRUNCATE TABLE order_fare_snapshot;
TRUNCATE TABLE rider_settlement;
TRUNCATE TABLE rider_withdrawal;
TRUNCATE TABLE delivery_order;
TRUNCATE TABLE item_type_surcharge;
TRUNCATE TABLE fare_policy;
TRUNCATE TABLE rider_payout_account;
TRUNCATE TABLE point_charge;
TRUNCATE TABLE point_wallet;
TRUNCATE TABLE member_term_agreement;
TRUNCATE TABLE term;
TRUNCATE TABLE rider_profile;
TRUNCATE TABLE member;

SET FOREIGN_KEY_CHECKS = 1;
START TRANSACTION;

-- ---------------------------------------------------------------------------
-- 2. 고객 6명, 라이더 9명
-- ---------------------------------------------------------------------------
-- 로그인 DTO가 빈 비밀번호를 거부하므로 전 계정 비밀번호는 aa로 통일한다.
SET @password_hash = '$2y$10$Lt6WtA6CYLmEjEtyFIQKTOwZN3QZqsumCPe6eSBLuiPZXfEohtbpy';

INSERT INTO member (
    login_id, password_hash, name, phone_number, role, status
) VALUES
      ('c1', @password_hash, '요청 대기 고객', '01000000001', 'CUSTOMER', 'ACTIVE'),
      ('c2', @password_hash, '배차 완료 고객', '01000000002', 'CUSTOMER', 'ACTIVE'),
      ('c3', @password_hash, '픽업 이동 고객', '01000000003', 'CUSTOMER', 'ACTIVE'),
      ('c4', @password_hash, '배송 중 고객', '01000000004', 'CUSTOMER', 'ACTIVE'),
      ('c5', @password_hash, '요청 없는 고객 1', '01000000005', 'CUSTOMER', 'ACTIVE'),
      ('c6', @password_hash, '요청 없는 고객 2', '01000000006', 'CUSTOMER', 'ACTIVE'),
      ('rpending1', @password_hash, '콜 대기 라이더 1', '01000000101', 'RIDER', 'ACTIVE'),
      ('rpending2', @password_hash, '콜 대기 라이더 2', '01000000102', 'RIDER', 'ACTIVE'),
      ('rpending3', @password_hash, '콜 대기 라이더 3', '01000000103', 'RIDER', 'ACTIVE'),
      ('rbusy1', @password_hash, '운행 중 라이더 1', '01000000111', 'RIDER', 'ACTIVE'),
      ('rbusy2', @password_hash, '운행 중 라이더 2', '01000000112', 'RIDER', 'ACTIVE'),
      ('rbusy3', @password_hash, '운행 중 라이더 3', '01000000113', 'RIDER', 'ACTIVE'),
      ('roffline1', @password_hash, '운행 종료 라이더 1', '01000000121', 'RIDER', 'ACTIVE'),
      ('roffline2', @password_hash, '운행 종료 라이더 2', '01000000122', 'RIDER', 'ACTIVE'),
      ('roffline3', @password_hash, '운행 종료 라이더 3', '01000000123', 'RIDER', 'ACTIVE');

SET @c1 = (SELECT member_id FROM member WHERE login_id = 'c1');
SET @c2 = (SELECT member_id FROM member WHERE login_id = 'c2');
SET @c3 = (SELECT member_id FROM member WHERE login_id = 'c3');
SET @c4 = (SELECT member_id FROM member WHERE login_id = 'c4');
SET @c5 = (SELECT member_id FROM member WHERE login_id = 'c5');
SET @c6 = (SELECT member_id FROM member WHERE login_id = 'c6');

SET @rpending1 = (SELECT member_id FROM member WHERE login_id = 'rpending1');
SET @rpending2 = (SELECT member_id FROM member WHERE login_id = 'rpending2');
SET @rpending3 = (SELECT member_id FROM member WHERE login_id = 'rpending3');
SET @rbusy1 = (SELECT member_id FROM member WHERE login_id = 'rbusy1');
SET @rbusy2 = (SELECT member_id FROM member WHERE login_id = 'rbusy2');
SET @rbusy3 = (SELECT member_id FROM member WHERE login_id = 'rbusy3');
SET @roffline1 = (SELECT member_id FROM member WHERE login_id = 'roffline1');
SET @roffline2 = (SELECT member_id FROM member WHERE login_id = 'roffline2');
SET @roffline3 = (SELECT member_id FROM member WHERE login_id = 'roffline3');

INSERT INTO rider_profile (member_id, operating_status, status_changed_at) VALUES
                                                                               (@rpending1, 'AVAILABLE', '2026-08-04 00:00:00.000'),
                                                                               (@rpending2, 'AVAILABLE', '2026-08-04 00:00:00.000'),
                                                                               (@rpending3, 'AVAILABLE', '2026-08-04 00:00:00.000'),
                                                                               (@rbusy1, 'BUSY', '2026-08-03 10:05:00.000'),
                                                                               (@rbusy2, 'BUSY', '2026-08-03 11:05:00.000'),
                                                                               (@rbusy3, 'BUSY', '2026-08-03 12:05:00.000'),
                                                                               (@roffline1, 'UNAVAILABLE', '2026-08-03 20:00:00.000'),
                                                                               (@roffline2, 'UNAVAILABLE', '2026-08-03 20:00:00.000'),
                                                                               (@roffline3, 'UNAVAILABLE', '2026-08-03 20:00:00.000');

INSERT INTO rider_payout_account (
    rider_id, bank_code, account_number_ciphertext,
    masked_account_number, account_holder_name
)
SELECT
    m.member_id,
    'TEST_BANK',
    UNHEX(SHA2(CONCAT('LOCAL_ONLY_', m.login_id), 256)),
    CONCAT('123-****-', LPAD(m.member_id, 4, '0')),
    m.name
FROM member AS m
WHERE m.role = 'RIDER';

INSERT INTO point_wallet (member_id, balance)
SELECT
    member_id,
    CASE WHEN role = 'RIDER' THEN 100000 ELSE 0 END
FROM member;

-- ---------------------------------------------------------------------------
-- 3. 약관과 동의 이력
-- ---------------------------------------------------------------------------
INSERT INTO term (
    term_code, target_role, title, content, version,
    is_required, is_active, effective_from
) VALUES
      ('SERVICE', 'COMMON', '서비스 이용약관', '로컬 테스트용 서비스 이용약관입니다.', '1.0', 1, 1, '2026-01-01 00:00:00.000'),
      ('PRIVACY', 'COMMON', '개인정보 처리 동의', '로컬 테스트용 개인정보 처리 동의입니다.', '1.0', 1, 1, '2026-01-01 00:00:00.000'),
      ('MARKETING', 'COMMON', '마케팅 정보 수신 동의', '로컬 테스트용 선택 약관입니다.', '1.0', 0, 1, '2026-01-01 00:00:00.000'),
      ('CUSTOMER_SERVICE', 'CUSTOMER', '고객 서비스 이용 동의', '로컬 테스트용 고객 약관입니다.', '1.0', 1, 1, '2026-01-01 00:00:00.000'),
      ('RIDER_SERVICE', 'RIDER', '라이더 서비스 이용 동의', '로컬 테스트용 라이더 약관입니다.', '1.0', 1, 1, '2026-01-01 00:00:00.000');

INSERT INTO member_term_agreement (member_id, term_id, agreed, agreed_at)
SELECT m.member_id, t.term_id, 1, '2026-03-01 00:00:00.000'
FROM member AS m
         JOIN term AS t
              ON t.target_role = 'COMMON'
                  OR t.target_role = m.role;

-- ---------------------------------------------------------------------------
-- 4. 요금정책
-- ---------------------------------------------------------------------------
INSERT INTO fare_policy (
    policy_version, base_fare, distance_unit_meters, distance_unit_fare,
    max_delivery_distance_meters, status, effective_from
) VALUES
    ('LOCAL-1.0', 5000, 1000, 1000, 30000, 'ACTIVE', '2026-01-01 00:00:00.000');

SET @fare_policy_id = LAST_INSERT_ID();

INSERT INTO item_type_surcharge (fare_policy_id, item_type, surcharge_amount) VALUES
                                                                                  (@fare_policy_id, 'DOCUMENT', 0),
                                                                                  (@fare_policy_id, 'SMALL_PARCEL', 500),
                                                                                  (@fare_policy_id, 'MEDIUM_PARCEL', 1000),
                                                                                  (@fare_policy_id, 'LARGE_PARCEL', 2000),
                                                                                  (@fare_policy_id, 'FOOD', 1000);

-- ---------------------------------------------------------------------------
-- 5. 충전 상태별 내역과 고객 초기 잔액
-- ---------------------------------------------------------------------------
SET @customer_initial_balance = 5000000;

INSERT INTO point_charge (
    customer_id, charge_request_key, payment_method, payment_provider,
    provider_payment_key, requested_amount, approved_amount, refunded_amount,
    status, issuer_code, masked_payment_method, requested_at, approved_at
)
SELECT
    m.member_id,
    CONCAT('20000000-0000-0000-0000-', LPAD(m.member_id, 12, '0')),
    'CARD', 'LOCAL_PG', CONCAT('LOCAL-PAY-', m.member_id),
    @customer_initial_balance, @customer_initial_balance, 0,
    'PAID', 'LOCAL_CARD', '****-****-****-1234',
    '2026-03-01 08:00:00.000', '2026-03-01 08:00:01.000'
FROM member AS m
WHERE m.role = 'CUSTOMER';

INSERT INTO point_charge (
    customer_id, charge_request_key, payment_method, payment_provider,
    provider_payment_key, provider_refund_key,
    requested_amount, approved_amount, refunded_amount, status,
    issuer_code, masked_payment_method, failure_reason, refund_reason,
    requested_at, approved_at, refunded_at
) VALUES
      (@c1, '21000000-0000-0000-0000-000000000001', 'CARD', 'LOCAL_PG',
       NULL, NULL, 10000, NULL, 0, 'PENDING', NULL, NULL, NULL, NULL,
       '2026-08-03 09:00:00.000', NULL, NULL),
      (@c1, '21000000-0000-0000-0000-000000000002', 'CARD', 'LOCAL_PG',
       NULL, NULL, 20000, NULL, 0, 'FAILED', NULL, NULL, '카드 승인 거절', NULL,
       '2026-08-03 09:10:00.000', NULL, NULL),
      (@c1, '21000000-0000-0000-0000-000000000003', 'BANK_TRANSFER', 'LOCAL_PG',
       NULL, NULL, 30000, NULL, 0, 'CANCELED', NULL, NULL, '사용자 요청 취소', NULL,
       '2026-08-03 09:20:00.000', NULL, NULL),
      (@c1, '21000000-0000-0000-0000-000000000004', 'CARD', 'LOCAL_PG',
       'LOCAL-PAY-REFUNDED', 'LOCAL-REFUND-1', 50000, 50000, 50000, 'REFUNDED',
       'LOCAL_CARD', '****-****-****-5678', NULL, '테스트 전액 환불',
       '2026-03-02 09:30:00.000', '2026-03-02 09:30:01.000', '2026-03-02 10:00:00.000');

INSERT INTO point_transaction (
    member_id, transaction_type, direction, amount,
    balance_before, balance_after, request_key, point_charge_id, created_at
)
SELECT
    pc.customer_id, 'CHARGE', 'CREDIT', pc.approved_amount,
    0, pc.approved_amount, pc.charge_request_key, pc.point_charge_id, pc.approved_at
FROM point_charge AS pc
WHERE pc.status = 'PAID';

UPDATE point_wallet AS pw
    JOIN member AS m ON m.member_id = pw.member_id
    SET pw.balance = @customer_initial_balance
WHERE m.role = 'CUSTOMER';

SET @refunded_charge_id = (
    SELECT point_charge_id FROM point_charge
    WHERE charge_request_key = '21000000-0000-0000-0000-000000000004'
);

INSERT INTO point_transaction (
    member_id, transaction_type, direction, amount,
    balance_before, balance_after, request_key, point_charge_id, created_at
) VALUES
      (@c1, 'CHARGE', 'CREDIT', 50000,
       @customer_initial_balance, @customer_initial_balance + 50000,
       '21000000-0000-0000-0000-000000000004', @refunded_charge_id, '2026-03-02 09:30:01.000'),
      (@c1, 'CHARGE_REFUND', 'DEBIT', 50000,
       @customer_initial_balance + 50000, @customer_initial_balance,
       '21000000-0000-0000-0000-000000000004', @refunded_charge_id, '2026-03-02 10:00:00.000');

-- ---------------------------------------------------------------------------
-- 6. 주소 기준점과 진행 중/상태 샘플 주문
-- ---------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS seed_location;
DROP TEMPORARY TABLE IF EXISTS seed_destination_location;

CREATE TEMPORARY TABLE seed_location (
    location_no INT PRIMARY KEY,
    road_address VARCHAR(255) NOT NULL,
    detail_address VARCHAR(255) NOT NULL,
    postal_code VARCHAR(10) NOT NULL,
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL
);

INSERT INTO seed_location VALUES
                              (1, '서울특별시 중구 세종대로 110', '서울시청 본관 앞', '04524', 37.5662952, 126.9779451),
                              (2, '서울특별시 종로구 사직로 161', '광화문 광장 입구', '03045', 37.5758772, 126.9768121),
                              (3, '서울특별시 용산구 한강대로 405', '서울역 1번 출구', '04320', 37.5546480, 126.9707020),
                              (4, '서울특별시 강남구 강남대로 396', '강남역 2번 출구', '06232', 37.4979420, 127.0276210),
                              (5, '서울특별시 강남구 테헤란로 521', '삼성역 5번 출구', '06164', 37.5088440, 127.0631600),
                              (6, '서울특별시 송파구 올림픽로 300', '롯데월드타워 정문', '05551', 37.5132610, 127.1001330),
                              (7, '서울특별시 마포구 양화로 160', '홍대입구역 9번 출구', '04050', 37.5571920, 126.9253810),
                              (8, '서울특별시 영등포구 여의대로 108', '여의도 더현대 정문', '07335', 37.5258520, 126.9283290),
                              (9, '서울특별시 성동구 왕십리광장로 17', '왕십리역 6번 출구', '04750', 37.5612610, 127.0370780),
                              (10, '서울특별시 광진구 아차산로 243', '건대입구역 2번 출구', '05018', 37.5404080, 127.0692020),
                              (11, '서울특별시 서초구 서초대로 294', '교대역 8번 출구', '06619', 37.4939610, 127.0146670),
                              (12, '서울특별시 동작구 현충로 257', '동작역 3번 출구', '06904', 37.5028520, 126.9803000);

CREATE TEMPORARY TABLE seed_destination_location LIKE seed_location;
INSERT INTO seed_destination_location SELECT * FROM seed_location;

-- BUSY 라이더 3명은 각각 진행 중 주문 1건을 가진다.
-- c1~c4는 진행 중, c5~c6은 완료/취소 이력만 있어 새 요청이 가능한 고객이다.
INSERT INTO delivery_order (
    customer_id, assigned_rider_id, status, request_key, item_type,
    straight_distance_meters,
    pickup_road_address, pickup_detail_address, pickup_postal_code,
    pickup_latitude, pickup_longitude,
    destination_road_address, destination_detail_address, destination_postal_code,
    destination_latitude, destination_longitude,
    sender_name, sender_phone_number, recipient_name, recipient_phone_number,
    requested_at, assigned_at, moving_to_pickup_at, picked_up_at,
    delivering_at, completed_at, canceled_at, cancel_reason
) VALUES
      (@c1, NULL, 'WAITING', '10000000-0000-0000-0000-000000000001', 'DOCUMENT', 1500,
       '서울특별시 중구 세종대로 110', '서울시청 본관 앞', '04524', 37.5662952, 126.9779451,
       '서울특별시 강남구 강남대로 396', '강남역 2번 출구', '06232', 37.4979420, 127.0276210,
       'c1 발송인', '01030000001', 'c1 수령인', '01040000001',
       '2026-08-03 09:00:00.000', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
      (@c2, @rbusy1, 'ASSIGNED', '10000000-0000-0000-0000-000000000002', 'SMALL_PARCEL', 2800,
       '서울특별시 종로구 사직로 161', '광화문 광장 입구', '03045', 37.5758772, 126.9768121,
       '서울특별시 강남구 테헤란로 521', '삼성역 5번 출구', '06164', 37.5088440, 127.0631600,
       'c2 발송인', '01030000002', 'c2 수령인', '01040000002',
       '2026-08-03 10:00:00.000', '2026-08-03 10:05:00.000', NULL, NULL, NULL, NULL, NULL, NULL),
      (@c3, @rbusy2, 'MOVING_TO_PICKUP', '10000000-0000-0000-0000-000000000003', 'MEDIUM_PARCEL', 3900,
       '서울특별시 용산구 한강대로 405', '서울역 1번 출구', '04320', 37.5546480, 126.9707020,
       '서울특별시 송파구 올림픽로 300', '롯데월드타워 정문', '05551', 37.5132610, 127.1001330,
       'c3 발송인', '01030000003', 'c3 수령인', '01040000003',
       '2026-08-03 11:00:00.000', '2026-08-03 11:05:00.000', '2026-08-03 11:10:00.000',
       NULL, NULL, NULL, NULL, NULL),
      (@c4, @rbusy3, 'DELIVERING', '10000000-0000-0000-0000-000000000004', 'FOOD', 5100,
       '서울특별시 마포구 양화로 160', '홍대입구역 9번 출구', '04050', 37.5571920, 126.9253810,
       '서울특별시 영등포구 여의대로 108', '여의도 더현대 정문', '07335', 37.5258520, 126.9283290,
       'c4 발송인', '01030000004', 'c4 수령인', '01040000004',
       '2026-08-03 12:00:00.000', '2026-08-03 12:05:00.000', '2026-08-03 12:10:00.000',
       '2026-08-03 12:20:00.000', '2026-08-03 12:25:00.000', NULL, NULL, NULL),
      (@c5, @roffline1, 'COMPLETED', '10000000-0000-0000-0000-000000000005', 'LARGE_PARCEL', 7200,
       '서울특별시 성동구 왕십리광장로 17', '왕십리역 6번 출구', '04750', 37.5612610, 127.0370780,
       '서울특별시 광진구 아차산로 243', '건대입구역 2번 출구', '05018', 37.5404080, 127.0692020,
       'c5 발송인', '01030000005', 'c5 수령인', '01040000005',
       '2026-07-31 10:00:00.000', '2026-07-31 10:05:00.000', '2026-07-31 10:10:00.000',
       '2026-07-31 10:20:00.000', '2026-07-31 10:25:00.000', '2026-07-31 11:00:00.000', NULL, NULL),
      (@c6, NULL, 'CANCELED', '10000000-0000-0000-0000-000000000006', 'DOCUMENT', 900,
       '서울특별시 서초구 서초대로 294', '교대역 8번 출구', '06619', 37.4939610, 127.0146670,
       '서울특별시 동작구 현충로 257', '동작역 3번 출구', '06904', 37.5028520, 126.9803000,
       'c6 발송인', '01030000006', 'c6 수령인', '01040000006',
       '2026-08-03 13:00:00.000', NULL, NULL, NULL, NULL, NULL,
       '2026-08-03 13:05:00.000', '고객 요청 취소');

-- ---------------------------------------------------------------------------
-- 7. 라이더별 목표 주문 수까지 완료 주문 생성
-- ---------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS seed_rider_order_target;
CREATE TEMPORARY TABLE seed_rider_order_target (
    rider_id BIGINT PRIMARY KEY,
    target_count INT NOT NULL
);

INSERT INTO seed_rider_order_target VALUES
                                        (@rpending1, 40), (@rpending2, 12), (@rpending3, 0),
                                        (@rbusy1, 40), (@rbusy2, 12), (@rbusy3, 1),
                                        (@roffline1, 40), (@roffline2, 12), (@roffline3, 0);

INSERT INTO delivery_order (
    customer_id, assigned_rider_id, status, request_key, item_type,
    straight_distance_meters,
    pickup_road_address, pickup_detail_address, pickup_postal_code,
    pickup_latitude, pickup_longitude,
    destination_road_address, destination_detail_address, destination_postal_code,
    destination_latitude, destination_longitude,
    sender_name, sender_phone_number, recipient_name, recipient_phone_number,
    requested_at, assigned_at, moving_to_pickup_at, picked_up_at,
    delivering_at, completed_at, canceled_at, cancel_reason
)
WITH RECURSIVE sequence_numbers AS (
    SELECT 1 AS sequence_no
    UNION ALL
    SELECT sequence_no + 1 FROM sequence_numbers WHERE sequence_no < 40
), current_counts AS (
    SELECT assigned_rider_id AS rider_id, COUNT(*) AS current_count
    FROM delivery_order
    WHERE assigned_rider_id IS NOT NULL
    GROUP BY assigned_rider_id
), sample_orders AS (
    SELECT
        target.rider_id,
        numbers.sequence_no,
        CASE MOD(target.rider_id * 7 + numbers.sequence_no, 6)
            WHEN 0 THEN @c1 WHEN 1 THEN @c2 WHEN 2 THEN @c3
            WHEN 3 THEN @c4 WHEN 4 THEN @c5 ELSE @c6
            END AS customer_id,
        MOD(target.rider_id * 3 + numbers.sequence_no, 12) + 1 AS pickup_location_no,
        MOD(target.rider_id * 3 + numbers.sequence_no + 5, 12) + 1 AS destination_location_no,
        TIMESTAMPADD(
            DAY,
                target.rider_id * 2 + numbers.sequence_no,
                TIMESTAMP('2026-04-01 08:00:00.000')
        ) AS requested_at
    FROM seed_rider_order_target AS target
             LEFT JOIN current_counts AS counts ON counts.rider_id = target.rider_id
             JOIN sequence_numbers AS numbers
                  ON numbers.sequence_no <= target.target_count - COALESCE(counts.current_count, 0)
)
SELECT
    sample_order.customer_id,
    sample_order.rider_id,
    'COMPLETED',
    CONCAT(
            '80000000-0000-', LPAD(sample_order.rider_id, 4, '0'), '-0000-',
            LPAD(sample_order.sequence_no, 12, '0')
    ),
    CASE MOD(sample_order.rider_id + sample_order.sequence_no, 5)
        WHEN 0 THEN 'DOCUMENT' WHEN 1 THEN 'SMALL_PARCEL'
        WHEN 2 THEN 'MEDIUM_PARCEL' WHEN 3 THEN 'LARGE_PARCEL' ELSE 'FOOD'
        END,
    1200 + MOD(sample_order.rider_id * 317 + sample_order.sequence_no * 653, 12800),
    CONCAT(pickup.road_address, ' (출발 ', sample_order.rider_id, '-', sample_order.sequence_no, ')'),
    CONCAT(pickup.detail_address, ' / ', sample_order.rider_id, '-', sample_order.sequence_no),
    pickup.postal_code,
    CAST(pickup.latitude + (sample_order.rider_id * 100 + sample_order.sequence_no) / 10000000.0 AS DECIMAL(10, 7)),
    CAST(pickup.longitude + (sample_order.rider_id * 200 + sample_order.sequence_no * 2) / 10000000.0 AS DECIMAL(10, 7)),
    CONCAT(destination.road_address, ' (도착 ', sample_order.rider_id, '-', sample_order.sequence_no, ')'),
    CONCAT(destination.detail_address, ' / ', sample_order.rider_id, '-', sample_order.sequence_no),
    destination.postal_code,
    CAST(destination.latitude - (sample_order.rider_id * 100 + sample_order.sequence_no) / 10000000.0 AS DECIMAL(10, 7)),
    CAST(destination.longitude - (sample_order.rider_id * 200 + sample_order.sequence_no * 2) / 10000000.0 AS DECIMAL(10, 7)),
    CONCAT('발송인 ', sample_order.customer_id, '-', sample_order.sequence_no),
    CONCAT('0103', LPAD(sample_order.rider_id, 3, '0'), LPAD(sample_order.sequence_no, 4, '0')),
    CONCAT('수령인 ', sample_order.customer_id, '-', sample_order.sequence_no),
    CONCAT('0104', LPAD(sample_order.rider_id, 3, '0'), LPAD(sample_order.sequence_no, 4, '0')),
    sample_order.requested_at,
    DATE_ADD(sample_order.requested_at, INTERVAL 5 MINUTE),
    DATE_ADD(sample_order.requested_at, INTERVAL 10 MINUTE),
    DATE_ADD(sample_order.requested_at, INTERVAL 20 MINUTE),
    DATE_ADD(sample_order.requested_at, INTERVAL 25 MINUTE),
    DATE_ADD(sample_order.requested_at, INTERVAL 50 MINUTE),
    NULL,
    NULL
FROM sample_orders AS sample_order
         JOIN seed_location AS pickup ON pickup.location_no = sample_order.pickup_location_no
         JOIN seed_destination_location AS destination
              ON destination.location_no = sample_order.destination_location_no;

-- ---------------------------------------------------------------------------
-- 8. 운임 스냅샷과 고객 포인트 원장
-- ---------------------------------------------------------------------------
INSERT INTO order_fare_snapshot (
    order_id, fare_policy_id, fare_type, policy_version,
    calculation_distance_meters, base_fare, distance_fare,
    item_surcharge, total_fare, calculated_at
)
SELECT
    o.order_id, @fare_policy_id, 'ESTIMATE', 'LOCAL-1.0',
    o.straight_distance_meters,
    5000,
    CEIL(o.straight_distance_meters / 1000.0) * 1000,
    CASE o.item_type
        WHEN 'DOCUMENT' THEN 0 WHEN 'SMALL_PARCEL' THEN 500
        WHEN 'MEDIUM_PARCEL' THEN 1000 WHEN 'LARGE_PARCEL' THEN 2000 ELSE 1000
        END,
    5000 + CEIL(o.straight_distance_meters / 1000.0) * 1000
        + CASE o.item_type
              WHEN 'DOCUMENT' THEN 0 WHEN 'SMALL_PARCEL' THEN 500
              WHEN 'MEDIUM_PARCEL' THEN 1000 WHEN 'LARGE_PARCEL' THEN 2000 ELSE 1000
        END,
    o.requested_at
FROM delivery_order AS o;

INSERT INTO order_fare_snapshot (
    order_id, fare_policy_id, fare_type, policy_version,
    calculation_distance_meters, base_fare, distance_fare,
    item_surcharge, total_fare, calculated_at
)
SELECT
    o.order_id, @fare_policy_id, 'FINAL', 'LOCAL-1.0',
    o.straight_distance_meters + 300,
    5000,
    CEIL((o.straight_distance_meters + 300) / 1000.0) * 1000,
    CASE o.item_type
        WHEN 'DOCUMENT' THEN 0 WHEN 'SMALL_PARCEL' THEN 500
        WHEN 'MEDIUM_PARCEL' THEN 1000 WHEN 'LARGE_PARCEL' THEN 2000 ELSE 1000
        END,
    5000 + CEIL((o.straight_distance_meters + 300) / 1000.0) * 1000
        + CASE o.item_type
              WHEN 'DOCUMENT' THEN 0 WHEN 'SMALL_PARCEL' THEN 500
              WHEN 'MEDIUM_PARCEL' THEN 1000 WHEN 'LARGE_PARCEL' THEN 2000 ELSE 1000
        END,
    o.completed_at
FROM delivery_order AS o
WHERE o.status = 'COMPLETED';

INSERT INTO point_transaction (
    member_id, transaction_type, direction, amount,
    balance_before, balance_after, request_key, delivery_order_id, created_at
)
WITH order_debits AS (
    SELECT
        o.order_id, o.customer_id, o.request_key, o.requested_at, fs.total_fare,
        SUM(fs.total_fare) OVER (
            PARTITION BY o.customer_id ORDER BY o.requested_at, o.order_id
        ) AS cumulative_fare
    FROM delivery_order AS o
             JOIN order_fare_snapshot AS fs
                  ON fs.order_id = o.order_id AND fs.fare_type = 'ESTIMATE'
)
SELECT
    customer_id, 'ORDER_USE', 'DEBIT', total_fare,
    @customer_initial_balance - cumulative_fare + total_fare,
    @customer_initial_balance - cumulative_fare,
    request_key, order_id, requested_at + INTERVAL 1 SECOND
FROM order_debits;

SET @c6_debit_total = (
    SELECT SUM(fs.total_fare)
    FROM delivery_order AS o
    JOIN order_fare_snapshot AS fs ON fs.order_id = o.order_id AND fs.fare_type = 'ESTIMATE'
    WHERE o.customer_id = @c6
);
SET @c6_canceled_order = (
    SELECT order_id FROM delivery_order
    WHERE request_key = '10000000-0000-0000-0000-000000000006'
);
SET @c6_canceled_fare = (
    SELECT total_fare FROM order_fare_snapshot
    WHERE order_id = @c6_canceled_order AND fare_type = 'ESTIMATE'
);

INSERT INTO point_transaction (
    member_id, transaction_type, direction, amount,
    balance_before, balance_after, request_key, delivery_order_id, created_at
) VALUES (
             @c6, 'ORDER_REFUND', 'CREDIT', @c6_canceled_fare,
             @customer_initial_balance - @c6_debit_total,
             @customer_initial_balance - @c6_debit_total + @c6_canceled_fare,
             '10000000-0000-0000-0000-000000000006', @c6_canceled_order, '2026-08-03 13:05:00.000'
         );

UPDATE point_wallet AS pw
    JOIN (
    SELECT
    member_id,
    SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS net_amount
    FROM point_transaction
    GROUP BY member_id
    ) AS ledger ON ledger.member_id = pw.member_id
    JOIN member AS m ON m.member_id = pw.member_id
    SET pw.balance = ledger.net_amount
WHERE m.role = 'CUSTOMER';

-- ---------------------------------------------------------------------------
-- 9. 주문 상태 이력
-- ---------------------------------------------------------------------------
-- 완료 주문은 WAITING→ASSIGNED→MOVING_TO_PICKUP→PICKED_UP→DELIVERING→COMPLETED
-- 여섯 단계를 모두 가진다. 취소 주문은 정책상 WAITING→CANCELED만 가능하다.
INSERT INTO order_status_history (
    order_id, previous_status, new_status, action,
    actor_member_id, actor_type, reason, request_key, changed_at
)
SELECT
    o.order_id, NULL, 'WAITING', 'REQUEST_DELIVERY', o.customer_id, 'CUSTOMER', NULL,
    CONCAT('50000000-0000-0000-0000-', LPAD(o.order_id, 12, '0')), o.requested_at
FROM delivery_order AS o;

INSERT INTO order_status_history (
    order_id, previous_status, new_status, action,
    actor_member_id, actor_type, reason, request_key, changed_at
)
SELECT
    o.order_id, 'WAITING', 'ASSIGNED', 'ACCEPT_DELIVERY', o.assigned_rider_id, 'RIDER', NULL,
    CONCAT('51000000-0000-0000-0000-', LPAD(o.order_id, 12, '0')), o.assigned_at
FROM delivery_order AS o
WHERE o.status IN ('ASSIGNED', 'MOVING_TO_PICKUP', 'PICKED_UP', 'DELIVERING', 'COMPLETED');

INSERT INTO order_status_history (
    order_id, previous_status, new_status, action,
    actor_member_id, actor_type, reason, request_key, changed_at
)
SELECT
    o.order_id, 'ASSIGNED', 'MOVING_TO_PICKUP', 'START_MOVING_TO_PICKUP', o.assigned_rider_id, 'RIDER', NULL,
    CONCAT('52000000-0000-0000-0000-', LPAD(o.order_id, 12, '0')), o.moving_to_pickup_at
FROM delivery_order AS o
WHERE o.status IN ('MOVING_TO_PICKUP', 'PICKED_UP', 'DELIVERING', 'COMPLETED');

INSERT INTO order_status_history (
    order_id, previous_status, new_status, action,
    actor_member_id, actor_type, reason, request_key, changed_at
)
SELECT
    o.order_id, 'MOVING_TO_PICKUP', 'PICKED_UP', 'CONFIRM_PICKUP', o.assigned_rider_id, 'RIDER', NULL,
    CONCAT('53000000-0000-0000-0000-', LPAD(o.order_id, 12, '0')), o.picked_up_at
FROM delivery_order AS o
WHERE o.status IN ('PICKED_UP', 'DELIVERING', 'COMPLETED');

INSERT INTO order_status_history (
    order_id, previous_status, new_status, action,
    actor_member_id, actor_type, reason, request_key, changed_at
)
SELECT
    o.order_id, 'PICKED_UP', 'DELIVERING', 'START_DELIVERY', o.assigned_rider_id, 'RIDER', NULL,
    CONCAT('54000000-0000-0000-0000-', LPAD(o.order_id, 12, '0')), o.delivering_at
FROM delivery_order AS o
WHERE o.status IN ('DELIVERING', 'COMPLETED');

INSERT INTO order_status_history (
    order_id, previous_status, new_status, action,
    actor_member_id, actor_type, reason, request_key, changed_at
)
SELECT
    o.order_id, 'DELIVERING', 'COMPLETED', 'COMPLETE_DELIVERY', o.assigned_rider_id, 'RIDER', NULL,
    CONCAT('55000000-0000-0000-0000-', LPAD(o.order_id, 12, '0')), o.completed_at
FROM delivery_order AS o
WHERE o.status = 'COMPLETED';

INSERT INTO order_status_history (
    order_id, previous_status, new_status, action,
    actor_member_id, actor_type, reason, request_key, changed_at
)
SELECT
    o.order_id, 'WAITING', 'CANCELED', 'CANCEL_DELIVERY', o.customer_id, 'CUSTOMER', o.cancel_reason,
    CONCAT('56000000-0000-0000-0000-', LPAD(o.order_id, 12, '0')), o.canceled_at
FROM delivery_order AS o
WHERE o.status = 'CANCELED';

-- ---------------------------------------------------------------------------
-- 10. 완료 증빙, 위치, 정산과 라이더 거래 원장
-- ---------------------------------------------------------------------------
INSERT INTO delivery_proof (order_id, rider_id, proof_type, proof_value, created_at)
SELECT
    o.order_id, o.assigned_rider_id,
    CASE MOD(o.order_id, 3)
        WHEN 0 THEN 'PHOTO' WHEN 1 THEN 'RECIPIENT_CONFIRMATION' ELSE 'AUTH_CODE'
        END,
    CONCAT('local-proof-', o.order_id), o.completed_at
FROM delivery_order AS o
WHERE o.status = 'COMPLETED';

SET @delivering_order_id = (
    SELECT order_id FROM delivery_order
    WHERE request_key = '10000000-0000-0000-0000-000000000004'
);

INSERT INTO rider_location_history (
    order_id, rider_id, latitude, longitude, accuracy_meters, measured_at, stored_at
) VALUES
      (@delivering_order_id, @rbusy3, 37.5500000, 126.9300000, 8.50, '2026-08-03 12:26:00.000', '2026-08-03 12:26:00.100'),
      (@delivering_order_id, @rbusy3, 37.5450000, 126.9290000, 7.20, '2026-08-03 12:27:00.000', '2026-08-03 12:27:00.100'),
      (@delivering_order_id, @rbusy3, 37.5350000, 126.9285000, 6.80, '2026-08-03 12:28:00.000', '2026-08-03 12:28:00.100');

INSERT INTO rider_settlement (
    order_id, rider_id, final_fare_snapshot_id, settlement_amount, settled_at
)
SELECT
    o.order_id, o.assigned_rider_id, fs.fare_snapshot_id, fs.total_fare, o.completed_at
FROM delivery_order AS o
         JOIN order_fare_snapshot AS fs
              ON fs.order_id = o.order_id AND fs.fare_type = 'FINAL'
WHERE o.status = 'COMPLETED';

INSERT INTO point_transaction (
    member_id, transaction_type, direction, amount,
    balance_before, balance_after, request_key, rider_settlement_id, created_at
)
WITH settlement_credits AS (
    SELECT
        rs.settlement_id, rs.rider_id, rs.settlement_amount, rs.settled_at,
        SUM(rs.settlement_amount) OVER (
            PARTITION BY rs.rider_id ORDER BY rs.settled_at, rs.settlement_id
        ) AS cumulative_settlement
    FROM rider_settlement AS rs
)
SELECT
    credit.rider_id, 'SETTLEMENT', 'CREDIT', credit.settlement_amount,
    pw.balance + credit.cumulative_settlement - credit.settlement_amount,
    pw.balance + credit.cumulative_settlement,
    CONCAT('30000000-0000-0000-0000-', LPAD(credit.settlement_id, 12, '0')),
    credit.settlement_id, credit.settled_at
FROM settlement_credits AS credit
         JOIN point_wallet AS pw ON pw.member_id = credit.rider_id;

UPDATE point_wallet AS pw
    JOIN (
    SELECT rider_id, SUM(settlement_amount) AS total_settlement
    FROM rider_settlement GROUP BY rider_id
    ) AS settlement ON settlement.rider_id = pw.member_id
    SET pw.balance = pw.balance + settlement.total_settlement;

-- 출금 상태와 WITHDRAWAL/WITHDRAWAL_REFUND 거래 유형도 함께 만든다.
SET @rpending1_masked_account = (
    SELECT masked_account_number FROM rider_payout_account WHERE rider_id = @rpending1
);

INSERT INTO rider_withdrawal (
    rider_id, request_key, amount,
    bank_code_snapshot, masked_account_number_snapshot, account_holder_name_snapshot,
    status, failure_reason, points_restored, requested_at, processed_at
) VALUES
      (@rpending1, '40000000-0000-0000-0000-000000000001', 2000,
       'TEST_BANK', @rpending1_masked_account, '콜 대기 라이더 1',
       'PENDING', NULL, 0, '2026-08-03 13:00:00.000', NULL),
      (@rpending1, '40000000-0000-0000-0000-000000000002', 4000,
       'TEST_BANK', @rpending1_masked_account, '콜 대기 라이더 1',
       'COMPLETED', NULL, 0, '2026-08-03 14:00:00.000', '2026-08-03 14:05:00.000'),
      (@rpending1, '40000000-0000-0000-0000-000000000003', 5000,
       'TEST_BANK', @rpending1_masked_account, '콜 대기 라이더 1',
       'FAILED', '수취 계좌 점검 필요', 1, '2026-08-03 15:00:00.000', '2026-08-03 15:05:00.000');

SET @withdrawal_pending = (SELECT withdrawal_id FROM rider_withdrawal WHERE request_key = '40000000-0000-0000-0000-000000000001');
SET @withdrawal_completed = (SELECT withdrawal_id FROM rider_withdrawal WHERE request_key = '40000000-0000-0000-0000-000000000002');
SET @withdrawal_failed = (SELECT withdrawal_id FROM rider_withdrawal WHERE request_key = '40000000-0000-0000-0000-000000000003');
SET @rider_balance = (SELECT balance FROM point_wallet WHERE member_id = @rpending1);

INSERT INTO point_transaction (
    member_id, transaction_type, direction, amount,
    balance_before, balance_after, request_key, rider_withdrawal_id, created_at
) VALUES
      (@rpending1, 'WITHDRAWAL', 'DEBIT', 2000, @rider_balance, @rider_balance - 2000,
       '40000000-0000-0000-0000-000000000001', @withdrawal_pending, '2026-08-03 13:00:00.000'),
      (@rpending1, 'WITHDRAWAL', 'DEBIT', 4000, @rider_balance - 2000, @rider_balance - 6000,
       '40000000-0000-0000-0000-000000000002', @withdrawal_completed, '2026-08-03 14:00:00.000'),
      (@rpending1, 'WITHDRAWAL', 'DEBIT', 5000, @rider_balance - 6000, @rider_balance - 11000,
       '40000000-0000-0000-0000-000000000003', @withdrawal_failed, '2026-08-03 15:00:00.000'),
      (@rpending1, 'WITHDRAWAL_REFUND', 'CREDIT', 5000, @rider_balance - 11000, @rider_balance - 6000,
       '40000000-0000-0000-0000-000000000003', @withdrawal_failed, '2026-08-03 15:05:00.000');

UPDATE point_wallet
SET balance = @rider_balance - 6000
WHERE member_id = @rpending1;

COMMIT;

-- ---------------------------------------------------------------------------
-- 11. 실행 결과 검증
-- ---------------------------------------------------------------------------
SELECT
    m.login_id,
    rp.operating_status,
    target.target_count,
    COUNT(o.order_id) AS actual_order_count,
    pw.balance
FROM seed_rider_order_target AS target
         JOIN member AS m ON m.member_id = target.rider_id
         JOIN rider_profile AS rp ON rp.member_id = target.rider_id
         JOIN point_wallet AS pw ON pw.member_id = target.rider_id
         LEFT JOIN delivery_order AS o ON o.assigned_rider_id = target.rider_id
GROUP BY m.member_id, m.login_id, rp.operating_status, target.target_count, pw.balance
ORDER BY rp.operating_status, m.login_id;

SELECT
    m.login_id,
    COUNT(o.order_id) AS total_order_count,
    SUM(o.status IN ('WAITING', 'ASSIGNED', 'MOVING_TO_PICKUP', 'PICKED_UP', 'DELIVERING')) AS active_order_count
FROM member AS m
         LEFT JOIN delivery_order AS o ON o.customer_id = m.member_id
WHERE m.role = 'CUSTOMER'
GROUP BY m.member_id, m.login_id
ORDER BY m.login_id;

SELECT new_status, COUNT(*) AS history_count
FROM order_status_history
GROUP BY new_status
ORDER BY new_status;

-- 현재 상태별 주문이 시작 상태부터 필요한 모든 이력을 가지고 있는지 확인한다.
-- 예상 이력 수: WAITING 1, ASSIGNED 2, MOVING 3, PICKED_UP 4,
-- DELIVERING 5, COMPLETED 6, CANCELED 2.
SELECT
    order_with_history.status,
    MIN(order_with_history.history_count) AS min_history_count,
    MAX(order_with_history.history_count) AS max_history_count
FROM (
         SELECT o.order_id, o.status, COUNT(h.status_history_id) AS history_count
         FROM delivery_order AS o
                  JOIN order_status_history AS h ON h.order_id = o.order_id
         GROUP BY o.order_id, o.status
     ) AS order_with_history
GROUP BY order_with_history.status
ORDER BY order_with_history.status;

SELECT
    COUNT(*) AS total_orders,
    COUNT(DISTINCT pickup_road_address) AS unique_pickup_addresses,
    COUNT(DISTINCT destination_road_address) AS unique_destination_addresses,
    COUNT(DISTINCT CONCAT(pickup_latitude, ',', pickup_longitude)) AS unique_pickup_coordinates,
    COUNT(DISTINCT CONCAT(destination_latitude, ',', destination_longitude)) AS unique_destination_coordinates
FROM delivery_order;

SELECT transaction_type, direction, COUNT(*) AS transaction_count
FROM point_transaction
GROUP BY transaction_type, direction
ORDER BY transaction_type;