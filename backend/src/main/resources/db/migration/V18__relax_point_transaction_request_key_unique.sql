-- point_transaction.request_key 의 유니크 범위를 (request_key) 에서 (request_key, transaction_type)
-- 으로 완화한다.
--
-- 왜 필요한가(#37/#40 에서 드러남):
--   V17 의 두 제약이 서로 다른 말을 하고 있었다.
--     uk_point_transaction_order_type (delivery_order_id, transaction_type)
--       → 한 주문에 ORDER_USE 1건 + ORDER_REFUND 1건을 허용한다.
--     uk_point_transaction_request (request_key)
--       → 그 두 행이 같은 request_key 를 갖는 것을 막는다.
--   ORDER_USE 는 주문의 요청 멱등키를 그대로 쓰는데(CHAR(36) 고정폭이라 접미사를 붙일 수 없다),
--   같은 주문의 취소 환불(CUS-PAY-003)이 원장을 남기려면 무관한 새 36자 키를 지어내야 했다.
--   그러면 그 키는 아무것도 식별하지 못하는 값이 되어 멱등성 근거로 쓸 수 없다.
--
-- 완화 후에도 멱등성은 그대로다:
--   재전송 멱등성은 "같은 요청이 같은 유형의 원장을 두 번 만들지 않는 것"이므로 유형까지 포함한
--   조합이면 충분하다. 서로 다른 유형(ORDER_USE / ORDER_REFUND)이 같은 키를 공유하는 것은
--   오히려 "이 결제와 이 환불이 같은 주문 요청에서 나왔다"는 추적 근거가 된다.
--
-- 범위:
--   기존 행은 request_key 가 이미 전역 유니크였으므로 더 넓은 조합에서도 당연히 유니크다.
--   따라서 기존 데이터로 이 마이그레이션이 실패할 수 없다.
--
-- 롤링 배포 호환성:
--   제약을 좁히는 것이 아니라 넓히는 방향이라, 구 버전 코드가 이 스키마에서 실패하지 않는다
--   (구 버전은 여전히 유형당 한 건만 만든다). 반대 방향이었다면 배포 순서를 고민해야 했다.

ALTER TABLE point_transaction
    DROP INDEX uk_point_transaction_request,
    ADD CONSTRAINT uk_point_transaction_request UNIQUE (request_key, transaction_type);
