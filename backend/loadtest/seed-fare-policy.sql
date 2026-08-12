-- #373: 로컬 개발·부하테스트 DB용 기본 요금 정책 시드.
--
-- 원래 V19 Flyway 마이그레이션으로 넣었으나, PR #460 리뷰(githings)에서 이 값이 "확정된 운영
-- 기본 정책"이 아니라 E2E 픽스처 값을 그대로 옮긴 것이라는 지적을 받아 공용 Flyway에서 뺐다.
-- 필요한 환경(로컬 DB, 부하테스트용 EC2 DB)에서 이 파일을 직접 실행한다.
--
-- 사용법(로컬):
--   docker exec -i turkey-mysql-local mysql -uturkey -plocal turkey < backend/loadtest/seed-fare-policy.sql
--
-- EC2 대상은 ec2-seed.sh가 이 파일을 자동으로 먼저 실행한다.
--
-- 조건부 INSERT: 이미 활성 정책이 있으면 아무것도 하지 않는다(uk_fare_policy_active 위반 방지).

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
    SELECT 1 FROM fare_policy WHERE status = 'ACTIVE'
);
