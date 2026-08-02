#!/usr/bin/env bash
# 앱 EC2에서 실행. myapp.service 와 같은 방식(네이티브 바이너리 + systemd)으로 설치한다 —
# 이 인스턴스는 Docker를 안 쓰고 있어서 Docker를 새로 들이지 않는다.
set -euo pipefail

VERSION=1.8.2
cd /tmp
curl -LO "https://github.com/prometheus/node_exporter/releases/download/v${VERSION}/node_exporter-${VERSION}.linux-amd64.tar.gz"
tar xzf "node_exporter-${VERSION}.linux-amd64.tar.gz"
sudo mv "node_exporter-${VERSION}.linux-amd64/node_exporter" /usr/local/bin/
sudo cp "$(dirname "$0")/node_exporter.service" /etc/systemd/system/node_exporter.service
sudo systemctl daemon-reload
sudo systemctl enable --now node_exporter

echo "확인: curl -s http://localhost:9100/metrics | head"
