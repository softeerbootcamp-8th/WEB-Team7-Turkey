-- 부하 테스트용 BUSY 라이더를 N명 만든다. reset-and-seed-local.sql 을 먼저 실행한 뒤에 돌린다
-- (그쪽이 전체 TRUNCATE 로 시작하므로 순서를 바꾸면 이 데이터가 지워진다).
--
--   cd backend
--   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/reset-and-seed-local.sql
--   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/seed-loadtest-riders.sql
--
-- 왜 필요한가: 기본 시드의 BUSY 라이더는 3명뿐이라 VU 수십 개가 세션 3개와 배송 3건을 공유한다.
-- 그러면 (1) 같은 행만 조회해 버퍼풀·캐시가 비현실적으로 유리하고, (2) 같은 Redis 키에 위치를
-- 몰아써서 saveIfNewer 경쟁이 과장된다(실측 절반 폐기). VU 당 라이더 1명이 되게 늘린다.
--
-- 계정: lt_r1..lt_rN (라이더, BUSY) / lt_c1..lt_cN (고객). 비밀번호는 기본 시드와 같은 'aa'.
--
-- 주의: 재귀 CTE 기본 상한(cte_max_recursion_depth)이 1000 이다. 그보다 많이 만들려면
-- 이 세션에서 SET SESSION cte_max_recursion_depth = <값> 을 먼저 실행해야 한다.
USE turkey;

SET @n := 100;
-- 기본 시드와 같은 'aa' 의 bcrypt 해시.
SET @password_hash := '$2y$10$Lt6WtA6CYLmEjEtyFIQKTOwZN3QZqsumCPe6eSBLuiPZXfEohtbpy';

-- 재실행 가능하게 이전 lt_* 데이터를 먼저 지운다(FK 순서: 주문 → 프로필 → 회원).
DELETE o FROM delivery_order o
    JOIN member m ON m.member_id IN (o.customer_id, o.assigned_rider_id)
WHERE m.login_id LIKE 'lt\_%';
DELETE rp FROM rider_profile rp JOIN member m ON m.member_id = rp.member_id
WHERE m.login_id LIKE 'lt\_%';
DELETE FROM member WHERE login_id LIKE 'lt\_%';

-- 고객 N명. 진행 중 주문은 고객당 1건만 허용되므로(uk_delivery_active_customer)
-- 라이더 수와 같은 수의 고객이 필요하다.
INSERT INTO member (login_id, password_hash, name, phone_number, role, status)
WITH RECURSIVE seq(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < @n)
SELECT CONCAT('lt_c', i), @password_hash, CONCAT('부하 고객 ', i),
       CONCAT('0108', LPAD(i, 7, '0')), 'CUSTOMER', 'ACTIVE'
FROM seq;

-- 라이더 N명.
INSERT INTO member (login_id, password_hash, name, phone_number, role, status)
WITH RECURSIVE seq(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < @n)
SELECT CONCAT('lt_r', i), @password_hash, CONCAT('부하 라이더 ', i),
       CONCAT('0109', LPAD(i, 7, '0')), 'RIDER', 'ACTIVE'
FROM seq;

INSERT INTO rider_profile (member_id, operating_status, status_changed_at)
SELECT member_id, 'BUSY', NOW(3) FROM member WHERE login_id LIKE 'lt\_r%';

-- 라이더별 진행 중 배송 1건. status 가 추적 가능 상태여야 위치 갱신이 발행할 채널을 찾는다
-- (DELIVERING 을 쓴다). 좌표는 행마다 다르게 흩어 놓는다 — 같은 값이면 인덱스·버퍼풀 접근이
-- 실제보다 유리해진다.
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
       CONCAT('20000000-0000-0000-0000-', LPAD(r.i, 12, '0')),
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
-- 'lt_r' 가 4글자라 번호는 5번째 문자부터다.
FROM (SELECT CAST(SUBSTRING(login_id, 5) AS UNSIGNED) AS i, member_id FROM member WHERE login_id LIKE 'lt\_r%') r
         JOIN (SELECT CAST(SUBSTRING(login_id, 5) AS UNSIGNED) AS i, member_id FROM member WHERE login_id LIKE 'lt\_c%') c
              ON c.i = r.i;

-- 컬럼 별칭은 ASCII 로 둔다 — mysql 클라이언트 기본 문자셋에서 한글 식별자가 깨진다.
SELECT COUNT(*) AS busy_riders,
       (SELECT COUNT(*) FROM delivery_order o JOIN member m ON m.member_id = o.assigned_rider_id
        WHERE m.login_id LIKE 'lt\_r%' AND o.active_rider_id IS NOT NULL) AS in_progress_deliveries
FROM member m JOIN rider_profile rp ON rp.member_id = m.member_id
WHERE m.login_id LIKE 'lt\_r%' AND rp.operating_status = 'BUSY';
