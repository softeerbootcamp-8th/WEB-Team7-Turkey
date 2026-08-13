-- 실사용 근사 혼합 시나리오 시드: BUSY 라이더+추적 고객 / AVAILABLE 라이더 / 콜 목록용 WAITING 풀.
-- reset-and-seed-local.sql 을 먼저 실행한 뒤에 돌린다(그쪽이 전체 TRUNCATE 로 시작한다).
--
--   cd backend
--   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/reset-and-seed-local.sql
--   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/seed-loadtest-mixed.sql
--
-- 만드는 것:
--   lt_r1..lt_r{@busy}   BUSY 라이더 (위치 전송 대상)
--   lt_c1..lt_c{@busy}   그 라이더의 진행 중(DELIVERING) 배송을 추적하는 고객
--   lt_a1..lt_a{@available}  AVAILABLE 라이더 (콜 목록 폴링 대상)
--   lt_w1..lt_w{@waiting}    콜 목록이 찾을 WAITING 주문의 고객
--
-- seed-loadtest-riders.sql / seed-loadtest-call-list.sql 을 순서대로 그냥 이어 돌리면 안 된다 —
-- 둘 다 정리 단계에서 `login_id LIKE 'lt\_%'`로 넓게 지우기 때문에 뒤에 도는 스크립트가 앞선
-- 스크립트의 데이터를 지운다. 이 스크립트는 정리를 한 번만, 네 접두어를 전부 합쳐서 한다.
--
-- !! 시드 직후에 k6 를 바로 시작할 것 !!
-- WAITING 주문(lt_w*)은 배차 대기 자동 취소 스캐너(#42, 60초 주기, 타임아웃 5분)의 대상이다.
-- requested_at 을 "지금부터 2분 전 이내"로 넣으므로, 시드 후 3분 안에 부하가 시작되지 않으면
-- 콜 목록이 빈 결과로 수렴한다.
USE turkey;

SET SESSION cte_max_recursion_depth = 5000;

SET @busy := 700;        -- BUSY 라이더(=위치 전송) 수 = 추적 고객 수
SET @available := 300;   -- AVAILABLE 라이더(=콜 목록 폴링) 수
SET @waiting := 500;     -- 콜 목록이 찾을 WAITING 주문 수(=그 주문의 고객 수)
SET @password_hash := '$2y$10$Lt6WtA6CYLmEjEtyFIQKTOwZN3QZqsumCPe6eSBLuiPZXfEohtbpy'; -- 'aa'
SET @fare_policy_id := (SELECT fare_policy_id FROM fare_policy WHERE policy_version = 'LOCAL-1.0');

-- 정리(한 번만, 네 접두어 전부). FK 순서: 거래내역·스냅샷 → 주문 → 지갑/프로필 → 회원.
-- point_transaction 은 만료 스캐너의 자동 취소·환급(WAITING → CANCELED)이 재실행 사이에
-- 이미 만들어 놨을 수 있다 — 먼저 안 지우면 delivery_order 삭제가 FK 위반으로 실패한다.
DELETE pt FROM point_transaction pt
    JOIN delivery_order o ON o.order_id = pt.delivery_order_id
    JOIN member m ON m.member_id = o.customer_id
WHERE m.login_id LIKE 'lt\_r%' OR m.login_id LIKE 'lt\_c%' OR m.login_id LIKE 'lt\_w%';
DELETE fs FROM order_fare_snapshot fs
    JOIN delivery_order o ON o.order_id = fs.order_id
    JOIN member m ON m.member_id = o.customer_id
WHERE m.login_id LIKE 'lt\_w%';
DELETE o FROM delivery_order o
    JOIN member m ON m.member_id IN (o.customer_id, o.assigned_rider_id)
WHERE m.login_id LIKE 'lt\_r%' OR m.login_id LIKE 'lt\_c%' OR m.login_id LIKE 'lt\_w%';
DELETE pw FROM point_wallet pw JOIN member m ON m.member_id = pw.member_id
WHERE m.login_id LIKE 'lt\_w%';
DELETE rp FROM rider_profile rp JOIN member m ON m.member_id = rp.member_id
WHERE m.login_id LIKE 'lt\_r%' OR m.login_id LIKE 'lt\_a%';
DELETE FROM member
WHERE login_id LIKE 'lt\_r%' OR login_id LIKE 'lt\_c%' OR login_id LIKE 'lt\_a%' OR login_id LIKE 'lt\_w%';

-- ── BUSY 라이더 + 추적 고객 + DELIVERING 배송(seed-loadtest-riders.sql과 동일 패턴) ──
INSERT INTO member (login_id, password_hash, name, phone_number, role, status)
WITH RECURSIVE seq(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < @busy)
SELECT CONCAT('lt_c', i), @password_hash, CONCAT('부하 고객 ', i),
       CONCAT('0108', LPAD(i, 7, '0')), 'CUSTOMER', 'ACTIVE'
FROM seq;

INSERT INTO member (login_id, password_hash, name, phone_number, role, status)
WITH RECURSIVE seq(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < @busy)
SELECT CONCAT('lt_r', i), @password_hash, CONCAT('부하 라이더 ', i),
       CONCAT('0109', LPAD(i, 7, '0')), 'RIDER', 'ACTIVE'
FROM seq;

INSERT INTO rider_profile (member_id, operating_status, status_changed_at)
SELECT member_id, 'BUSY', NOW(3) FROM member WHERE login_id LIKE 'lt\_r%';

INSERT INTO delivery_order (
    customer_id, assigned_rider_id, status, request_key, item_type,
    straight_distance_meters,
    pickup_road_address, pickup_postal_code, pickup_latitude, pickup_longitude,
    destination_road_address, destination_postal_code, destination_latitude, destination_longitude,
    sender_name, sender_phone_number, recipient_name, recipient_phone_number,
    requested_at, assigned_at, moving_to_pickup_at, picked_up_at, delivering_at
)
SELECT c.member_id,
       r.member_id,
       'DELIVERING',
       CONCAT('40000000-0000-0000-0000-', LPAD(r.i, 12, '0')),
       'DOCUMENT',
       1000 + r.i,
       CONCAT('서울특별시 부하구 테스트로 ', r.i), '04524',
       37.4500 + (r.i % 50) * 0.002, 126.8500 + (r.i % 50) * 0.004,
       CONCAT('서울특별시 부하구 도착로 ', r.i), '06232',
       37.4600 + (r.i % 50) * 0.002, 126.9500 + (r.i % 50) * 0.004,
       CONCAT('발송인 ', r.i), CONCAT('0106', LPAD(r.i, 7, '0')),
       CONCAT('수령인 ', r.i), CONCAT('0107', LPAD(r.i, 7, '0')),
       NOW(3) - INTERVAL 30 MINUTE, NOW(3) - INTERVAL 25 MINUTE,
       NOW(3) - INTERVAL 20 MINUTE, NOW(3) - INTERVAL 15 MINUTE, NOW(3) - INTERVAL 10 MINUTE
FROM (SELECT CAST(SUBSTRING(login_id, 5) AS UNSIGNED) AS i, member_id FROM member WHERE login_id LIKE 'lt\_r%') r
         JOIN (SELECT CAST(SUBSTRING(login_id, 5) AS UNSIGNED) AS i, member_id FROM member WHERE login_id LIKE 'lt\_c%') c
              ON c.i = r.i;

-- ── AVAILABLE 라이더(콜 목록 폴링 대상, seed-loadtest-call-list.sql과 동일 패턴) ──
INSERT INTO member (login_id, password_hash, name, phone_number, role, status)
WITH RECURSIVE seq(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < @available)
SELECT CONCAT('lt_a', i), @password_hash, CONCAT('콜목록 라이더 ', i),
       CONCAT('0103', LPAD(i, 7, '0')), 'RIDER', 'ACTIVE'
FROM seq;

INSERT INTO rider_profile (member_id, operating_status, status_changed_at)
SELECT member_id, 'AVAILABLE', NOW(3) FROM member WHERE login_id LIKE 'lt\_a%';

-- ── WAITING 풀(콜 목록이 찾을 대상) ──
INSERT INTO member (login_id, password_hash, name, phone_number, role, status)
WITH RECURSIVE seq(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < @waiting)
SELECT CONCAT('lt_w', i), @password_hash, CONCAT('콜목록 고객 ', i),
       CONCAT('0102', LPAD(i, 7, '0')), 'CUSTOMER', 'ACTIVE'
FROM seq;

-- 스캐너가 만료 취소하면 환급 대상 지갑이 필요하다(없으면 매 분 예외, seed-loadtest-call-list.sql 비고).
INSERT INTO point_wallet (member_id, balance)
SELECT member_id, 1000000 FROM member WHERE login_id LIKE 'lt\_w%';

INSERT INTO delivery_order (
    customer_id, assigned_rider_id, status, request_key, item_type,
    straight_distance_meters,
    pickup_road_address, pickup_postal_code, pickup_latitude, pickup_longitude,
    destination_road_address, destination_postal_code, destination_latitude, destination_longitude,
    sender_name, sender_phone_number, recipient_name, recipient_phone_number,
    requested_at
)
SELECT c.member_id,
       NULL,
       'WAITING',
       CONCAT('50000000-0000-0000-0000-', LPAD(c.i, 12, '0')),
       ELT(1 + (c.i % 5), 'DOCUMENT', 'SMALL_PARCEL', 'MEDIUM_PARCEL', 'LARGE_PARCEL', 'FOOD'),
       1000 + (c.i * 137 % 19000),
       CONCAT('서울특별시 콜구 대기로 ', c.i), '04524',
       37.430 + (c.i * 37 % 270) * 0.001,
       126.800 + (c.i * 53 % 380) * 0.001,
       CONCAT('서울특별시 콜구 도착로 ', c.i), '06232',
       37.440 + (c.i * 41 % 260) * 0.001,
       126.810 + (c.i * 59 % 370) * 0.001,
       CONCAT('발송인 ', c.i), CONCAT('0104', LPAD(c.i, 7, '0')),
       CONCAT('수령인 ', c.i), CONCAT('0105', LPAD(c.i, 7, '0')),
       NOW(3) - INTERVAL (c.i % 120) SECOND
FROM (SELECT CAST(SUBSTRING(login_id, 5) AS UNSIGNED) AS i, member_id
      FROM member WHERE login_id LIKE 'lt\_w%') c;

INSERT INTO order_fare_snapshot (
    order_id, fare_policy_id, fare_type, policy_version,
    calculation_distance_meters, base_fare, distance_fare, item_surcharge, total_fare
)
SELECT o.order_id, @fare_policy_id, 'ESTIMATE', 'LOCAL-1.0',
       o.straight_distance_meters,
       5000,
       FLOOR((o.straight_distance_meters + 999) / 1000) * 1000,
       s.surcharge_amount,
       5000 + FLOOR((o.straight_distance_meters + 999) / 1000) * 1000 + s.surcharge_amount
FROM delivery_order o
         JOIN member m ON m.member_id = o.customer_id
         JOIN item_type_surcharge s
              ON s.fare_policy_id = @fare_policy_id AND s.item_type = o.item_type
WHERE m.login_id LIKE 'lt\_w%';

-- 컬럼 별칭은 ASCII 로 둔다 — mysql 클라이언트 기본 문자셋에서 한글 식별자가 깨진다.
SELECT
    (SELECT COUNT(*) FROM member m JOIN rider_profile rp ON rp.member_id = m.member_id
     WHERE m.login_id LIKE 'lt\_r%' AND rp.operating_status = 'BUSY') AS busy_riders,
    (SELECT COUNT(*) FROM delivery_order o JOIN member m ON m.member_id = o.assigned_rider_id
     WHERE m.login_id LIKE 'lt\_r%' AND o.status = 'DELIVERING') AS delivering_orders,
    (SELECT COUNT(*) FROM member m JOIN rider_profile rp ON rp.member_id = m.member_id
     WHERE m.login_id LIKE 'lt\_a%' AND rp.operating_status = 'AVAILABLE') AS available_riders,
    (SELECT COUNT(*) FROM delivery_order o JOIN member m ON m.member_id = o.customer_id
     WHERE m.login_id LIKE 'lt\_w%' AND o.status = 'WAITING') AS waiting_orders;
