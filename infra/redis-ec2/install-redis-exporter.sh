#!/usr/bin/env bash
# Redis EC2에서 실행.
set -euo pipefail

# dirname "$0" 는 상대경로로 실행하면(./install-redis-exporter.sh) 상대경로를 돌려주는데,
# 아래에서 cd /tmp 로 이동하면 그 상대경로 기준점이 깨진다 — 절대경로로 미리 고정해둔다.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 인스턴스마다 아키텍처가 다를 수 있다(x86_64 vs Graviton/aarch64) — 하드코딩하면
# 잘못된 아키텍처 바이너리가 받아지고, 실행 시 커널이 거부(ENOEXEC)한 걸 셸이 스크립트인 줄
# 알고 재해석하면서 "Syntax error" 같은 엉뚱한 에러로 보인다.
case "$(uname -m)" in
  x86_64) ARCH=amd64 ;;
  aarch64) ARCH=arm64 ;;
  *) echo "지원하지 않는 아키텍처: $(uname -m)" >&2; exit 1 ;;
esac

VERSION=1.61.0
cd /tmp
curl -LO "https://github.com/oliver006/redis_exporter/releases/download/v${VERSION}/redis_exporter-v${VERSION}.linux-${ARCH}.tar.gz"
tar xzf "redis_exporter-v${VERSION}.linux-${ARCH}.tar.gz"
sudo mv "redis_exporter-v${VERSION}.linux-${ARCH}/redis_exporter" /usr/local/bin/
sudo cp "$SCRIPT_DIR/redis_exporter.service" /etc/systemd/system/redis_exporter.service
sudo systemctl daemon-reload
sudo systemctl enable --now redis_exporter

echo "확인: curl -s http://localhost:9121/metrics | head"
echo "참고: redis_exporter.service 의 After=redis.service 는 배포판마다 실제 유닛명이"
echo "다를 수 있다(Debian/Ubuntu는 보통 redis-server.service) — systemctl list-units | grep redis 로 확인 후 필요하면 고칠 것."
