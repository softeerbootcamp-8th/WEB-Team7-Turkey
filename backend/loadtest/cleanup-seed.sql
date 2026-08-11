-- #373: seed.js 가 만든 부하테스트 계정·데이터를 지운다.
--
-- 대상은 로그인 ID 접두어(lt_cust_ / lt_rider_)로만 고른다 — RUN_ID 는 실행마다 달라지므로
-- 특정 회차 하나만 지우고 싶으면 아래 LIKE 패턴에 RUN_ID를 채워 넣는다. 기본값(전체 %)은
-- "지금까지의 모든 부하테스트 회차"를 한 번에 정리한다.
--
-- 삭제 순서는 FK 를 거스르지 않는 방향(자식 → 부모)이다. delivery_order/point_wallet/
-- rider_profile 이 전부 member 를 참조하므로 member 를 가장 나중에 지운다.
--
-- 사용법:
--   docker exec -i turkey-mysql-local mysql -uturkey -plocal turkey < backend/loadtest/cleanup-seed.sql
--
-- 특정 회차만 지우려면 아래 두 LIKE 패턴의 '%'를 'r1755000000000' 같은 실제 RUN_ID 로 바꾼다.

SET @login_pattern = 'lt\\_%';

DELETE pt FROM point_transaction pt
JOIN point_wallet pw ON pw.member_id = pt.member_id
JOIN member m ON m.member_id = pw.member_id
WHERE m.login_id LIKE @login_pattern;

DELETE ofs FROM order_fare_snapshot ofs
JOIN delivery_order o ON o.order_id = ofs.order_id
JOIN member m ON m.member_id = o.customer_id
WHERE m.login_id LIKE @login_pattern;

DELETE o FROM delivery_order o
JOIN member m ON m.member_id = o.customer_id
WHERE m.login_id LIKE @login_pattern;

DELETE pc FROM point_charge pc
JOIN member m ON m.member_id = pc.customer_id
WHERE m.login_id LIKE @login_pattern;

DELETE pw FROM point_wallet pw
JOIN member m ON m.member_id = pw.member_id
WHERE m.login_id LIKE @login_pattern;

DELETE rp FROM rider_profile rp
JOIN member m ON m.member_id = rp.member_id
WHERE m.login_id LIKE @login_pattern;

DELETE mta FROM member_term_agreement mta
JOIN member m ON m.member_id = mta.member_id
WHERE m.login_id LIKE @login_pattern;

DELETE FROM member WHERE login_id LIKE @login_pattern;

SELECT ROW_COUNT() AS deleted_members;
