-- 실사용 근사 혼합 시나리오 시드: BUSY 라이더+추적 고객 / AVAILABLE 라이더 / 콜 목록용 WAITING 풀.
-- reset-and-seed-local.sql 을 먼저 실행한 뒤에 돌린다(그쪽이 전체 TRUNCATE 로 시작한다).
--
--   cd backend
--   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/reset-and-seed-local.sql
--   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/seed-loadtest-mixed.sql
--
-- 만드는 것:
--   lt_r1..lt_r{@busy}   BUSY 라이더 (위치 전송 대상)
--   lt_c1..lt_c{@busy}   그 라이더의 진행 중(ASSIGNED 시작 — k6 가 완료까지 진행시킨다) 배송을
--                        추적하는 고객
--   lt_a1..lt_a{@available}  AVAILABLE 라이더 (콜 목록 폴링 대상)
--   lt_w1..lt_w{@waiting}    콜 목록이 찾을 WAITING 주문의 고객
--   lt_w 고객당 과거 COMPLETED 주문 다수 — WAITING 이 전체 주문의 10%가 되도록 채우는 이력.
--   실제 운영 DB는 대부분 종결된 과거 주문이고 지금 살아있는 WAITING 은 일부라는 걸 근사한다
--   (전부 WAITING 인 채로 재면 콜 목록 인덱스가 실제보다 훨씬 좁은 후보군을 본다).
--   lt_oc1..lt_oc{@order_customers}  주문 생성 고객(진행 중 주문 없음 + 포인트 충분) —
--   테스트 시작 시 k6가 견적→생성을 계정당 1회 호출한다(진행 중 주문 1건 제한이라 반복 불가).
--   @completed_history_extra 건의 추가 COMPLETED 이력 — 위 waiting_ratio 계산과 무관하게,
--   DB 인스턴스가 오래 운영돼 대량의 종결 주문이 쌓인 상태를 그대로 재현하려고 고정 건수를
--   더 얹는다(#502, 힙·GC·Hikari 튜닝이 테이블 크기가 커진 상태에서도 유효한지 확인 목적).
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

SET SESSION cte_max_recursion_depth = 15000;

SET @busy := 800;        -- BUSY 라이더(=위치 전송) 수 = 추적 고객 수. 이번 시나리오는 안 씀(k6 BUSY_COUNT=0).
SET @available := 700;   -- AVAILABLE 라이더(=콜 목록 폴링) 수
SET @waiting := 500;     -- 콜 목록이 찾을 WAITING 주문 수(=그 주문의 고객 수)
SET @order_customers := 300;          -- 주문 생성 고객(lt_oc) 수. 0 이면 생성하지 않는다.
SET @completed_history_extra := 10000; -- 위 waiting_ratio 이력과 별개로 더 얹을 고정 COMPLETED 건수.
-- WAITING(@waiting)이 전체 주문의 10%가 되도록 나머지 90%를 lt_w 고객의 과거 COMPLETED
-- 주문으로 채운다. 전체 = @waiting / 0.10, 이미 있는 DELIVERING(@busy)·WAITING(@waiting)을
-- 뺀 나머지가 채워야 할 이력 건수다.
SET @waiting_ratio := 0.10;
SET @history_count := CEIL(@waiting / @waiting_ratio) - @busy - @waiting;
SET @password_hash := '$2y$10$Lt6WtA6CYLmEjEtyFIQKTOwZN3QZqsumCPe6eSBLuiPZXfEohtbpy'; -- 'aa'
SET @fare_policy_id := (SELECT fare_policy_id FROM fare_policy WHERE policy_version = 'LOCAL-1.0');

-- 정리(한 번만, 네 접두어 전부). FK 순서: 거래내역 → 정산 → 스냅샷 → 주문 → 지갑/프로필 → 회원.
-- point_transaction 은 만료 스캐너의 자동 취소·환급(WAITING → CANCELED)이 재실행 사이에
-- 이미 만들어 놨을 수 있다 — 먼저 안 지우면 delivery_order 삭제가 FK 위반으로 실패한다.
-- member_id 로 직접 조인한다(delivery_order_id 경유 조인은 놓치는 행이 있다) — k6 가 실제로
-- 배송을 완료시키면서 라이더 쪽 SETTLEMENT 거래(delivery_order_id 는 NULL, rider_settlement_id
-- 로만 연결됨)가 생기는데, 예전 조인은 그걸 못 잡아 재시드가 FK 위반으로 깨졌다.
DELETE pt FROM point_transaction pt
    JOIN member m ON m.member_id = pt.member_id
WHERE m.login_id LIKE 'lt\_r%' OR m.login_id LIKE 'lt\_c%' OR m.login_id LIKE 'lt\_w%' OR m.login_id LIKE 'lt\_oc%';
-- rider_settlement 은 order_fare_snapshot·delivery_order 둘 다를 참조한다 — k6 가 완료(complete)
-- 까지 진행시키면서 생긴다. 스냅샷보다 먼저 지워야 한다.
DELETE rs FROM rider_settlement rs
    JOIN delivery_order o ON o.order_id = rs.order_id
    JOIN member m ON m.member_id IN (o.customer_id, o.assigned_rider_id)
WHERE m.login_id LIKE 'lt\_r%' OR m.login_id LIKE 'lt\_c%' OR m.login_id LIKE 'lt\_w%' OR m.login_id LIKE 'lt\_oc%';
-- delivery_proof 도 complete 가 만든다(완료 인증 1건, uk_delivery_proof_order) — delivery_order
-- 보다 먼저 지운다.
DELETE dp FROM delivery_proof dp
    JOIN delivery_order o ON o.order_id = dp.order_id
    JOIN member m ON m.member_id IN (o.customer_id, o.assigned_rider_id)
WHERE m.login_id LIKE 'lt\_r%' OR m.login_id LIKE 'lt\_c%' OR m.login_id LIKE 'lt\_w%' OR m.login_id LIKE 'lt\_oc%';
DELETE fs FROM order_fare_snapshot fs
    JOIN delivery_order o ON o.order_id = fs.order_id
    JOIN member m ON m.member_id = o.customer_id
WHERE m.login_id LIKE 'lt\_w%' OR m.login_id LIKE 'lt\_c%' OR m.login_id LIKE 'lt\_oc%';
DELETE o FROM delivery_order o
    JOIN member m ON m.member_id IN (o.customer_id, o.assigned_rider_id)
WHERE m.login_id LIKE 'lt\_r%' OR m.login_id LIKE 'lt\_c%' OR m.login_id LIKE 'lt\_w%' OR m.login_id LIKE 'lt\_oc%';
DELETE pw FROM point_wallet pw JOIN member m ON m.member_id = pw.member_id
WHERE m.login_id LIKE 'lt\_w%' OR m.login_id LIKE 'lt\_r%' OR m.login_id LIKE 'lt\_oc%';
DELETE rp FROM rider_profile rp JOIN member m ON m.member_id = rp.member_id
WHERE m.login_id LIKE 'lt\_r%' OR m.login_id LIKE 'lt\_a%';
DELETE FROM member
WHERE login_id LIKE 'lt\_r%' OR login_id LIKE 'lt\_c%' OR login_id LIKE 'lt\_a%' OR login_id LIKE 'lt\_w%'
   OR login_id LIKE 'lt\_oc%';

-- ── BUSY 라이더 + 추적 고객 + ASSIGNED 배송 ──
-- 예전엔 여기서 DELIVERING까지 만들어 뒀지만, k6 가 START_MOVING_TO_PICKUP→PICK_UP→
-- START_DELIVERING→complete 순서로 직접 진행시키게 바뀌면서 시작점을 ASSIGNED로 당겼다
-- (RiderDeliveryAction 은 목표 상태가 아니라 "현재 상태에서 가능한 행위"만 받는다 — 이미
-- DELIVERING이면 더 갈 곳이 없다).
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

-- 배송 완료(complete) 시 정산 적립 대상 지갑이 필요하다(없으면 "정산할 라이더의 포인트
-- 지갑이 없습니다"로 완료 자체가 실패한다 — k6 가 실제로 완료까지 진행시키며 발견).
INSERT INTO point_wallet (member_id, balance)
SELECT member_id, 0 FROM member WHERE login_id LIKE 'lt\_r%';

INSERT INTO delivery_order (
    customer_id, assigned_rider_id, status, request_key, item_type,
    straight_distance_meters,
    pickup_road_address, pickup_postal_code, pickup_latitude, pickup_longitude,
    destination_road_address, destination_postal_code, destination_latitude, destination_longitude,
    sender_name, sender_phone_number, recipient_name, recipient_phone_number,
    requested_at, assigned_at
)
SELECT c.member_id,
       r.member_id,
       'ASSIGNED',
       CONCAT('40000000-0000-0000-0000-', LPAD(r.i, 12, '0')),
       'DOCUMENT',
       1000 + r.i,
       CONCAT('서울특별시 부하구 테스트로 ', r.i), '04524',
       37.4500 + (r.i % 50) * 0.002, 126.8500 + (r.i % 50) * 0.004,
       CONCAT('서울특별시 부하구 도착로 ', r.i), '06232',
       37.4600 + (r.i % 50) * 0.002, 126.9500 + (r.i % 50) * 0.004,
       CONCAT('발송인 ', r.i), CONCAT('0106', LPAD(r.i, 7, '0')),
       CONCAT('수령인 ', r.i), CONCAT('0107', LPAD(r.i, 7, '0')),
       NOW(3) - INTERVAL 5 MINUTE, NOW(3) - INTERVAL 4 MINUTE
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

-- ── 주문 생성 고객(진행 중 주문 없음 — k6가 시작 시 견적→생성을 계정당 1회 호출) ──
-- WHERE i <= @order_customers 로 재귀 CTE의 기저행(i=1)까지 걸러야 @order_customers=0일 때
-- 정말로 0건이 만들어진다(기저 SELECT 1 은 재귀 조건과 무관하게 항상 나온다).
INSERT INTO member (login_id, password_hash, name, phone_number, role, status)
WITH RECURSIVE seq(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < @order_customers)
SELECT CONCAT('lt_oc', i), @password_hash, CONCAT('주문생성 고객 ', i),
       CONCAT('0111', LPAD(i, 7, '0')), 'CUSTOMER', 'ACTIVE'
FROM seq WHERE i <= @order_customers;

-- 요금 상한(20건 근처 최대 운임 2만원대)보다 넉넉히 잡는다 — /quote 대조 실패(estimatedFare
-- 불일치)가 아니라 402(포인트 부족)로 실패하는 걸 막기 위함.
INSERT INTO point_wallet (member_id, balance)
SELECT member_id, 1000000 FROM member WHERE login_id LIKE 'lt\_oc%';

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

-- lt_w 고객당 과거 COMPLETED 이력(WAITING 10% 비율을 맞추는 채움용, 위 주석 참고).
-- lt_w 고객·lt_r 라이더를 순환 배정한다 — 라이더는 이미 다른 주문에서 BUSY 지만, 과거 완료
-- 이력은 동시성 제약(라이더당 진행 중 배송 1건)의 대상이 아니라 FK 만 유효하면 된다.
INSERT INTO delivery_order (
    customer_id, assigned_rider_id, status, request_key, item_type,
    straight_distance_meters,
    pickup_road_address, pickup_postal_code, pickup_latitude, pickup_longitude,
    destination_road_address, destination_postal_code, destination_latitude, destination_longitude,
    sender_name, sender_phone_number, recipient_name, recipient_phone_number,
    requested_at, assigned_at, moving_to_pickup_at, picked_up_at, delivering_at, completed_at
)
SELECT cust.member_id,
       rider.member_id,
       'COMPLETED',
       CONCAT('52000000-0000-0000-0000-', LPAD(seq.i, 12, '0')),
       ELT(1 + (seq.i % 5), 'DOCUMENT', 'SMALL_PARCEL', 'MEDIUM_PARCEL', 'LARGE_PARCEL', 'FOOD'),
       1000 + (seq.i * 137 % 19000),
       CONCAT('서울특별시 콜구 대기로 ', seq.i), '04524',
       37.430 + (seq.i * 37 % 270) * 0.001,
       126.800 + (seq.i * 53 % 380) * 0.001,
       CONCAT('서울특별시 콜구 도착로 ', seq.i), '06232',
       37.440 + (seq.i * 41 % 260) * 0.001,
       126.810 + (seq.i * 59 % 370) * 0.001,
       CONCAT('발송인 ', seq.i), CONCAT('0104', LPAD(seq.i % 10000000, 7, '0')),
       CONCAT('수령인 ', seq.i), CONCAT('0105', LPAD(seq.i % 10000000, 7, '0')),
       NOW(3) - INTERVAL (7 * 24 * 60 + seq.i) MINUTE,
       NOW(3) - INTERVAL (7 * 24 * 60 + seq.i - 5) MINUTE,
       NOW(3) - INTERVAL (7 * 24 * 60 + seq.i - 10) MINUTE,
       NOW(3) - INTERVAL (7 * 24 * 60 + seq.i - 15) MINUTE,
       NOW(3) - INTERVAL (7 * 24 * 60 + seq.i - 20) MINUTE,
       NOW(3) - INTERVAL (7 * 24 * 60 + seq.i - 25) MINUTE
FROM (WITH RECURSIVE seq(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < @history_count)
      SELECT i FROM seq) seq
         JOIN (SELECT CAST(SUBSTRING(login_id, 5) AS UNSIGNED) AS i, member_id
               FROM member WHERE login_id LIKE 'lt\_w%') cust
              ON cust.i = ((seq.i - 1) % @waiting) + 1
         JOIN (SELECT CAST(SUBSTRING(login_id, 5) AS UNSIGNED) AS i, member_id
               FROM member WHERE login_id LIKE 'lt\_r%') rider
              ON rider.i = ((seq.i - 1) % @busy) + 1;

-- 위 waiting_ratio 이력과 별개로 고정 건수를 더 얹는다(@completed_history_extra, 기본 0).
-- request_key 접두어를 '54...'로 달리해 위 '52...' 이력과 절대 겹치지 않게 한다. lt_w/lt_r를
-- 그대로 재사용한다 — 이 행들은 순수 테이블 크기 재현용이라 어느 고객·라이더 소유든 무방하다.
INSERT INTO delivery_order (
    customer_id, assigned_rider_id, status, request_key, item_type,
    straight_distance_meters,
    pickup_road_address, pickup_postal_code, pickup_latitude, pickup_longitude,
    destination_road_address, destination_postal_code, destination_latitude, destination_longitude,
    sender_name, sender_phone_number, recipient_name, recipient_phone_number,
    requested_at, assigned_at, moving_to_pickup_at, picked_up_at, delivering_at, completed_at
)
SELECT cust.member_id,
       rider.member_id,
       'COMPLETED',
       CONCAT('54000000-0000-0000-0000-', LPAD(seq.i, 12, '0')),
       ELT(1 + (seq.i % 5), 'DOCUMENT', 'SMALL_PARCEL', 'MEDIUM_PARCEL', 'LARGE_PARCEL', 'FOOD'),
       1000 + (seq.i * 137 % 19000),
       CONCAT('서울특별시 이력구 대기로 ', seq.i), '04524',
       37.430 + (seq.i * 37 % 270) * 0.001,
       126.800 + (seq.i * 53 % 380) * 0.001,
       CONCAT('서울특별시 이력구 도착로 ', seq.i), '06232',
       37.440 + (seq.i * 41 % 260) * 0.001,
       126.810 + (seq.i * 59 % 370) * 0.001,
       CONCAT('발송인 ', seq.i), CONCAT('0104', LPAD(seq.i % 10000000, 7, '0')),
       CONCAT('수령인 ', seq.i), CONCAT('0105', LPAD(seq.i % 10000000, 7, '0')),
       NOW(3) - INTERVAL (30 * 24 * 60 + seq.i) MINUTE,
       NOW(3) - INTERVAL (30 * 24 * 60 + seq.i - 5) MINUTE,
       NOW(3) - INTERVAL (30 * 24 * 60 + seq.i - 10) MINUTE,
       NOW(3) - INTERVAL (30 * 24 * 60 + seq.i - 15) MINUTE,
       NOW(3) - INTERVAL (30 * 24 * 60 + seq.i - 20) MINUTE,
       NOW(3) - INTERVAL (30 * 24 * 60 + seq.i - 25) MINUTE
FROM (WITH RECURSIVE seq(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < @completed_history_extra)
      SELECT i FROM seq WHERE i <= @completed_history_extra) seq
         JOIN (SELECT CAST(SUBSTRING(login_id, 5) AS UNSIGNED) AS i, member_id
               FROM member WHERE login_id LIKE 'lt\_w%') cust
              ON cust.i = ((seq.i - 1) % @waiting) + 1
         JOIN (SELECT CAST(SUBSTRING(login_id, 5) AS UNSIGNED) AS i, member_id
               FROM member WHERE login_id LIKE 'lt\_r%') rider
              ON rider.i = ((seq.i - 1) % @busy) + 1;

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
WHERE m.login_id LIKE 'lt\_w%' OR m.login_id LIKE 'lt\_c%';
-- lt_c(=BUSY 쌍의 고객, customer_id 로 조인되므로 여기서 같이 잡힌다)도 포함해야 한다 —
-- 빠뜨리면 /transition, /complete, /current 처럼 운임 스냅샷을 조회하는 API가 전부
-- "배차된 주문의 예상 운임 스냅샷이 없습니다"(IllegalStateException→400)로 죽는다.
-- 이 스크립트가 처음 만들어졌을 땐 lt_r/lt_c 가 DELIVERING 고정이라 /location 만 썼고
-- 이 경로를 아무도 안 건드려 안 드러났었다(k6 가 상태 전이를 직접 하게 되며 발견).

-- 컬럼 별칭은 ASCII 로 둔다 — mysql 클라이언트 기본 문자셋에서 한글 식별자가 깨진다.
SELECT
    (SELECT COUNT(*) FROM member m JOIN rider_profile rp ON rp.member_id = m.member_id
     WHERE m.login_id LIKE 'lt\_r%' AND rp.operating_status = 'BUSY') AS busy_riders,
    (SELECT COUNT(*) FROM delivery_order o JOIN member m ON m.member_id = o.assigned_rider_id
     WHERE m.login_id LIKE 'lt\_r%' AND o.status = 'ASSIGNED') AS assigned_orders,
    (SELECT COUNT(*) FROM member m JOIN rider_profile rp ON rp.member_id = m.member_id
     WHERE m.login_id LIKE 'lt\_a%' AND rp.operating_status = 'AVAILABLE') AS available_riders,
    (SELECT COUNT(*) FROM delivery_order o JOIN member m ON m.member_id = o.customer_id
     WHERE m.login_id LIKE 'lt\_w%' AND o.status = 'WAITING') AS waiting_orders,
    (SELECT COUNT(*) FROM member WHERE login_id LIKE 'lt\_oc%') AS order_customers,
    (SELECT COUNT(*) FROM delivery_order o JOIN member m ON m.member_id = o.customer_id
     WHERE m.login_id LIKE 'lt\_w%' AND o.status = 'COMPLETED') AS completed_history_orders,
    -- customer_id·assigned_rider_id 를 IN(...) 으로 한 번에 조인하면 둘 다 lt_ 패턴에 걸리는
    -- 행(예: lt_c 고객 + lt_r 라이더의 DELIVERING 주문)이 두 번 잡힌다 — EXISTS 로 갈라야
    -- 주문 1건이 정확히 1번만 세어진다.
    (SELECT COUNT(*) FROM delivery_order o
     WHERE EXISTS (SELECT 1 FROM member m WHERE m.member_id = o.customer_id
                   AND (m.login_id LIKE 'lt\_r%' OR m.login_id LIKE 'lt\_c%' OR m.login_id LIKE 'lt\_w%'))
        OR EXISTS (SELECT 1 FROM member m WHERE m.member_id = o.assigned_rider_id
                   AND (m.login_id LIKE 'lt\_r%' OR m.login_id LIKE 'lt\_c%' OR m.login_id LIKE 'lt\_w%')))
        AS total_orders_this_dataset,
    (SELECT ROUND(100 *
        (SELECT COUNT(*) FROM delivery_order o JOIN member m ON m.member_id = o.customer_id
         WHERE m.login_id LIKE 'lt\_w%' AND o.status = 'WAITING') /
        (SELECT COUNT(*) FROM delivery_order o
         WHERE EXISTS (SELECT 1 FROM member m WHERE m.member_id = o.customer_id
                       AND (m.login_id LIKE 'lt\_r%' OR m.login_id LIKE 'lt\_c%' OR m.login_id LIKE 'lt\_w%'))
            OR EXISTS (SELECT 1 FROM member m WHERE m.member_id = o.assigned_rider_id
                       AND (m.login_id LIKE 'lt\_r%' OR m.login_id LIKE 'lt\_c%' OR m.login_id LIKE 'lt\_w%'))), 1))
        AS waiting_ratio_pct;
