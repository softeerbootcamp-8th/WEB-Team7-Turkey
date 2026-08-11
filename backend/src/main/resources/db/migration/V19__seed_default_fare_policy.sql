-- 기본 요금 정책 시드(#373).
--
-- V8 이 fare_policy 테이블을 만들었지만 행을 넣는 곳이 어디에도 없어서, 새로 띄운 DB 에는
-- 활성 정책이 하나도 없다. 그 상태에서는 DeliveryService.calculate 가
-- "활성화된 요금 정책이 없습니다"로 끊어 요금 견적(/quote)과 배송요청 생성이 전부 실패한다.
-- 부하테스트 시딩(#373)이 이 벽에 먼저 막혀서, 정책 조달을 마이그레이션으로 옮겼다(사람 확인).
--
-- 값은 새로 정한 것이 아니라 E2E 테스트가 이미 공용 픽스처로 쓰던 값을 그대로 옮긴 것이다
-- (CustomerDeliveryCreateE2ETest.saveActiveFarePolicy 등): 기본 3,000원 + 100m 당 130원,
-- 최대 배송거리 30km. 실제 영업 요금이 확정되면 이 행을 INACTIVE 로 내리고 새 정책 버전을
-- 추가하는 방식으로 바꾼다(정책 교체는 행 수정이 아니라 새 행 + 상태 전환이다).
--
-- item_type_surcharge 는 일부러 비워 둔다. 물품별 할증 금액은 팀이 합의한 값이 없어서
-- 여기서 지어내지 않는다 — 없으면 DeliveryService.calculateFare 가 할증 0으로 계산한다.
--
-- 조건부 INSERT 인 이유: 이미 활성 정책이 있는 환경(운영·개발 DB 에 수동으로 넣어 둔 경우)에서
-- 이 마이그레이션이 uk_fare_policy_active UNIQUE 를 위반하면 Flyway 가 실패하고 앱이 아예 뜨지
-- 않는다. 시드는 "없을 때만 채운다"가 맞는 의미이므로 존재 여부를 조건으로 건다.

INSERT INTO fare_policy (
    policy_version,
    base_fare,
    distance_unit_meters,
    distance_unit_fare,
    max_delivery_distance_meters,
    status,
    effective_from
)
SELECT 'v1', 3000, 100, 130, 30000, 'ACTIVE', '2026-01-01 00:00:00.000'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM (SELECT fare_policy_id FROM fare_policy WHERE status = 'ACTIVE') AS existing_active
);
