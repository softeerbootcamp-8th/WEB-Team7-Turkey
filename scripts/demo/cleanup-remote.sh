#!/usr/bin/env bash
# 배포 DB에 seed-remote.sh로 만든 콜 목록 시연 데이터를 지운다.
# 사용법:
#   ./scripts/demo/cleanup-remote.sh <run-id>   # 그 run-id로 만든 것만 (팀 공용 환경이니 기본은 이걸 쓴다)
#   ./scripts/demo/cleanup-remote.sh all        # demo_cust_ 로 시작하는 전부
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_ID="${1:?사용법: ./cleanup-remote.sh <run-id 또는 all>}"
SQL_FILE=$(mktemp)
trap 'rm -f "$SQL_FILE"' EXIT

if [ "$RUN_ID" = "all" ]; then
  PATTERN='demo\_cust\_%'
else
  PATTERN="demo\\_cust\\_${RUN_ID}\\_%"
fi

sed "s/@@PATTERN@@/${PATTERN}/g" "${SCRIPT_DIR}/cleanup-call-list-seed.sql.template" > "$SQL_FILE"

DB_PW=$(ssh turkey-was "sudo grep DB_PASSWORD /etc/myapp/secrets.env | cut -d= -f2-" | sed "s/^'//; s/'$//")
DB_PW_B64=$(printf '%s' "$DB_PW" | base64 -w0)

ssh turkey-db "export MYSQL_PWD=\$(echo ${DB_PW_B64} | base64 -d); mysql --default-character-set=utf8mb4 -h 127.0.0.1 -uturkey_admin turkey" < "$SQL_FILE"
unset DB_PW DB_PW_B64

echo "[cleanup-remote] 정리 완료 (대상: ${RUN_ID})" >&2
