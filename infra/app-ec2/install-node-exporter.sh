#!/usr/bin/env bash
# 앱 EC2에서 실행. myapp.service 와 같은 방식(네이티브 바이너리 + systemd)으로 설치한다 —
# 이 인스턴스는 Docker를 안 쓰고 있어서 Docker를 새로 들이지 않는다.
set -euo pipefail

# dirname "$0" 는 상대경로로 실행하면(./install-node-exporter.sh) 상대경로를 돌려주는데,
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

VERSION=1.8.2
cd /tmp
curl -LO "https://github.com/prometheus/node_exporter/releases/download/v${VERSION}/node_exporter-${VERSION}.linux-${ARCH}.tar.gz"
tar xzf "node_exporter-${VERSION}.linux-${ARCH}.tar.gz"
sudo mv "node_exporter-${VERSION}.linux-${ARCH}/node_exporter" /usr/local/bin/
sudo cp "$SCRIPT_DIR/node_exporter.service" /etc/systemd/system/node_exporter.service
sudo systemctl daemon-reload
sudo systemctl enable --now node_exporter

echo "확인: curl -s http://localhost:9100/metrics | head"
