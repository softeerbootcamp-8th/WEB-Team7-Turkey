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
  #
  # redis(763) 대시보드는 쿠버네티스 전제(namespace 라벨)로 만들어졌다 — 우리 redis_exporter 는
  # 그 라벨을 안 찍어서 namespace 변수가 빈 목록이 되고, 그걸 걸러 쓰는 instance 변수도 따라서
  # 비어 19개 패널 전부 데이터가 안 뜬다(instance=~"" 는 아무 것도 매칭 못 함). instance 쿼리에서
  # namespace 필터를 떼어 우회한다(다른 대시보드엔 없는 패턴이라 전체에 걸어도 안전하다).
  # 빈 배열을 "${arr[@]}"로 그대로 펼치면 macOS 기본 bash(3.2)의 `set -u`에서
  # "unbound variable"로 죽는다 — no-op(`-e ''`)으로 기본값을 채워 항상 최소 1개 원소를 둔다.
  mysql_patch=(-e '')
  if [ "$name" = "mysql" ]; then
    # mysql(7362) 대시보드의 "System Charts" 행(CPU/Load 등)은 node_exporter 패널을 그대로
    # 끌어와 $host 를 mysqld_exporter 의 instance(mysqld-exporter:9104)로 채운다 — 그런데 그
    # 값은 node_exporter 쪽엔 없다(우리 node_exporter 는 인스턴스 하나를 job=node-app/
    # node-db/node-redis 세 개로 나눠 스크레이프한다, docker-compose.loadtest.yml). instance
    # 필터가 영영 안 맞아 CPU/Load 패널이 조용히 빈다. node-exporter(1860) 대시보드 자신은
    # $host 가 제대로 맞으므로 이 패치를 mysql 에만 건다 — 전체에 걸면 그쪽을 깨뜨린다.
    mysql_patch=(
      -e 's/node_cpu_seconds_total{instance=\\"\$host\\",mode!=\\"idle\\"}/node_cpu_seconds_total{job=\\"node-db\\",cpu=~\\"2|3\\",mode!=\\"idle\\"}/g'
      -e 's/node_cpu_seconds_total{instance=\\"\$host\\",mode!=\\"idle\\",mode!=\\"iowait\\"}/node_cpu_seconds_total{job=\\"node-db\\",cpu=~\\"2|3\\",mode!=\\"idle\\",mode!=\\"iowait\\"}/g'
      -e 's/node_cpu_seconds_total_average{instance=~\\"\$host\\", mode!=\\"total\\", mode!=\\"idle\\"}/node_cpu_seconds_total_average{job=~\\"node-db\\",cpu=~\\"2|3\\", mode!=\\"total\\", mode!=\\"idle\\"}/g'
      -e 's/node_load1{instance=\\"\$host\\"}/node_load1{job=\\"node-db\\"}/g'
    )
  fi
  if curl -fsSL "https://grafana.com/api/dashboards/${id}/revisions/latest/download" \
     | sed -e 's/\${DS_PROMETHEUS}/prometheus/g' \
           -e 's/\${DS_LOKI}/loki/g' \
           -e 's/"\${DS_[A-Z0-9_-]*}"/"prometheus"/g' \
           -e 's/label_values(redis_up{namespace=~\\"\$namespace\\"}, instance)/label_values(redis_up, instance)/' \
           "${mysql_patch[@]}" \
     > "${name}.json.tmp"
  then
    mv "${name}.json.tmp" "${name}.json"
    echo "받음: ${name}.json (grafana.com/dashboards/${id})"
  else
    rm -f "${name}.json.tmp"
    echo "실패(건너뜀): ${name} (id=${id}) — ID가 바뀌었는지 grafana.com 에서 확인" >&2
  fi
done
