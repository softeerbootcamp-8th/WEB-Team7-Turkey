-- 부하 테스트용 "주문 생성" 고객을 N명 만든다. reset-and-seed-local.sql 을 먼저 실행한 뒤에 돌린다
-- (그쪽이 전체 TRUNCATE 로 시작하고 fare_policy·item_type_surcharge 를 심는다. 순서를 바꾸면
--  이 데이터가 지워지고, fare_policy 가 없으면 주문 생성이 견적 계산에서 실패한다).
--
--   cd backend
--   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/reset-and-seed-local.sql
--   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/seed-loadtest-order-create.sql
--
-- 왜 필요한가: POST /api/customer/deliveries 는 "상태 소진형"이다. 고객은 진행 중 주문을 1건만
-- 가질 수 있어(uk_delivery_active_customer) 두 번째 생성부터 전부 409 다. 기본 시드에서 진행 중
-- 주문이 없는 고객은 c5·c6 둘뿐이라 VU 를 늘릴 수 없다. 부하 시나리오(order-create-cancel.js)는
-- "생성→취소"를 반복해 슬롯을 비우며 도는데, 그러려면 VU 당 고객 1명이 필요하다. 여기서 그 고객을
-- N명 만들고, 생성 트랜잭션의 포인트 차감(order→payment MANDATORY)이 402 로 죽지 않게 지갑을
-- 넉넉히 충전한다(생성이 차감하고 취소가 환급하므로 사이클당 순변화는 0 이지만 버퍼를 크게 둔다).
--
-- 계정: lt_o1..lt_oN (고객, 진행 중 주문 없음). 비밀번호는 기본 시드와 같은 'aa'.
--
-- ⚠ 접두어 주의: seed-loadtest-riders.sql 은 DELETE ... LIKE 'lt\_%' 로 lt_ 전체를 지운다.
-- 그 스크립트를 나중에 돌리면 이 lt_o* 도 함께 사라진다(SKILL.md 「시드 충돌」). 이 파일은
-- 반대로 자기 접두어 lt_o% 만 지워 다른 데이터셋(lt_r/lt_c/lt_w/lt_a)을 건드리지 않는다.
--
-- 주의: 재귀 CTE 기본 상한(cte_max_recursion_depth)이 1000 이다. 그보다 많이 만들려면
-- 이 세션에서 SET SESSION cte_max_recursion_depth = <값> 을 먼저 실행해야 한다.
USE turkey;

-- 기본 200 — 위치 갱신 시드(@n=100, 2n=200 계정)와 같은 VU 규모에 맞춘 값이다. 여기서는 역할이
-- 하나(고객)뿐이라 계정 수가 곧 @n 이다. 더 큰 VU 가 필요하면 이 값을 먼저 올린다.
SET @n := 200;
-- 기본 시드와 같은 'aa' 의 bcrypt 해시.
SET @password_hash := '$2y$10$Lt6WtA6CYLmEjEtyFIQKTOwZN3QZqsumCPe6eSBLuiPZXfEohtbpy';

-- 재실행 가능하게 이전 lt_o 데이터를 먼저 지운다. 자기 접두어만 지운다 — 다른 부하 데이터셋
-- (lt_r/lt_c/lt_w/lt_a)은 건드리지 않는다.
--
-- FK 순서가 중요하다: 첫 시드 때는 주문이 없어 delivery_order→wallet→member 만으로 통과했지만,
-- 부하 런을 한 번이라도 돌리면 주문 수만 건 + 각 주문의 자식(요금 스냅샷·상태 이력·포인트 거래)이
-- 쌓여 delivery_order 를 바로 못 지운다. 자식부터 지운다:
--   delivery_order 자식: order_fare_snapshot, order_status_history, delivery_proof,
--                        rider_location_history, rider_settlement, point_transaction(delivery_order_id)
--   point_wallet 자식: point_transaction(member_id)  ← 지갑보다 먼저 지워야 함
DELETE c FROM order_fare_snapshot c JOIN delivery_order o ON o.order_id = c.order_id
    JOIN member m ON m.member_id = o.customer_id WHERE m.login_id LIKE 'lt\_o%';
DELETE c FROM order_status_history c JOIN delivery_order o ON o.order_id = c.order_id
    JOIN member m ON m.member_id = o.customer_id WHERE m.login_id LIKE 'lt\_o%';
DELETE c FROM delivery_proof c JOIN delivery_order o ON o.order_id = c.order_id
    JOIN member m ON m.member_id = o.customer_id WHERE m.login_id LIKE 'lt\_o%';
DELETE c FROM rider_location_history c JOIN delivery_order o ON o.order_id = c.order_id
    JOIN member m ON m.member_id = o.customer_id WHERE m.login_id LIKE 'lt\_o%';
DELETE c FROM rider_settlement c JOIN delivery_order o ON o.order_id = c.order_id
    JOIN member m ON m.member_id = o.customer_id WHERE m.login_id LIKE 'lt\_o%';
-- 포인트 거래는 delivery_order_id·point_charge_id·member_id 를 모두 참조하므로 회원 기준으로
-- 한 번에 지운다(주문 삭제와 지갑 삭제 양쪽을 다 풀어 준다).
DELETE t FROM point_transaction t JOIN member m ON m.member_id = t.member_id
WHERE m.login_id LIKE 'lt\_o%';
DELETE o FROM delivery_order o
    JOIN member m ON m.member_id = o.customer_id
WHERE m.login_id LIKE 'lt\_o%';
DELETE w FROM point_wallet w JOIN member m ON m.member_id = w.member_id
WHERE m.login_id LIKE 'lt\_o%';
DELETE FROM member WHERE login_id LIKE 'lt\_o%';

-- 고객 N명. 진행 중 주문은 만들지 않는다 — 부하 시나리오가 매 iteration 에서 스스로 생성한다.
INSERT INTO member (login_id, password_hash, name, phone_number, role, status)
WITH RECURSIVE seq(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < @n)
SELECT CONCAT('lt_o', i), @password_hash, CONCAT('부하 주문고객 ', i),
       CONCAT('0105', LPAD(i, 7, '0')), 'CUSTOMER', 'ACTIVE'
FROM seq;

-- 지갑을 넉넉히 충전한다. 주문 생성은 요금(수천~수만 원)을 차감하고 취소가 환급하므로 사이클당
-- 순변화는 0 이지만, 차감과 환급 사이의 창·재시도 여지를 감안해 1억 P 를 넣는다.
INSERT INTO point_wallet (member_id, balance)
SELECT member_id, 100000000 FROM member WHERE login_id LIKE 'lt\_o%';

-- 컬럼 별칭은 ASCII 로 둔다 — mysql 클라이언트 기본 문자셋에서 한글 식별자가 깨진다.
SELECT COUNT(*) AS order_customers,
       MIN(balance) AS min_balance,
       (SELECT COUNT(*) FROM delivery_order o JOIN member m ON m.member_id = o.customer_id
        WHERE m.login_id LIKE 'lt\_o%') AS existing_orders
FROM member m JOIN point_wallet w ON w.member_id = m.member_id
WHERE m.login_id LIKE 'lt\_o%';
