#!/usr/bin/env bash
# 로컬에 seed-local.sh로 만든 콜 목록 시연 데이터를 지운다.
# 사용법: ./scripts/demo/cleanup-local.sh <run-id>
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_ID="${1:?사용법: ./cleanup-local.sh <run-id>}"

sed "s/@@RUNID@@/${RUN_ID}/g" "${SCRIPT_DIR}/cleanup-call-list-seed.sql.template" \
  | docker exec -i turkey-mysql-local mysql -uturkey -plocal turkey

echo "[cleanup-local] run_id=${RUN_ID} 정리 완료" >&2
