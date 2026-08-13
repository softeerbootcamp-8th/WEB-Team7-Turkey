# 모니터링 EC2

Prometheus(지표) + Loki(로그) + Grafana(조회) + Caddy(HTTPS). 대상 EC2에 설치하는 에이전트는
`../app-ec2`, `../redis-ec2` 를 본다.

## EC2에 올리기 전 로컬 검증

```bash
./fetch-dashboards.sh   # 대시보드도 함께 확인하려면 먼저
./verify-local.sh       # 스택 기동 + 검사. 끝나도 떠 있으니 Grafana 를 눈으로 볼 것
                        # (Prometheus 는 http://localhost:9099, Grafana 는 https://localhost:8443)
./verify-local.sh --down
```

배포와 **같은** `prometheus.yml`·`rules/`·`provisioning/`·`loki-config.yaml`·`Caddyfile` 을 쓰고,
대상 주소와 DB 접속정보만 `docker-compose.local.yml` 로 갈아 끼운다(가짜 EC2 역할의
node-exporter·redis·mysql 컨테이너가 함께 뜬다). 검사 내용: 규칙 로드, 전 대상 스크레이프 성공,
**알림 규칙이 참조하는 지표가 실제로 수집되는지**, Loki 적재→조회 왕복, Caddy 자체서명 HTTPS 경유
Grafana→데이터소스 질의, 대시보드 프로비저닝.

앱 지표(JVM·Hikari·HTTP)는 `./gradlew bootRun` 을 켜 두면 함께 검증된다(안 켜면 그 항목만 WARN).

로컬에서 검증되지 않는 것 두 가지 — 배포 후 EC2에서 직접 확인해야 한다:
- **Promtail(journald)**: macOS 에 journald 가 없다. 배포 후 `journalctl -u promtail -f` 와
  Grafana Explore 에서 `{job="spring-app"}` 로 확인.
- **보안그룹·사설 IP**: 로컬은 docker 네트워크로 붙는다.

## 처음 띄울 때

```bash
cp .env.example .env               # GRAFANA_ADMIN_PASSWORD, (선택) SITE_ADDRESS
cp .my.cnf.example .my.cnf         # mysqld-exporter 접속정보(읽기전용 계정)
vi prometheus.yml targets/*.yml    # 사설 IP 채우기
./fetch-dashboards.sh              # grafana.com 대시보드 JSON 내려받기(커밋 안 함)
docker compose up -d
```

Grafana 는 Caddy 뒤에서 HTTPS 로 열린다(자체서명이라 최초 1회 "고급 > 계속 진행"). 예시
`Caddyfile`(`{$SITE_ADDRESS:https://localhost} { tls internal ... }`)은 **도메인이 있을 때만**
그대로 쓴다.

**IP 로 접속하면(도메인 없이) 이 예시로는 TLS 핸드셰이크가 "internal error"로 끊긴다.** IP 는
SNI 에 담을 수 없어서(RFC 6066) Caddy 가 어떤 이름의 인증서를 찾아야 할지 몰라 컨테이너 내부
주소로 잘못 찾기 때문이다. 실제로 이 문제를 겪고 고친 운영 사례의 패턴:

```caddyfile
{
        default_sni 3.38.124.24        # 팀 Elastic IP. 바뀌면 이 값도 같이 바꾼다.
}

https://3.38.124.24 {
        tls internal
        reverse_proxy grafana:3000
}
```

이 경우 `.env` 의 `SITE_ADDRESS` 는 안 쓴다(Caddyfile 이 주소를 직접 갖고 있다) — 비워 둬도
된다. `docker-compose.yml` 은 이 값을 `${SITE_ADDRESS:-}` 로 optional 하게 받으므로 비어 있어도
`docker compose` 명령 자체는 실패하지 않는다.

## 고친 뒤 반영하는 법

| 고친 것 | 반영 |
|---|---|
| `targets/*.yml` (앱 인스턴스 추가) | 자동. Prometheus 가 파일 변경을 감지한다 |
| `rules/*.yml` (알림 규칙) | `curl -X POST http://localhost:9090/-/reload` |
| `prometheus.yml` | `docker compose restart prometheus` — 단일 파일 바인드 마운트라 reload 로는 안 잡힌다 |
| 대시보드 | `./fetch-dashboards.sh` 재실행(프로바이더가 다시 읽는다) |

**로컬에서 브랜치를 옮겼다면 `docker compose ... up -d --force-recreate prometheus` 를 해야 한다.**
`targets/`·`rules/` 는 디렉터리 바인드 마운트인데, 브랜치 전환으로 그 디렉터리가 지워졌다 다시
생기면 컨테이너는 삭제된 inode 를 계속 본다 — 대상이 **조용히 사라지고**(mysql 만 남는다) 지표가
빈다. 실제로 재측정 한 번을 이것 때문에 날렸다.

## 알아둘 것

- **9090(Prometheus)은 루프백 바인딩**이다. 부하테스트 결과를 밀어 넣거나 개발 PC 에서 직접
  쿼리하려면 `ssh -N -L 9099:localhost:9090 <이 EC2>` 로 터널을 연다. **개발 PC 쪽 포트는 9099 로
  통일한다** — `backend/docker-compose.yml` 의 s3mock 이 9090 을 쓰기 때문이다(로컬 검증 스택도
  같은 이유로 9099 로 노출한다). k6 실행법은 그 파일의 k6 서비스 주석 참고.
- **알림은 전송되지 않는다.** `rules/alerts.yml` 은 평가·표시까지만 하고(Prometheus `/alerts`,
  Grafana > Alert rules) Alertmanager 를 붙이지 않았다 — 보낼 채널(Slack 등)이 아직 안 정해졌다.
  정해지면 compose 에 alertmanager 컨테이너 하나와 `prometheus.yml` 의 `alerting:` 블록을 추가한다.
- 기동 로그의 `provisioning/plugins`·`provisioning/alerting` 디렉터리 없음 `level=error` 두 줄은
  정상이다(둘 다 안 쓴다).
- 보존 기간: 지표 15일 또는 8GB 중 먼저 걸리는 쪽(compose 의 `--storage.tsdb.retention.*`),
  로그 7일(`loki-config.yaml`). EC2 볼륨 크기에 맞춰 조정한다.
- Loki(3100)는 평문·무인증이다. 방어선은 보안그룹의 사설 IP 허용뿐이며, 이는 각 exporter
  (9100/9121/8081)와 같은 수준이다.
