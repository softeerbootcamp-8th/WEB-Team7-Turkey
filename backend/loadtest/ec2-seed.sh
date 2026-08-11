#!/usr/bin/env bash
# EC2 부하테스트용 시딩 — SQL 직접 INSERT (#459).
#
# 로컬용 seed.js는 회원가입 API를 거치는데, 그 API가 반환하는 인증번호(debugCode)는
# local 프로파일에서만 채워진다(PhoneVerificationController: includeDebugCode =
# environment.matchesProfiles("local")). 운영은 당연히 그 프로파일이 아니라서 seed.js를
# 그대로 못 쓴다. 대신 계정·배송만 SQL로 직접 만들고, 세션은 실제 로그인 API로 받는다
# (로그인은 debugCode가 필요 없어서 이 우회가 가능하다).
#
# SSH 경유(WAS를 jump host로 DB 인스턴스 접속)를 전제로 한다 — ~/.ssh/config 에
# turkey-was/turkey-db 호스트가 설정돼 있어야 한다.
#
# 사용법:
#   ./ec2-seed.sh <N> <RUN_ID>
#
# 출력: ec2-seed-<RUN_ID>.json (같은 폴더에 생성, git 추적 안 함 — 실행마다 새로 생기는
#   결과물이다). k6 쪽에서 이 파일을 읽어 로그인·부하를 시작한다.
#
# 정리: 다 쓴 뒤에는 backend/loadtest/cleanup-seed.sql 을 운영 DB에도 그대로 실행하면 된다
#   (login_id LIKE 'lt\_%' 조건이라 로컬용과 동일하게 동작).
#
# fare_policy: 활성 요금 정책은 더 이상 Flyway(V19)가 보장하지 않는다(#373, PR #460 리뷰 반영 —
#   운영 확정 정책이 아니라 별도 seed로 분리). 이 스크립트가 seed-fare-policy.sql을 계정 시딩보다
#   먼저 실행해 존재를 보장한다.

set -euo pipefail

N="${1:?사용법: ./ec2-seed.sh <N> <RUN_ID>}"
RUN_ID="${2:?사용법: ./ec2-seed.sh <N> <RUN_ID>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_FILE="${SCRIPT_DIR}/ec2-seed-${RUN_ID}.json"
SQL_FILE=$(mktemp)
trap 'rm -f "$SQL_FILE"' EXIT

# bcrypt("loadtest1234") — 로컬 시딩과 같은 값을 재사용한다(해시는 환경 무관, 알고리즘·평문만
# 같으면 어디서 만들었든 유효하다).
PASSWORD_HASH='$2a$10$4d5LgvscIbN0TaAuQ9Afk.0HA9JxCDlL6f02CC9k/3Vtiq4EBXMA6'

{
  echo "SET @hash = '${PASSWORD_HASH}';"
  cat "${SCRIPT_DIR}/seed-fare-policy.sql"
  echo "SELECT fare_policy_id, base_fare, distance_unit_meters, distance_unit_fare"
  echo "  INTO @fp_id, @base, @unit_m, @unit_fare FROM fare_policy WHERE status='ACTIVE' LIMIT 1;"

  for i in $(seq 1 "$N"); do
    cust="lt_cust_${RUN_ID}_${i}"
    rider="lt_rider_${RUN_ID}_${i}"
    # phone_number 는 member 테이블에 형식 CHECK 가 없다(UNIQUE 만) — MD5 로 회차·역할·순번에서
    # 결정적으로 뽑아 20자 안에 맞춘다. 형식 검증은 회원가입 API 를 거칠 때만 걸리는데, 여긴
    # SQL 직접 INSERT 라 해당 없다.
    cat <<SQL
INSERT INTO member (login_id, password_hash, name, phone_number, role, status) VALUES
  ('${cust}', @hash, 'lt cust ${RUN_ID} ${i}', CONCAT('c', SUBSTRING(MD5('${RUN_ID}-c-${i}'), 1, 15)), 'CUSTOMER', 'ACTIVE'),
  ('${rider}', @hash, 'lt rider ${RUN_ID} ${i}', CONCAT('r', SUBSTRING(MD5('${RUN_ID}-r-${i}'), 1, 15)), 'RIDER', 'ACTIVE');

INSERT INTO point_wallet (member_id, balance)
SELECT member_id, 1000000 FROM member WHERE login_id = '${cust}';

INSERT INTO rider_profile (member_id, operating_status)
SELECT member_id, 'BUSY' FROM member WHERE login_id = '${rider}';

INSERT INTO delivery_order (
  customer_id, status, request_key, item_type, straight_distance_meters,
  pickup_road_address, pickup_detail_address, pickup_postal_code, pickup_latitude, pickup_longitude,
  destination_road_address, destination_detail_address, destination_postal_code, destination_latitude, destination_longitude,
  sender_name, sender_phone_number, recipient_name, recipient_phone_number,
  assigned_rider_id, assigned_at
)
SELECT c.member_id, 'ASSIGNED', UUID(), 'DOCUMENT', 6305,
  '서울 강남구 테헤란로 152', '5층', '06236', 37.5006000, 127.0366000,
  '서울 송파구 올림픽로 300', '1동', '05551', 37.5145000, 127.1059000,
  c.name, c.phone_number, 'lt recipient ${i}', c.phone_number,
  r.member_id, UTC_TIMESTAMP()
FROM member c, member r
WHERE c.login_id = '${cust}' AND r.login_id = '${rider}';

INSERT INTO order_fare_snapshot
  (order_id, fare_policy_id, fare_type, policy_version, calculation_distance_meters, base_fare, distance_fare, item_surcharge, total_fare)
SELECT o.order_id, @fp_id, 'ESTIMATE', 'ec2-seed', 6305, @base,
  CEIL(6305 / @unit_m) * @unit_fare, 0, @base + CEIL(6305 / @unit_m) * @unit_fare
FROM delivery_order o JOIN member c ON c.member_id = o.customer_id
WHERE c.login_id = '${cust}';
SQL
  done

  cat <<SQL
SELECT o.order_id, c.login_id, c.password_hash IS NOT NULL, r.login_id
FROM delivery_order o
JOIN member c ON c.member_id = o.customer_id
JOIN member r ON r.member_id = o.assigned_rider_id
WHERE c.login_id LIKE 'lt\_cust\_${RUN_ID}\_%'
ORDER BY o.order_id;
SQL
} > "$SQL_FILE"

echo "[ec2-seed] ${N}쌍 SQL 생성 완료, turkey-db 로 실행 중..." >&2

DB_PW=$(ssh turkey-was "sudo grep DB_PASSWORD /etc/myapp/secrets.env | cut -d= -f2-" | sed "s/^'//; s/'$//")
DB_PW_B64=$(printf '%s' "$DB_PW" | base64 -w0)

RESULT=$(ssh turkey-db "export MYSQL_PWD=\$(echo ${DB_PW_B64} | base64 -d); mysql -N -h 127.0.0.1 -uturkey_admin turkey" < "$SQL_FILE")
unset DB_PW DB_PW_B64

# RESULT 는 탭 구분 (order_id, customer_login_id, has_hash, rider_login_id) 줄들 — JSON 배열로 변환.
{
  echo '{"runId":"'"${RUN_ID}"'","pairs":['
  echo "$RESULT" | awk -F'\t' '{
    printf "%s{\"deliveryId\":%s,\"customerLoginId\":\"%s\",\"riderLoginId\":\"%s\"}", (NR>1?",":""), $1, $2, $4
  }'
  echo ']}'
} > "$OUT_FILE"

PAIR_COUNT=$(echo "$RESULT" | grep -c . || true)
echo "[ec2-seed] 완료: ${PAIR_COUNT}쌍 → ${OUT_FILE}" >&2
