-- 콜 목록 조회(GET /api/rider/requests) 부하 테스트용 시드.
-- reset-and-seed-local.sql 을 먼저 실행한 뒤에 돌린다(그쪽이 전체 TRUNCATE 로 시작한다).
--
--   cd backend
--   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/reset-and-seed-local.sql
--   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/seed-loadtest-call-list.sql
--
-- 만드는 것: AVAILABLE 라이더 @riders 명(lt_a1..) + WAITING 주문 @orders 건(고객 lt_w1.., 주문당
-- ESTIMATE 운임 스냅샷 1건). 비밀번호는 기본 시드와 같은 'aa'.
--
-- 왜 이 형태인가:
--  * 콜 목록은 라이더가 AVAILABLE 이어야 200 이 나온다(아니면 403). 기본 시드의 AVAILABLE 은
--    rpending1~3 뿐이라 VU 수십 개가 세션 3개를 공유한다.
--  * 고객은 진행 중 주문을 1건만 가질 수 있어(uk_delivery_active_customer) WAITING 1건마다
--    고객이 1명씩 필요하다. 그래서 고객 수 = 주문 수다.
--  * 주문마다 ESTIMATE 스냅샷이 없으면 서비스가 IllegalStateException 을 던져 500 이 된다
--    (도메인 불변식). 목록 조회는 이 스냅샷을 IN 절로 한 번에 읽는다.
--  * 픽업 좌표를 서울 전역(위도 37.430~37.700 / 경도 126.800~127.180)에 흩는다. 한 점에 몰면
--    bounding box 인덱스가 비현실적으로 유리하거나 불리해진다.
--
-- !! 시드 직후에 부하를 돌릴 것 !!
-- 배차 대기 자동 취소 스캐너(#42, DeliveryOrderExpiryScheduler, 60초 주기)가 requested_at 이
-- 5분보다 오래된 WAITING 을 취소한다. 아래는 requested_at 을 "지금부터 2분 전 이내"로 넣으므로,
-- 시드 후 3분 안에 시작하지 않으면 측정 도중 주문이 사라진다(목록이 비어 조회 비용이 0에 수렴).
USE turkey;

SET SESSION cte_max_recursion_depth = 100000;

SET @riders := 100;   -- MAX_VU 보다 많아야 한다(계정 공유 왜곡 방지)
SET @orders := 8000;  -- WAITING 건수. 좌표 없는 arm 의 비용을 정하는 변수다
-- 기본 시드와 같은 'aa' 의 bcrypt 해시.
SET @password_hash := '$2y$10$Lt6WtA6CYLmEjEtyFIQKTOwZN3QZqsumCPe6eSBLuiPZXfEohtbpy';
SET @fare_policy_id := (SELECT fare_policy_id FROM fare_policy WHERE policy_version = 'LOCAL-1.0');

-- 재실행 가능하게 이전 데이터를 지운다(FK 순서: 스냅샷 → 주문 → 지갑/프로필 → 회원).
DELETE fs FROM order_fare_snapshot fs
    JOIN delivery_order o ON o.order_id = fs.order_id
    JOIN member m ON m.member_id = o.customer_id
WHERE m.login_id LIKE 'lt\_w%';
DELETE o FROM delivery_order o JOIN member m ON m.member_id = o.customer_id
WHERE m.login_id LIKE 'lt\_w%';
DELETE pw FROM point_wallet pw JOIN member m ON m.member_id = pw.member_id
WHERE m.login_id LIKE 'lt\_w%';
DELETE rp FROM rider_profile rp JOIN member m ON m.member_id = rp.member_id
WHERE m.login_id LIKE 'lt\_a%';
DELETE FROM member WHERE login_id LIKE 'lt\_w%' OR login_id LIKE 'lt\_a%';

-- 고객 = WAITING 주문 수.
INSERT INTO member (login_id, password_hash, name, phone_number, role, status)
WITH RECURSIVE seq(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < @orders)
SELECT CONCAT('lt_w', i), @password_hash, CONCAT('콜목록 고객 ', i),
       CONCAT('0102', LPAD(i, 7, '0')), 'CUSTOMER', 'ACTIVE'
FROM seq;

-- 스캐너가 혹시 이 주문들을 만료 취소하게 되면 환급 대상 지갑이 필요하다(없으면 매 분 예외).
INSERT INTO point_wallet (member_id, balance)
SELECT member_id, 1000000 FROM member WHERE login_id LIKE 'lt\_w%';

-- 라이더는 AVAILABLE 이어야 콜 목록을 조회할 수 있다.
INSERT INTO member (login_id, password_hash, name, phone_number, role, status)
WITH RECURSIVE seq(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < @riders)
SELECT CONCAT('lt_a', i), @password_hash, CONCAT('콜목록 라이더 ', i),
       CONCAT('0103', LPAD(i, 7, '0')), 'RIDER', 'ACTIVE'
FROM seq;

INSERT INTO rider_profile (member_id, operating_status, status_changed_at)
SELECT member_id, 'AVAILABLE', NOW(3) FROM member WHERE login_id LIKE 'lt\_a%';

-- WAITING 주문. 좌표는 서로 소인 곱셈 모듈러로 격자에 고르게 흩는다(i 순서대로 늘어놓으면
-- 한 줄로 늘어서 bounding box 가 거의 안 걸리거나 전부 걸린다).
-- 'lt_w' 가 4글자라 번호는 5번째 문자부터다.
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
       CONCAT('30000000-0000-0000-0000-', LPAD(c.i, 12, '0')),
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
       -- 최대 2분 전. 5분을 넘기면 만료 스캐너가 지운다(위 주의 참고).
       NOW(3) - INTERVAL (c.i % 120) SECOND
FROM (SELECT CAST(SUBSTRING(login_id, 5) AS UNSIGNED) AS i, member_id
      FROM member WHERE login_id LIKE 'lt\_w%') c;

-- 예상 운임 스냅샷. 기본 요금정책(base 5000 / 1km 당 1000)과 같은 식으로 계산하고,
-- 품목 할증은 item_type_surcharge 에서 가져온다. total = base + distance + surcharge 를
-- DB CHECK 가 강제하므로 정수 연산으로 맞춘다.
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
SELECT (SELECT COUNT(*) FROM member m JOIN rider_profile rp ON rp.member_id = m.member_id
        WHERE m.login_id LIKE 'lt\_a%' AND rp.operating_status = 'AVAILABLE') AS available_riders,
       (SELECT COUNT(*) FROM delivery_order WHERE status = 'WAITING') AS waiting_orders,
       (SELECT COUNT(*) FROM order_fare_snapshot fs JOIN delivery_order o ON o.order_id = fs.order_id
        WHERE o.status = 'WAITING' AND fs.fare_type = 'ESTIMATE') AS estimate_snapshots;
