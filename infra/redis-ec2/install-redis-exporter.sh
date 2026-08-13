#!/usr/bin/env bash
# Redis EC2에서 실행.
set -euo pipefail

VERSION=1.61.0
cd /tmp
curl -LO "https://github.com/oliver006/redis_exporter/releases/download/v${VERSION}/redis_exporter-v${VERSION}.linux-amd64.tar.gz"
tar xzf "redis_exporter-v${VERSION}.linux-amd64.tar.gz"
sudo mv "redis_exporter-v${VERSION}.linux-amd64/redis_exporter" /usr/local/bin/
sudo cp "$(dirname "$0")/redis_exporter.service" /etc/systemd/system/redis_exporter.service
sudo systemctl daemon-reload
sudo systemctl enable --now redis_exporter

echo "확인: curl -s http://localhost:9121/metrics | grep redis_exporter_last_scrape_error"
echo "  err=\"\" 0 이 아니라 NOAUTH 등이 찍히면 Redis 에 비밀번호가 걸려 있는 것이다 —"
echo "  redis_exporter.service 의 REDIS_PASSWORD 주석 참고."
echo "참고: redis_exporter.service 의 After=redis.service 는 배포판마다 실제 유닛명이"
echo "다를 수 있다(Debian/Ubuntu는 보통 redis-server.service) — systemctl list-units | grep redis 로 확인 후 필요하면 고칠 것."
