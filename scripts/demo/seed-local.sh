#!/usr/bin/env bash
# 로컬 docker-compose MySQL에 콜 목록 시연용 WAITING 주문을 시딩한다.
# SSH 필요 없음 — 로컬 컨테이너에 직접 붙는다(backend/docker-compose.yml, turkey-mysql-local).
#
# 사용법 (저장소 루트에서):
#   ./scripts/demo/seed-local.sh [클러스터당 주문 수, 기본 16] [run-id, 기본 현재 시각]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COUNT="${1:-16}"
RUN_ID="${2:-$(date +%s)}"

echo "[seed-local] run_id=${RUN_ID}, 클러스터당 ${COUNT}건 → turkey-mysql-local" >&2

python3 "${SCRIPT_DIR}/generate_call_list_seed.py" --run-id "${RUN_ID}" --count-per-cluster "${COUNT}" \
  | docker exec -i turkey-mysql-local mysql -uturkey -plocal turkey

echo "[seed-local] 완료. run_id=${RUN_ID}" >&2
echo "[seed-local] 정리하려면: ./scripts/demo/cleanup-local.sh ${RUN_ID}" >&2
