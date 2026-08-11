#!/usr/bin/env python3
"""부하테스트 한 런의 지표를 Prometheus 에서 긁어 리포트 표(마크다운)로 찍는다.

    ./loadtest/collect.py <시작epoch> <끝epoch> [testid] > docs/loadtest/2026-08-11-location.md

k6 실행 직전·직후의 `date +%s` 를 그대로 넘긴다. 시각을 파일에 적어 두는 방식(handleSummary 등)을
쓰지 않는 이유는 그게 상태를 하나 더 만들기 때문이다 — 인자 두 개로 끝난다.

읽는 대상은 **로컬** 모니터링 스택(infra/monitoring-ec2, docker-compose.local.yml +
docker-compose.loadtest.yml)의 Prometheus 다. PROM_URL 로 바꿀 수 있지만 배포 EC2 를 겨냥하지는
말 것 — 그쪽에는 로컬 앱 지표가 없고, k6 결과를 밀어넣으면 보존 상한(8GB)에서 운영 지표가 밀린다.

퍼센타일 주의: k6 의 p95/p99 는 flush 구간(기본 5초)마다의 값이라 런 전체 값이 아니다.
퍼센타일은 평균낼 수 없으므로 여기서는 **구간 최댓값(참고용)** 으로만 찍는다. 리포트에 적을
런 단위 p95/p99 는 k6 가 종료할 때 터미널에 찍는 summary 를 쓴다.
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PROM = os.environ.get("PROM_URL", "http://localhost:9099").rstrip("/")


class MultiSeries(Exception):
    """집계(sum/max)를 빼먹은 쿼리. 조용히 첫 시리즈를 쓰면 틀린 값이 리포트에 박힌다 —
    실제로 그랬다: k6 의 p95 가 시리즈 두 개(setup 로그인 / 부하 대상)로 나오는데 첫 번째를
    집어 bcrypt 로그인의 63ms 를 위치 갱신 지연으로 보고했다. 그래서 예외로 만든다."""


def query(promql, at):
    """instant query 하나. 값이 없으면 None. 시리즈가 둘 이상이면 MultiSeries."""
    url = f"{PROM}/api/v1/query?" + urllib.parse.urlencode({"query": promql, "time": at})
    with urllib.request.urlopen(url, timeout=10) as res:
        body = json.load(res)
    if body.get("status") != "success":
        return None
    result = body["data"]["result"]
    if not result:
        return None
    if len(result) > 1:
        raise MultiSeries(f"{len(result)}개 시리즈: {promql}")
    return float(result[0]["value"][1])


# raw 시계열로 남길 것. 표는 구간을 하나의 숫자로 접기 때문에 **모양**을 잃는다 —
# 처리량이 언제 꺾였는지, 지연이 어느 VU 에서 튀었는지는 시계열이 있어야 판단할 수 있다.
# Prometheus 보존이 15일이라 그 안에는 다시 뽑을 수 있지만, 리포트와 같이 남겨 두면
# 보존 기간이 지나거나 로컬 스택을 지운 뒤에도 근거가 남는다.
RAW = [
    ("k6_rps", "sum(rate(k6_http_reqs_total{{{k6}}}[15s]))"),
    ("k6_vus", "sum(k6_vus{{{k6}}})"),
    ("k6_p95_seconds", 'max(k6_http_req_duration_p95{{group="",{k6}}})'),
    ("k6_p99_seconds", 'max(k6_http_req_duration_p99{{group="",{k6}}})'),
    ("k6_failed_rate", "max(k6_http_req_failed_rate{{{k6}}})"),
    # 스크레이프 주기가 15초라 rate() 의 범위는 최소 그 2배여야 한다. `[15s]` 로 두면 창에
    # 표본이 하나뿐이라 **결과가 조용히 빈다**(실제로 그랬다). k6 지표는 5초 flush 라 15s 로 족하다.
    ("app_rps", "sum(rate(http_server_requests_seconds_count[1m]))"),
    ("app_mean_seconds",
     "sum(rate(http_server_requests_seconds_sum[1m])) "
     "/ sum(rate(http_server_requests_seconds_count[1m]))"),
    ("hikari_active", "max(hikaricp_connections_active)"),
    ("hikari_pending", "max(hikaricp_connections_pending)"),
    ("jvm_threads", "max(jvm_threads_live_threads)"),
    ("tomcat_connections", "max(tomcat_connections_current_connections)"),
    ("heap_used_bytes", 'sum(jvm_memory_used_bytes{{area="heap"}})'),
    ("gc_pause_seconds_per_sec", "sum(rate(jvm_gc_pause_seconds_sum[1m]))"),
    ("mysql_questions_per_sec", "sum(rate(mysql_global_status_questions[1m]))"),
    ("redis_commands_per_sec", "sum(rate(redis_commands_processed_total[1m]))"),
]


# Grafana 대시보드가 이미 정의해 둔 패널 쿼리도 함께 긁는다. 손으로 고른 RAW 15개보다 훨씬
# 넓고(여기서 100개 이상), **화면에서 보는 값과 리포트의 값이 같아진다**는 이점이 있다.
# 값 → 각 대시보드의 변수를 채울 job(그 job 의 instance 로 치환한다). None 은 instance 를 안 쓰는 것.
PANEL_DASHBOARDS = {
    "k6-prometheus.json": None,
    "jvm-micrometer.json": "spring-app",
    "mysql.json": "mysql",
    "redis.json": "redis",
}
# node-exporter.json(284개)은 제외한다. 전부 호스트 전체 지표라 1GiB/2vCPU 로 묶인 **앱 컨테이너**
# 를 나타내지 않는다 — 개발 PC 의 다른 프로세스까지 섞여 부하테스트 판단에 쓸 수 없다.


def find_dashboards(start):
    """infra/monitoring-ec2/provisioning/dashboards 를 위로 올라가며 찾는다."""
    for base in [Path(start).resolve(), *Path(start).resolve().parents]:
        cand = base / "infra" / "monitoring-ec2" / "provisioning" / "dashboards"
        if cand.is_dir():
            return cand
    return None


def panel_queries(path):
    """(패널명, expr) 목록. 행(row) 안에 중첩된 패널까지 훑는다."""
    found = []

    def walk(node, title):
        if isinstance(node, dict):
            title = node.get("title", title) if "panels" in node or "targets" in node else title
            for target in node.get("targets", []) or []:
                expr = target.get("expr")
                if isinstance(expr, str) and expr.strip():
                    found.append((title or "?", expr.strip()))
            for value in node.values():
                walk(value, title)
        elif isinstance(node, list):
            for value in node:
                walk(value, title)

    walk(json.loads(Path(path).read_text(encoding="utf-8")), None)
    return found


def harvest_panels(dashboards, start, end, step, testid):
    """대시보드 패널 쿼리를 그 구간에 대해 실행한다. 반환: {대시보드: {패널: {expr, values}}}"""
    instances = {}
    for series in query_series('count by (job, instance) ({job!=""})', end):
        instances[series.get("job")] = series.get("instance")

    out, stats = {}, {"실행": 0, "값있음": 0, "빈결과": 0, "변수미해결": 0}
    for name, job in PANEL_DASHBOARDS.items():
        path = Path(dashboards) / name
        if not path.is_file():
            continue
        # Grafana 변수 치환. instance 를 안 쓰는 대시보드는 빈 문자열로 두면 PromQL 이
        # "그 라벨이 없는 시리즈"에 매칭한다(앱 지표에 application 라벨이 없어도 도는 이유).
        sub = {
            "$__rate_interval": "1m", "$__interval": "1m",
            "$rate_interval": "1m", "$interval": "1m",
            "$host": instances.get(job, ""), "$instance": instances.get(job, ""),
            "$node": instances.get(job, ""), "$job": job or "",
            "$application": "", "$quantile_stat": "p95",
            "$testid": testid or ".*",
        }
        panels = {}
        for title, expr in panel_queries(path):
            filled = expr
            for key in sorted(sub, key=len, reverse=True):
                filled = filled.replace(key, sub[key])
            stats["실행"] += 1
            if re.search(r"\$\w", filled):  # repeat 변수 등 — 남은 것은 돌리지 않는다
                stats["변수미해결"] += 1
                continue
            values = query_range(filled, start, end, step)
            stats["값있음" if values else "빈결과"] += 1
            if values:
                panels.setdefault(title, []).append({"expr": filled, "values": values})
        if panels:
            out[name] = panels
    return out, stats


def query_series(promql, at):
    """시리즈의 라벨 집합 목록(값은 버린다)."""
    url = f"{PROM}/api/v1/query?" + urllib.parse.urlencode({"query": promql, "time": at})
    with urllib.request.urlopen(url, timeout=10) as res:
        body = json.load(res)
    if body.get("status") != "success":
        return []
    return [r["metric"] for r in body["data"]["result"]]


def query_range(promql, start, end, step):
    url = f"{PROM}/api/v1/query_range?" + urllib.parse.urlencode(
        {"query": promql, "start": start, "end": end, "step": step})
    with urllib.request.urlopen(url, timeout=30) as res:
        body = json.load(res)
    if body.get("status") != "success":
        return []
    result = body["data"]["result"]
    return result[0]["values"] if result else []


def save_raw(target, start, end, testid, k6sel, step=5, panels_from=None):
    """시계열을 JSON 으로 저장하고 경로를 돌려준다. target 이 디렉터리면 파일명을 만든다."""
    path = Path(target)
    if path.is_dir():
        day = time.strftime("%Y-%m-%d", time.localtime(end))
        path = path / f"{day}-{testid or 'unknown'}-raw.json"
    series = {name: query_range(build(q, end - start, k6sel), start, end, step)
              for name, q in RAW}
    body = {
        "window": {"start": start, "end": end, "step": step, "testid": testid},
        "note": "값은 [epoch, 문자열] 쌍. p95/p99 와 app_mean 은 초 단위(밀리초 아님).",
        "series": series,
    }
    panel_stats = None
    if panels_from:
        body["panels"], panel_stats = harvest_panels(panels_from, start, end, step, testid)
    path.write_text(json.dumps(body, indent=1), encoding="utf-8")
    return path, sum(1 for v in series.values() if v), panel_stats


def query_label(promql, at, label):
    """시리즈들의 라벨 값 목록. 여러 시리즈를 허용해야 하므로 query() 와 따로 둔다."""
    url = f"{PROM}/api/v1/query?" + urllib.parse.urlencode({"query": promql, "time": at})
    with urllib.request.urlopen(url, timeout=10) as res:
        body = json.load(res)
    if body.get("status") != "success":
        return []
    return sorted({r["metric"].get(label, "") for r in body["data"]["result"]} - {""})


def fmt(v, unit="", scale=1.0, digits=2):
    if v is None:
        return "-"
    return f"{v * scale:,.{digits}f}{unit}"


# (표시명, PromQL, 단위, 배율, 소수점) — {w} 는 윈도우 초, {k6} 는 testid 라벨 매처
#
# 규칙 두 개:
#  - 모든 쿼리는 sum()/max() 로 한 시리즈로 줄인다. 안 그러면 MultiSeries 로 죽는다(의도).
#  - k6 지연 지표는 `group="",` 로 **setup 을 제외한다.** setup 의 로그인은 bcrypt 라 위치
#    갱신보다 14배 느려서(63ms vs 4.4ms), 섞으면 부하 대상 지연이 그 값으로 덮인다.
# k6 의 duration 계열 단위는 **초** 다(밀리초 아님) — 배율 1000 으로 ms 로 바꾼다.
ROWS = [
    ("--- k6(부하 발생기) ---", None, "", 1, 0),
    ("총 요청", "sum(increase(k6_http_reqs_total{{{k6}}}[{w}s]))", "건", 1, 0),
    ("평균 처리량", "sum(increase(k6_http_reqs_total{{{k6}}}[{w}s])) / {w}", " req/s", 1, 0),
    ("피크 처리량(15초 해상도)",
     "max_over_time((sum(rate(k6_http_reqs_total{{{k6}}}[15s])))[{w}s:15s])", " req/s", 1, 0),
    ("실패율(구간 최댓값)", "max(max_over_time(k6_http_req_failed_rate{{{k6}}}[{w}s]))", "%", 100, 2),
    ("p95(구간 최댓값·참고)",
     'max(max_over_time(k6_http_req_duration_p95{{group="",{k6}}}[{w}s]))', " ms", 1000, 2),
    ("p99(구간 최댓값·참고)",
     'max(max_over_time(k6_http_req_duration_p99{{group="",{k6}}}[{w}s]))', " ms", 1000, 2),
    ("최대 VU", "max(max_over_time(k6_vus{{{k6}}}[{w}s]))", "", 1, 0),
    ("드롭된 iteration",
     "sum(increase(k6_dropped_iterations_total{{{k6}}}[{w}s])) or vector(0)", "건", 1, 0),

    ("--- 앱(Micrometer) ---", None, "", 1, 0),
    ("서버측 평균 처리시간",
     "sum(increase(http_server_requests_seconds_sum[{w}s])) "
     "/ sum(increase(http_server_requests_seconds_count[{w}s]))", " ms", 1000, 2),
    ("5xx 응답",
     'sum(increase(http_server_requests_seconds_count{{status=~"5.."}}[{w}s])) or vector(0)',
     "건", 1, 0),
    ("Hikari 활성 최대", "max(max_over_time(hikaricp_connections_active[{w}s]))", "", 1, 0),
    ("Hikari 대기 최대", "max(max_over_time(hikaricp_connections_pending[{w}s]))", "", 1, 0),
    ("JVM 플랫폼 스레드 최대", "max(max_over_time(jvm_threads_live_threads[{w}s]))", "", 1, 0),
    ("Tomcat 현재 연결 최대",
     "max(max_over_time(tomcat_connections_current_connections[{w}s]))", "", 1, 0),
    ("GC 횟수", "sum(increase(jvm_gc_pause_seconds_count[{w}s]))", "회", 1, 0),
    ("GC 정지 합계", "sum(increase(jvm_gc_pause_seconds_sum[{w}s]))", "초", 1, 2),
    ("힙 사용 최대",
     'max_over_time((sum(jvm_memory_used_bytes{{area="heap"}}))[{w}s:15s])',
     " MiB", 1 / 1048576, 0),
    ("힙 상한", 'sum(jvm_memory_max_bytes{{area="heap"}})', " MiB", 1 / 1048576, 0),

    ("--- 요청당 왕복 수 ---", None, "", 1, 0),
    # 좌변을 sum() 으로 감싸는 게 핵심이다. 감싸지 않으면 좌변에 {instance,job} 라벨이 남아
    # 우변(라벨 없음)과 벡터 매칭이 안 되고 **조용히 빈 결과**가 된다.
    ("MySQL 문장/요청",
     "sum(increase(mysql_global_status_questions[{w}s])) "
     "/ sum(increase(k6_http_reqs_total{{{k6}}}[{w}s]))", "", 1, 2),
    ("Redis 커맨드/요청",
     "sum(increase(redis_commands_processed_total[{w}s])) "
     "/ sum(increase(k6_http_reqs_total{{{k6}}}[{w}s]))", "", 1, 2),
]


def build(promql, window, k6sel):
    return promql.format(k6=k6sel, w=window)


def main(argv):
    raw_target = None
    with_panels = "--panels" in argv
    argv = [a for a in argv if a != "--panels"]
    if "--raw" in argv:
        i = argv.index("--raw")
        if i + 1 >= len(argv):
            sys.exit("--raw 뒤에 디렉터리나 파일 경로가 필요하다")
        raw_target = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    if len(argv) < 3:
        sys.exit(__doc__)
    start, end = int(argv[1]), int(argv[2])
    testid = argv[3] if len(argv) > 3 else None
    window = end - start
    if window <= 0:
        sys.exit(f"윈도우가 비었다: start={start} end={end}")
    try:
        with urllib.request.urlopen(f"{PROM}/-/ready", timeout=5) as res:
            res.read()
    except (urllib.error.URLError, OSError) as e:
        sys.exit(
            f"Prometheus({PROM}) 에 못 붙었다: {e}\n"
            "  cd infra/monitoring-ec2 && docker compose -f docker-compose.yml \\\n"
            "    -f docker-compose.local.yml -f docker-compose.loadtest.yml up -d"
        )

    # testid 를 안 받았으면 그 구간에 데이터가 있는 testid 를 직접 찾는다. 호출자가 넘기지
    # 못하는 경우가 실제로 있다 — 훅은 셸이 확장하기 **전** 명령문을 보므로 `testid=$ID` 같은
    # 변수를 복원할 수 없다. 구간이 이미 런을 특정하므로 데이터에서 되찾는 편이 확실하다.
    detected = None
    if not testid:
        found = query_label(
            build("count by (testid) (increase(k6_http_reqs_total{{{k6}}}[{w}s]))", window, ""),
            end, "testid")
        if len(found) == 1:
            testid, detected = found[0], "자동 탐지"
        elif len(found) > 1:
            detected = f"이 구간에 런이 {len(found)}개 겹쳐 있다: {', '.join(found)} — 전부 합산했다"

    # 셀렉터 내부에 들어가는 매처다(중괄호 없음). 뒤의 쉼표는 다른 매처와 이어 붙기 위한 것이고,
    # PromQL 은 셀렉터의 끝 쉼표를 허용한다.
    k6sel = f'testid="{testid}",' if testid else ""

    # k6 결과가 아예 없으면 표를 찍지 않고 죽는다. 빈 표를 리포트로 남기는 것이 최악이다.
    total = query(build("sum(increase(k6_http_reqs_total{{{k6}}}[{w}s]))", window, k6sel), end)
    if not total:
        sys.exit(
            f"이 구간({window}초)에 k6 결과가 없다"
            + (f" (testid={testid})" if testid else "")
            + ".\n  - k6 실행에 --tag testid=<값> 을 줬는지, 그 값을 그대로 넘겼는지\n"
            "  - K6_OUT=experimental-prometheus-rw 로 원격쓰기가 됐는지(k6 로그에 flush 에러)\n"
            "  - start/end epoch 가 실제 실행 구간인지"
        )

    label = f", testid=`{testid}`" if testid else ""
    if detected:
        label += f" ({detected})"
    elif not testid:
        label += " (testid 없음 — `--tag testid=` 를 붙이면 런을 특정할 수 있다)"
    print(f"측정 구간: <!-- epoch {start}~{end} --> {window}초{label}")
    print()
    print("| 항목 | 값 |")
    print("|---|---|")
    missing, broken = [], []
    for name, promql, unit, scale, digits in ROWS:
        if promql is None:
            print(f"| **{name}** | |")
            continue
        try:
            v = query(build(promql, window, k6sel), end)
        except MultiSeries as e:
            broken.append(f"{name}({e})")
            print(f"| {name} | **집계 누락** |")
            continue
        if v is None:
            missing.append(name)
        print(f"| {name} | {fmt(v, unit, scale, digits)} |")
    print()
    print("- 앱 컨테이너 CPU·메모리는 위 표에 없다(node-exporter 는 호스트 전체를 본다). "
          "`docker stats` 값을 손으로 적을 것.")
    print("- 런 단위 p95/p99 는 k6 종료 시 터미널 summary 를 쓴다(위 값은 구간 최댓값).")
    print("- **요청당 왕복 수는 상한이다.** 분자는 그 구간 MySQL·Redis 의 *모든* 활동이라 "
          "setup 의 로그인, exporter 자신의 스크레이프 질의, 백그라운드 스케줄러(배차 만료 스캐너 "
          "1분 주기)가 함께 들어간다. 분모는 k6 요청 수뿐이다. 순수 요청 비용을 보려면 "
          "부하 없이 같은 구간을 한 번 재서 바닥값을 빼야 한다.")
    if missing and window < 60:
        # 스크레이프 주기 15초. 구간이 짧으면 increase()/rate() 의 창에 표본이 1~2개뿐이라
        # **조용히 빈 결과**가 된다(exporter 문제가 아니다). 같은 이유로 짧은 런에서는
        # 피크 처리량이 평균보다 작게 나오기도 한다.
        print(f"- **비어 있는 항목**: {', '.join(missing)} — 측정 구간이 {window}초로 짧아서다. "
              "스크레이프 주기가 15초라 구간당 표본이 1~2개면 increase()/rate() 가 값을 못 낸다. "
              "**90초 이상으로 재면 채워진다**(exporter 설정 문제가 아니다).")
    elif missing:
        print(f"- **비어 있는 항목**: {', '.join(missing)} "
              "— 해당 exporter 가 부하 대상을 보고 있는지 확인(docker-compose.loadtest.yml).")
    if broken:
        print(f"- **쿼리 버그**: {', '.join(broken)} — 이 스크립트의 ROWS 를 고칠 것(sum/max 누락).")
    if raw_target:
        dashboards = find_dashboards(raw_target) if with_panels else None
        path, filled, panel_stats = save_raw(raw_target, start, end, testid, k6sel,
                                            panels_from=dashboards)
        print(f"- raw 시계열({filled}/{len(RAW)} 계열, 5초 간격): `{path}` — 표는 구간을 한 숫자로 "
              "접으므로 처리량이 꺾인 지점·지연이 튄 구간은 이 파일로 본다.")
        if panel_stats:
            print(f"- 같은 파일의 `panels` 에 Grafana 대시보드 패널 쿼리도 담았다: "
                  f"{panel_stats['값있음']}개 값 있음 / 실행 {panel_stats['실행']} "
                  f"(빈결과 {panel_stats['빈결과']}, repeat 변수라 건너뜀 {panel_stats['변수미해결']}). "
                  "화면에서 보는 값과 같은 쿼리다.")
        elif with_panels:
            print("- `--panels` 를 줬지만 대시보드 JSON 을 못 찾았다"
                  "(`infra/monitoring-ec2/provisioning/dashboards`, gitignore 라 "
                  "`./fetch-dashboards.sh` 로 받는다).")


def selftest():
    assert build("x{{{k6}}}[{w}s]", 90, 'testid="a",') == 'x{testid="a",}[90s]'
    assert build("x{{{k6}}}[{w}s]", 90, "") == "x{}[90s]"
    assert build('x{{group="",{k6}}}', 90, 'testid="a",') == 'x{group="",testid="a",}'
    # 모든 실제 쿼리가 포맷 가능해야 한다(중괄호 이스케이프를 빠뜨리면 여기서 터진다).
    for _, promql, *_ in ROWS:
        if promql:
            assert "{k6}" not in build(promql, 90, 'testid="a",')
    assert fmt(None) == "-"
    assert fmt(1234.5, " req/s", 1, 0) == "1,234 req/s"
    assert fmt(0.0123, "%", 100, 2) == "1.23%"
    assert fmt(157286400, " MiB", 1 / 1048576, 0) == "150 MiB"
    assert fmt(0.00583, " ms", 1000, 2) == "5.83 ms"
    print("selftest ok")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
    else:
        main(sys.argv)
