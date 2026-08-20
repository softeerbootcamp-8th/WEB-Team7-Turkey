#!/usr/bin/env bash
# 배포 DB에 콜 목록 시연용 WAITING 주문을 시딩한다.
#
# SSH 경유(WAS를 jump host로 삼아 DB 인스턴스 접속)를 전제로 한다 — ~/.ssh/config 에
# turkey-was/turkey-db 호스트가 설정돼 있어야 한다(backend/loadtest/remote/ec2-seed.sh와 같은
# 방식). 실행은 여기, 이 맥북 터미널에서 한다 — EC2에 직접 로그인해서 손으로 타이핑하지 않는다.
#
# **배포 DB(팀 공용 환경)를 직접 건드린다.** 실행 전에 다른 사람이 그 시간에 그 서버로
# 작업·시연 중이 아닌지 확인할 것.
#
# 사용법 (저장소 루트에서):
#   ./scripts/demo/seed-remote.sh [클러스터당 주문 수, 기본 16] [run-id, 기본 현재 시각]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COUNT="${1:-16}"
RUN_ID="${2:-$(date +%s)}"
SQL_FILE=$(mktemp)
trap 'rm -f "$SQL_FILE"' EXIT

python3 "${SCRIPT_DIR}/generate_call_list_seed.py" --run-id "${RUN_ID}" --count-per-cluster "${COUNT}" > "$SQL_FILE"

echo "[seed-remote] run_id=${RUN_ID}, 클러스터당 ${COUNT}건 → 배포 DB로 전송 중..." >&2

DB_PW=$(ssh turkey-was "sudo grep DB_PASSWORD /etc/myapp/secrets.env | cut -d= -f2-" | sed "s/^'//; s/'$//")
DB_PW_B64=$(printf '%s' "$DB_PW" | base64 -w0)

ssh turkey-db "export MYSQL_PWD=\$(echo ${DB_PW_B64} | base64 -d); mysql --default-character-set=utf8mb4 -h 127.0.0.1 -uturkey_admin turkey" < "$SQL_FILE"
unset DB_PW DB_PW_B64

echo "[seed-remote] 완료. run_id=${RUN_ID}" >&2
echo "[seed-remote] 정리하려면: ./scripts/demo/cleanup-remote.sh ${RUN_ID}" >&2
