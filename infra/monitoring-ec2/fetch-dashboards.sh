#!/usr/bin/env bash
# 모니터링 EC2에서 실행. grafana.com 의 공식 대시보드를 provisioning/dashboards/ 로 받는다.
# 직접 만들지 않는 이유: exporter 마다 사실상 표준 대시보드가 이미 있다.
# JSON 은 생성물이라 커밋하지 않는다(.gitignore) — 새 박스에서는 한 번 실행해야 한다.
set -euo pipefail

cd "$(dirname "$0")/provisioning/dashboards"

# 이름:grafana.com 대시보드 ID
for spec in \
  node-exporter:1860 \
  redis:763 \
  mysql:7362 \
  jvm-micrometer:4701 \
  k6-prometheus:19665
do
  name="${spec%%:*}"
  id="${spec##*:}"
  # 받은 JSON 은 데이터소스를 ${DS_...} 입력 변수로 남겨두는데, 프로비저닝 경로에서는 그걸
  # 채워줄 사람이 없어서 패널이 전부 빈다 — datasources.yml 의 고정 uid 로 치환해 붙인다.
  if curl -fsSL "https://grafana.com/api/dashboards/${id}/revisions/latest/download" \
     | sed -e 's/\${DS_PROMETHEUS}/prometheus/g' \
           -e 's/\${DS_LOKI}/loki/g' \
           -e 's/"\${DS_[A-Z0-9_-]*}"/"prometheus"/g' \
     > "${name}.json.tmp"
  then
    mv "${name}.json.tmp" "${name}.json"
    echo "받음: ${name}.json (grafana.com/dashboards/${id})"
  else
    rm -f "${name}.json.tmp"
    echo "실패(건너뜀): ${name} (id=${id}) — ID가 바뀌었는지 grafana.com 에서 확인" >&2
  fi
done
