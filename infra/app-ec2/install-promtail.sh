#!/usr/bin/env bash
# 앱 EC2에서 실행하기 전에 promtail-config.yaml 의 <모니터링-EC2-사설IP> 를 채워야 한다.
set -euo pipefail

VERSION=3.2.0
cd /tmp
curl -LO "https://github.com/grafana/loki/releases/download/v${VERSION}/promtail-linux-amd64.zip"
sudo apt-get install -y unzip 2>/dev/null || sudo yum install -y unzip
unzip -o promtail-linux-amd64.zip
sudo mv promtail-linux-amd64 /usr/local/bin/promtail

sudo mkdir -p /etc/promtail
sudo cp "$(dirname "$0")/promtail-config.yaml" /etc/promtail/config.yaml
sudo cp "$(dirname "$0")/promtail.service" /etc/systemd/system/promtail.service
sudo systemctl daemon-reload
sudo systemctl enable --now promtail

echo "확인: sudo systemctl status promtail"
