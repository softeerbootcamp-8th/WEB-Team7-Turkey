#!/usr/bin/env bash
# app-ec2/install-node-exporter.sh 와 동일한 내용이다. Redis EC2는 별개 호스트라
# 공용 스크립트로 묶지 않고 그대로 복제해서 둔다(호스트마다 독립적으로 실행되는 설치
# 스크립트라 공유 모듈화의 이득이 없다).
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
