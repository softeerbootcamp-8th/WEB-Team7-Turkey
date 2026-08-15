#!/bin/sh
# 컨테이너별 CPU·메모리를 주기적으로 찍는다. **부하 실행과 병행해서** 돌려야 한다.
#
#   ./loadtest/sample-stats.sh [출력csv] [초] [샘플링간격초]   # 인자 없으면 규약 경로, 간격 기본 5초
#   ./loadtest/sample-stats.sh /tmp/stats.csv 600 1 &
#   docker compose run --rm ... k6 run ...
#   ./loadtest/collect.py --latest --steps          # 규약 경로면 --stats 도 불필요
#
# 왜 필요한가: 이 값이 Prometheus 에 없다. node-exporter 는 호스트 전체를 보므로 1GiB/2vCPU 로
# 묶인 앱 컨테이너를 나타내지 못하고, 개발 PC 의 다른 프로세스까지 섞인다. 그런데 **병목 판정에
# 결정적인 값이다** — 2026-08-11 계단 런에서 "앱 CPU 203%(제한 만석) / MySQL 71% / Redis 6%"
# 라는 배분이 없었으면 "CPU 가 천장"이라는 판정을 내릴 수 없었다.
#
# k6 컨테이너도 함께 남긴다. 부하 생성기가 같은 머신에서 CPU 를 가져가면 측정이 흐려지므로,
# 그 양을 리포트에 근거로 적어야 한다(같은 런에서 1000 VU 일 때 k6 48% 였다 — 이 범위에서는
# 측정을 흐리지 않는다는 근거).
#
# cAdvisor 를 관측 스택에 붙이면 Prometheus 로 들어와 이 스크립트가 필요 없어진다. 다만
# prometheus.yml 이 배포와 공용이라 로컬 전용 job 을 넣는 방법을 정해야 해서 미뤘다.
set -eu
# 인자를 생략하면 규약 경로에 쓴다. collect.py 가 그 파일을 자동으로 찾아 쓰므로(--stats 불필요)
# 훅이 부르는 경로에서도 컨테이너 CPU 가 표에 들어온다. git 추적 안 함(.gitignore).
OUT="${1:-$(cd "$(dirname "$0")" && pwd)/.stats-latest.csv}"
END=$(( $(date +%s) + ${2:-900} ))
echo "epoch,name,cpu_percent,mem_used_mib" > "$OUT"
while [ "$(date +%s)" -lt "$END" ]; do
  T=$(date +%s)
  # MemUsage 는 "123.4MiB / 1GiB" 형태다. 앞쪽만 취해 MiB 숫자로 바꾼다(GiB 는 ×1024).
  docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' 2>/dev/null \
    | grep -E 'backend-app|backend-k6|turkey-mysql|turkey-redis' \
    | awk -F',' -v t="$T" '{
        cpu=$2; sub(/%/,"",cpu);
        split($3, m, " / "); v=m[1];
        unit=v; gsub(/[0-9.]/,"",unit); gsub(/[^0-9.]/,"",v);
        if (unit ~ /GiB/) v=v*1024; else if (unit ~ /kB|KiB/) v=v/1024;
        printf "%s,%s,%s,%.1f\n", t, $1, cpu, v;
      }' >> "$OUT"
  sleep "${3:-5}"
done
