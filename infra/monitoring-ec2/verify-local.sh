#!/usr/bin/env bash
# EC2에 올리기 전 로컬 검증. 스택을 띄우고 "실제로 지표·로그가 흐르는지"까지 확인한다.
#
#   ./verify-local.sh          # 띄우고 검사(끝나도 계속 떠 있음 — Grafana 를 눈으로 보라고)
#   ./verify-local.sh --down   # 정리만
#
# 배포와 다른 것은 대상 주소·접속정보뿐이다(docker-compose.local.yml 주석 참고).
set -uo pipefail
cd "$(dirname "$0")"

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.local.yml)

# Caddy 자체서명 인증서의 주체. 배포는 .env 에서 실제 주소를 받고(없으면 기동을 거부한다),
# 로컬에서는 localhost 로 고정한다.
export SITE_ADDRESS=https://localhost


if [[ "${1:-}" == "--down" ]]; then
  "${COMPOSE[@]}" down -v
  exit 0
fi

# shellcheck disable=SC1091
[[ -f .env ]] && { set -a; . ./.env; set +a; }
PW="${GRAFANA_ADMIN_PASSWORD:-local}"

fails=0
ok()   { printf '  \033[32mOK\033[0m   %s\n' "$1"; }
warn() { printf '  \033[33mWARN\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; fails=$((fails+1)); }

# $1=설명 $2=URL $3=성공 판정 python 표현식(변수 j 에 JSON)
check_json() {
  local body; body=$(curl -sk --max-time 10 "$2" 2>/dev/null)
  if [[ -z "$body" ]]; then bad "$1 (응답 없음)"; return; fi
  local msg
  msg=$(printf '%s' "$body" | python3 -c "
import json,sys
try: j=json.load(sys.stdin)
except Exception as e: print('JSON 파싱 실패: %s' % e); sys.exit(0)
print($3)
" 2>/dev/null)
  if [[ "$msg" == "OK" ]]; then ok "$1"; else bad "$1 — ${msg:-판정 실패}"; fi
}

wait_for() {  # $1=설명 $2=URL $3=타임아웃초
  printf '  ... %s 대기' "$1"
  for _ in $(seq 1 "$3"); do
    if curl -sfk --max-time 3 "$2" >/dev/null 2>&1; then printf ' 준비됨\n'; return 0; fi
    printf '.'; sleep 1
  done
  printf ' 시간초과\n'; return 1
}

echo "== 스택 기동 =="
"${COMPOSE[@]}" up -d || exit 1

echo
echo "== 준비 대기 =="
wait_for "Prometheus" http://localhost:9099/-/ready 90 || bad "Prometheus 기동 실패"
wait_for "Loki"       http://localhost:3100/ready    90 || bad "Loki 기동 실패"
wait_for "Grafana(Caddy 경유 HTTPS)" https://localhost:8443/api/health 90 || bad "Caddy/Grafana 기동 실패"
# 첫 스크레이프(15초 주기)가 돌 시간을 준다.
sleep 20

echo
echo "== Prometheus =="
check_json "알림 규칙 8개 로드" \
  'http://localhost:9099/api/v1/rules' \
  "'OK' if sum(len(g['rules']) for g in j['data']['groups'])==8 else '규칙 %d개' % sum(len(g['rules']) for g in j['data']['groups'])"

check_json "대상 스크레이프 성공(spring-app 제외 전부 up)" \
  'http://localhost:9099/api/v1/targets?state=active' \
  "'OK' if not [t['scrapePool'] for t in j['data']['activeTargets'] if t['health']!='up' and t['scrapePool']!='spring-app'] else 'down: %s' % [t['scrapePool']+' '+t['lastError'][:60] for t in j['data']['activeTargets'] if t['health']!='up' and t['scrapePool']!='spring-app']"

# 대상이 up 이어도 지표 이름이 다르면 대시보드·규칙은 빈다. 각 exporter 의 대표 지표를 직접 본다.
for q in node_cpu_seconds_total:node-exporter redis_up:redis_exporter mysql_up:mysqld-exporter; do
  metric="${q%%:*}"; who="${q##*:}"
  check_json "$who 지표 수집($metric)" \
    "http://localhost:9099/api/v1/query?query=count($metric)" \
    "'OK' if j['data']['result'] else '시리즈 없음'"
done

# 앱은 bootRun 을 켠 사람만 검증된다. Actuator 경로(/actuator/prometheus)를 확인하는 유일한 검사다.
# 중괄호·따옴표는 URL 에 그대로 실으면 Prometheus 가 parse error 로 거절한다 — 반드시 인코딩.
app_state=$(curl -s --max-time 5 --get --data-urlencode 'query=up{job="spring-app"}' \
  http://localhost:9099/api/v1/query \
  | python3 -c "import json,sys; r=json.load(sys.stdin)['data']['result']; print(r[0]['value'][1] if r else 'none')" 2>/dev/null)
if [[ "$app_state" == "1" ]]; then
  check_json "spring-app Actuator 지표 수집(jvm_memory_used_bytes)" \
    'http://localhost:9099/api/v1/query?query=count(jvm_memory_used_bytes)' \
    "'OK' if j['data']['result'] else 'up 이지만 지표 없음 — metrics_path 확인'"
else
  warn "spring-app 미검증 — 앱을 안 띄웠다. 검증하려면 ./gradlew bootRun 후 재실행"
fi

echo
echo "== 알림 규칙이 참조하는 지표 실재 확인 =="
# 규칙이 없는 지표를 참조하면 syntax 는 통과하고 영원히 울리지 않는다. 이름이 바뀌거나
# exporter 버전이 달라지면 여기서 잡힌다.
for m in node_filesystem_avail_bytes node_filesystem_size_bytes node_cpu_seconds_total \
         jvm_memory_used_bytes jvm_memory_max_bytes hikaricp_connections_pending \
         redis_memory_used_bytes redis_memory_max_bytes \
         mysql_global_status_threads_connected mysql_global_variables_max_connections
do
  n=$(curl -s --max-time 5 --get --data-urlencode "query=count($m)" http://localhost:9099/api/v1/query \
    | python3 -c "import json,sys; r=json.load(sys.stdin)['data']['result']; print(r[0]['value'][1] if r else '0')" 2>/dev/null)
  if [[ "${n:-0}" != "0" ]]; then ok "$m (${n}개 시리즈)"
  elif [[ "$m" == jvm_* || "$m" == hikaricp_* ]]; then warn "$m 없음 — 앱(bootRun)을 안 띄웠기 때문"
  else bad "$m 없음 — 규칙이 이 지표를 참조하는데 수집되지 않는다"; fi
done
# Micrometer 는 첫 요청이 들어온 뒤에야 이 지표를 만든다. 갓 띄운 앱에서 없는 것은 정상이다.
n=$(curl -s --max-time 5 --get --data-urlencode 'query=count(http_server_requests_seconds_count)' \
  http://localhost:9099/api/v1/query \
  | python3 -c "import json,sys; r=json.load(sys.stdin)['data']['result']; print(r[0]['value'][1] if r else '0')" 2>/dev/null)
[[ "${n:-0}" != "0" ]] && ok "http_server_requests_seconds_count (${n}개 시리즈)" \
  || warn "http_server_requests_seconds_count 없음 — 앱에 요청이 한 번도 안 들어왔다(Micrometer 지연 등록)"

echo
echo "== Loki(적재 → 조회 왕복) =="
NS=$(python3 -c 'import time; print(int(time.time()*1e9))')
push=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:3100/loki/api/v1/push \
  -H 'Content-Type: application/json' \
  --data-binary "{\"streams\":[{\"stream\":{\"job\":\"verify-local\"},\"values\":[[\"$NS\",\"event=VERIFY_LOCAL ok=true\"]]}]}" 2>/dev/null)
[[ "$push" == "204" ]] && ok "로그 적재(HTTP 204)" || bad "로그 적재 실패(HTTP $push)"
sleep 2
START=$((NS - 60000000000))
check_json "적재한 로그 조회" \
  "http://localhost:3100/loki/api/v1/query_range?query=%7Bjob%3D%22verify-local%22%7D&start=$START&end=$((NS + 60000000000))" \
  "'OK' if j['data']['result'] else '조회 결과 없음'"

echo
echo "== Grafana(HTTPS → 데이터소스 프록시) =="
check_json "자체서명 HTTPS 로 Grafana 응답" \
  https://localhost:8443/api/health \
  "'OK' if j.get('database')=='ok' else str(j)"

check_json "Grafana → Prometheus 질의" \
  "https://admin:$PW@localhost:8443/api/datasources/proxy/uid/prometheus/api/v1/query?query=up" \
  "'OK' if j.get('status')=='success' and j['data']['result'] else str(j)[:80]"

check_json "Grafana → Loki 질의" \
  "https://admin:$PW@localhost:8443/api/datasources/proxy/uid/loki/loki/api/v1/labels" \
  "'OK' if j.get('status')=='success' and 'job' in j.get('data',[]) else str(j)[:80]"

dash=$(curl -sk --max-time 10 "https://admin:$PW@localhost:8443/api/search?type=dash-db" \
  | python3 -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null)
if [[ "${dash:-0}" -ge 1 ]]; then ok "대시보드 ${dash}개 프로비저닝"
else warn "대시보드 0개 — ./fetch-dashboards.sh 를 먼저 실행할 것"; fi

echo
if [[ $fails -eq 0 ]]; then
  echo "전부 통과. Grafana: https://localhost:8443 (admin / $PW, 자체서명 경고는 통과시킬 것)"
  echo "정리: ./verify-local.sh --down"
else
  echo "실패 ${fails}건. 로그: ${COMPOSE[*]} logs --tail=50 <서비스>"
fi
exit $fails
