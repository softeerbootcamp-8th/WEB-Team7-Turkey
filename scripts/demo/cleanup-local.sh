#!/usr/bin/env bash
# 로컬에 seed-local.sh로 만든 콜 목록 시연 데이터를 지운다.
# 사용법:
#   ./scripts/demo/cleanup-local.sh <run-id>   # 그 run-id로 만든 것만
#   ./scripts/demo/cleanup-local.sh all        # demo_cust_ 로 시작하는 전부 (로컬은 버리는 데이터라 안전)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_ID="${1:?사용법: ./cleanup-local.sh <run-id 또는 all>}"

if [ "$RUN_ID" = "all" ]; then
  PATTERN='demo\_cust\_%'
else
  PATTERN="demo\\_cust\\_${RUN_ID}\\_%"
fi

sed "s/@@PATTERN@@/${PATTERN}/g" "${SCRIPT_DIR}/cleanup-call-list-seed.sql.template" \
  | docker exec -i turkey-mysql-local mysql --default-character-set=utf8mb4 -uturkey -plocal turkey

echo "[cleanup-local] 정리 완료 (대상: ${RUN_ID})" >&2
