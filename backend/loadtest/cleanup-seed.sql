-- #373: local/seed.js · remote/ec2-seed.sh 가 만든 부하테스트 계정·데이터를 지운다.
--
-- 대상은 **이 시딩이 만든 두 접두어(lt_cust_ / lt_rider_)로만** 고른다.
-- `lt\_%` 로 넓히면 안 된다 — 같은 lt_ 접두어를 쓰는 다른 부하테스트 데이터셋
-- (scripts/seed-loadtest-call-list.sql 의 lt_w*·lt_a*, scripts/seed-loadtest-riders.sql 의
-- lt_r*·lt_c*)까지 함께 지워진다. 주문 수천 건이 든 데이터셋이 조용히 사라진다.
--
-- 삭제 순서는 FK 를 거스르지 않는 방향(자식 → 부모)이다. delivery_order/point_wallet/
-- rider_profile 이 전부 member 를 참조하므로 member 를 가장 나중에 지운다.
--
-- 사용법(전체 회차 정리):
--   docker exec -i turkey-mysql-local mysql -uturkey -plocal turkey < backend/loadtest/cleanup-seed.sql
--
-- 특정 회차만 지우려면 @run 에 RUN_ID 를 넣는다. RUN_ID 는 접두어 **뒤**, 인덱스 **앞**에
-- 오므로(`lt_cust_<RUN_ID>_<i>`) 양쪽에 와일드카드가 필요하다 — 접두어 없이
-- `lt\_<RUN_ID>` 로 쓰면 아무 행도 매칭되지 않는다.

SET @run = '%';   -- 예: 'r1755000000000'

SET @cust_pattern  = CONCAT('lt\\_cust\\_',  @run, '\\_%');
SET @rider_pattern = CONCAT('lt\\_rider\\_', @run, '\\_%');

DELETE pt FROM point_transaction pt
JOIN point_wallet pw ON pw.member_id = pt.member_id
JOIN member m ON m.member_id = pw.member_id
WHERE m.login_id LIKE @cust_pattern OR m.login_id LIKE @rider_pattern;

DELETE ofs FROM order_fare_snapshot ofs
JOIN delivery_order o ON o.order_id = ofs.order_id
JOIN member m ON m.member_id = o.customer_id
WHERE m.login_id LIKE @cust_pattern OR m.login_id LIKE @rider_pattern;

DELETE o FROM delivery_order o
JOIN member m ON m.member_id = o.customer_id
WHERE m.login_id LIKE @cust_pattern OR m.login_id LIKE @rider_pattern;

DELETE pc FROM point_charge pc
JOIN member m ON m.member_id = pc.customer_id
WHERE m.login_id LIKE @cust_pattern OR m.login_id LIKE @rider_pattern;

DELETE pw FROM point_wallet pw
JOIN member m ON m.member_id = pw.member_id
WHERE m.login_id LIKE @cust_pattern OR m.login_id LIKE @rider_pattern;

DELETE rp FROM rider_profile rp
JOIN member m ON m.member_id = rp.member_id
WHERE m.login_id LIKE @cust_pattern OR m.login_id LIKE @rider_pattern;

DELETE mta FROM member_term_agreement mta
JOIN member m ON m.member_id = mta.member_id
WHERE m.login_id LIKE @cust_pattern OR m.login_id LIKE @rider_pattern;

DELETE FROM member WHERE login_id LIKE @cust_pattern OR login_id LIKE @rider_pattern;

SELECT ROW_COUNT() AS deleted_members;
