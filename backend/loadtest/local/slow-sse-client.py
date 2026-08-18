#!/usr/bin/env python3
# 느린 SSE 클라이언트가 Redis pub/sub 팬아웃 전용 스레드풀(trackingEventExecutor, 고정 4개,
# RedisMessageListenerConfig)을 막아 무관한 다른 배송의 위치 중계까지 지연시키는지(HOL blocking)
# 재현한다. k6/xk6-sse는 애플리케이션 레벨 콜백만 제어할 뿐 소켓을 계속 읽어들이므로 이 재현엔
# 못 쓴다 — 진짜 TCP 수신 정체(recv()를 아예 안 부름 + 작은 SO_RCVBUF)가 필요해서 표준 라이브러리
# 소켓으로 직접 만든다.
#
# 준비 (backend/):
#   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/reset-and-seed-local.sql
#   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/seed-loadtest-riders.sql
#
# 실행 (라이더 위치 전송을 먼저 백그라운드로 띄우고, 같은 backend_default 네트워크에서 이 스크립트를 돈다):
#   docker compose --profile loadtest run --rm -d \
#     -e RIDER_COUNT=6 -e MAX_VU=6 k6 run /scripts/local/rider-location-update.js
#   docker run --rm -it --network backend_default \
#     -v "$(pwd)/loadtest/local:/scripts:ro" python:3.12-alpine \
#     python3 /scripts/slow-sse-client.py --host app --port 8080 --actuator-host app --actuator-port 8081
#
# --slow 는 풀 크기(4)보다 커야 재현된다(기본 5). control 고객 1명(마지막 계정)의 위치 수신
# 지연을 baseline 구간(느린 클라이언트 시작 전)과 비교해서 읽는다.
import argparse
import datetime
import http.client
import json
import socket
import threading
import time
import urllib.request


def login(host, port, login_id, password):
    conn = http.client.HTTPConnection(host, port, timeout=10)
    conn.request(
        "POST", "/api/customer/login",
        json.dumps({"loginId": login_id, "password": password}),
        {"Content-Type": "application/json"},
    )
    res = conn.getresponse()
    body = res.read()
    if res.status != 200:
        raise RuntimeError(f"{login_id} 로그인 실패: {res.status} {body}")
    cookie = next(
        v.split(";", 1)[0].split("=", 1)[1]
        for k, v in res.getheaders() if k.lower() == "set-cookie" and v.startswith("SESSION_ID=")
    )
    conn.close()
    return cookie


def active_delivery_id(host, port, session):
    conn = http.client.HTTPConnection(host, port, timeout=10)
    conn.request("GET", "/api/customer/deliveries/active", headers={"Cookie": f"SESSION_ID={session}"})
    res = conn.getresponse()
    data = json.loads(res.read())
    conn.close()
    if not data.get("data"):
        raise RuntimeError("진행 중 배송이 없음 — seed-loadtest-riders.sql 을 먼저 돌렸는지 확인")
    return data["data"]["deliveryId"]


def open_sse_socket(host, port, session, delivery_id, rcvbuf=None):
    s = socket.create_connection((host, port), timeout=10)
    if rcvbuf is not None:
        # 기본 수신 버퍼(자동 확장, 수십~수백 KB)로는 작은 위치 프레임 수백 개가 쌓여야 막힌다.
        # 커널이 요청값보다 크게 밀어 올릴 수 있어도(리눅스 tcp_rmem 최소치) 훨씬 빨리 채워진다.
        s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, rcvbuf)
    s.sendall((
        f"GET /api/customer/deliveries/{delivery_id}/tracking/stream HTTP/1.1\r\n"
        f"Host: {host}\r\n"
        f"Cookie: SESSION_ID={session}\r\n"
        f"Accept: text/event-stream\r\n"
        f"Connection: keep-alive\r\n\r\n"
    ).encode())
    buf = b""
    s.settimeout(10)
    while b"\r\n\r\n" not in buf:
        chunk = s.recv(1)
        if not chunk:
            raise RuntimeError("SSE 핸드셰이크 중 연결 종료")
        buf += chunk
    if b" 200 " not in buf.split(b"\r\n", 1)[0]:
        raise RuntimeError(f"SSE 연결 실패: {buf.split(chr(10).encode(), 1)[0]}")
    return s


def slow_reader(idx, host, port, session, delivery_id, hold_seconds, log):
    s = open_sse_socket(host, port, session, delivery_id, rcvbuf=2048)
    log(f"[slow-{idx}] delivery={delivery_id} 연결됨 — 앞으로 {hold_seconds}s 동안 recv() 안 부름")
    # 이 sleep이 시뮬레이션의 전부다: 소켓을 열어만 두고 더 읽지 않으면 커널 수신 버퍼가 차고,
    # 서버의 emitter.send()가 그 소켓에 쓰려 할 때 블로킹된다.
    time.sleep(hold_seconds)
    s.close()
    log(f"[slow-{idx}] 종료")


def _parse_instant(text):
    return datetime.datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()


def control_reader(host, port, session, delivery_id, duration_seconds, log, summary_interval=3):
    # 이벤트마다 로그를 찍으면 실제 라이더보다 훨씬 빠른 k6 닫힌 모델 부하에서 초당 수천 줄이
    # 쏟아진다 — summary_interval 초마다 그 구간의 최소/최대 지연만 요약해서 찍는다.
    s = open_sse_socket(host, port, session, delivery_id)
    s.settimeout(1)
    deadline = time.time() + duration_seconds
    buf = ""
    window_start = time.time()
    window_latencies = []
    while time.time() < deadline:
        try:
            chunk = s.recv(4096).decode(errors="ignore")
        except socket.timeout:
            chunk = ""
        if chunk:
            buf += chunk
            while "\n\n" in buf:
                frame, buf = buf.split("\n\n", 1)
                for line in frame.splitlines():
                    if not line.startswith("data:"):
                        continue
                    try:
                        payload = json.loads(line[len("data:"):].strip())
                    except json.JSONDecodeError:
                        continue
                    if payload.get("type") == "location":
                        window_latencies.append((time.time() - _parse_instant(payload["measuredAt"])) * 1000)
        if time.time() - window_start >= summary_interval:
            if window_latencies:
                log(f"[control] {len(window_latencies)}건 수신, "
                    f"지연 min={min(window_latencies):.0f}ms max={max(window_latencies):.0f}ms")
            else:
                log("[control] 이 구간에 수신된 위치 이벤트 없음")
            window_latencies = []
            window_start = time.time()
    s.close()


def poll_executor_metrics(actuator_host, actuator_port, duration_seconds, log):
    deadline = time.time() + duration_seconds
    url = f"http://{actuator_host}:{actuator_port}/actuator/prometheus"
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as r:
                lines = r.read().decode().splitlines()
            wanted = ("executor_active_threads{", "executor_queued_tasks{")
            values = [l for l in lines if l.startswith(wanted) and "trackingEventExecutor" in l]
            log(f"[executor] {' | '.join(values)}")
        except Exception as e:
            log(f"[executor] 조회 실패: {e}")
        time.sleep(3)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="app")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--actuator-host", default=None, help="기본값은 --host와 동일")
    parser.add_argument("--actuator-port", type=int, default=8081)
    parser.add_argument("--password", default="aa")
    parser.add_argument("--customer-prefix", default="lt_c")
    parser.add_argument("--slow", type=int, default=5, help="느린 클라이언트 수(풀 크기 4보다 커야 재현됨)")
    parser.add_argument("--baseline-seconds", type=int, default=20, help="느린 클라이언트 시작 전 기준 구간")
    parser.add_argument("--hold-seconds", type=int, default=60, help="느린 클라이언트가 안 읽고 버티는 시간")
    args = parser.parse_args()
    actuator_host = args.actuator_host or args.host

    log_lock = threading.Lock()

    def log(msg):
        with log_lock:
            print(f"{time.strftime('%H:%M:%S')} {msg}", flush=True)

    total_customers = args.slow + 1  # 마지막 계정이 control
    logins = [f"{args.customer_prefix}{i}" for i in range(1, total_customers + 1)]
    log(f"로그인 {len(logins)}건: {logins[0]} … {logins[-1]}")
    sessions = [login(args.host, args.port, lid, args.password) for lid in logins]
    delivery_ids = [active_delivery_id(args.host, args.port, s) for s in sessions]

    total_duration = args.baseline_seconds + args.hold_seconds
    threads = []

    control_idx = args.slow  # 0-based 마지막
    t = threading.Thread(
        target=control_reader,
        args=(args.host, args.port, sessions[control_idx], delivery_ids[control_idx], total_duration, log),
    )
    threads.append(t)
    threads.append(threading.Thread(
        target=poll_executor_metrics, args=(actuator_host, args.actuator_port, total_duration, log),
    ))
    for t in threads:
        t.start()

    log(f"baseline 구간 {args.baseline_seconds}s — control 지연을 먼저 관찰")
    time.sleep(args.baseline_seconds)

    log(f"느린 클라이언트 {args.slow}개 연결 시작 (풀 크기 4를 넘김)")
    for i in range(args.slow):
        st = threading.Thread(
            target=slow_reader,
            args=(i + 1, args.host, args.port, sessions[i], delivery_ids[i], args.hold_seconds, log),
        )
        threads.append(st)
        st.start()

    for t in threads:
        t.join()
    log("완료 — baseline 구간 대비 느린 클라이언트 구간의 control 지연·executor_active_threads/queued_tasks 변화를 비교할 것")


if __name__ == "__main__":
    main()
