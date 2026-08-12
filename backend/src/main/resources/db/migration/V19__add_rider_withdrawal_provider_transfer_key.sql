-- rider_withdrawal 에 이체 벤더 식별자 컬럼을 추가한다(#90).
--
-- 왜 필요한가:
--   point_charge 는 PG 승인 식별자(provider_payment_key)를 확정 트랜잭션보다 먼저 커밋해서
--   "PG 승인은 받았는데 DB 반영이 실패한" 건을 provider_payment_key IS NOT NULL 조건으로 찾을 수
--   있게 해 둔다(PointChargeApprover.recordApprovalReceived, #33). 출금 모의 처리(#90)도 같은
--   구조(PayoutGateway 호출 → 확정)를 쓰지만, 이 컬럼이 없어 처음에는 그 선커밋 단계를 만들지
--   못하고 로그로만 남겼다 — 이 마이그레이션이 그 자리를 채운다.
--
-- 유니크로 두는 이유: point_charge.provider_payment_key 와 같다. 같은 이체가 두 번 기록되는 것을
--   DB 수준에서 막는다(#33 uk_point_charge_provider_payment 와 동일한 판단).
--
-- NULL 허용: 아직 이체를 시도하지 않은 PENDING 행은 값이 없다.

ALTER TABLE rider_withdrawal
    ADD COLUMN provider_transfer_key VARCHAR(100) NULL AFTER account_holder_name_snapshot,
    ADD CONSTRAINT uk_rider_withdrawal_provider_transfer UNIQUE (provider_transfer_key);
