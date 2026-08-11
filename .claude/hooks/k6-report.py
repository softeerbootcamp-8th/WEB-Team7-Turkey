#!/usr/bin/env python3
"""k6 부하테스트가 끝나면 Prometheus 지표 표를 컨텍스트로 올린다(PostToolUse/Bash).

왜 훅인가: 지표 수집을 잊는 것이 가장 흔한 실패다. 스킬 절차를 따르지 않고 k6 를 직접 돌린
경우에도 이게 붙는다. 반대로 **사용자가 자기 터미널에서 돌린 k6 에는 붙지 않는다**(훅은 이
세션의 tool 호출에만 발동한다) — 그때는 사람이 collect.py 를 직접 부른다.

측정 구간은 상태 파일 없이 k6 자신의 출력에서 얻는다. 마지막 진행 줄이
`running (1m36.1s), 00/20 VUs, ...` 라 여기서 실행 시간을 뽑고, 끝을 now 로 잡아 역산한다.
그래서 훅은 k6 실행과 별개의 어떤 상태도 관리하지 않는다.
"""
import json
import re
import subprocess
import sys
import time
from pathlib import Path

# k6 의 duration 표기: 1h2m3.4s / 1m36.1s / 45.2s
_DUR = re.compile(r"running \((?:(\d+)h)?(?:(\d+)m)?([\d.]+)s\)")
_TESTID = re.compile(r"testid=([\w.\-]+)")


def parse_duration(text):
    """가장 마지막 `running (...)` 표기를 초로. 못 찾으면 None."""
    last = None
    for m in _DUR.finditer(text):
        h, mi, s = m.group(1), m.group(2), m.group(3)
        last = int(h or 0) * 3600 + int(mi or 0) * 60 + float(s)
    return last


def main():
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return  # 훅 입력이 이상하면 조용히 빠진다. 훅이 작업을 막아서는 안 된다.

    tool_input = payload.get("tool_input") or {}
    command = tool_input.get("command") or ""

    # k6 실행이 아니면 통과. `k6 --help`, collect.py 호출 등은 제외한다.
    if "k6" not in command or not re.search(r"\bk6\b.*\brun\b", command):
        return
    # 백그라운드 실행이면 이 훅은 '시작' 시점에 불린다 — 그때 지표를 모으면 빈 표가 나온다.
    if tool_input.get("run_in_background"):
        return

    response = payload.get("tool_response")
    if isinstance(response, dict):
        output = (response.get("stdout") or "") + (response.get("stderr") or "")
    else:
        output = str(response or "")

    duration = parse_duration(output)
    end = int(time.time())
    if duration is None:
        # 여기까지 왔는데 실행 시간이 없는 경우는 둘이다:
        #  (a) k6 가 초기화 단계에서 죽었다(스크립트 오류, 로그인 실패) → 알려야 한다.
        #  (b) 애초에 k6 실행이 아니었다. 명령문에 `k6 run` 이라는 **문자열만** 들어 있는 경우
        #      (문서 수정, grep, 이 훅 자체의 테스트)가 실제로 발생한다 → 조용히 빠져야 한다.
        # 출력에 k6 특유의 흔적이 있는지로 둘을 가른다.
        if not any(k in output for k in ("k6", "scenarios:", "execution:", "level=error")):
            return
        emit("k6 실행 시간을 출력에서 찾지 못했다(초기화 실패로 보인다). "
             "지표 수집을 건너뛴다 — k6 출력의 오류를 먼저 볼 것.")
        return
    # 램프업 전 여유 5초. collect.py 의 구간이 짧으면 increase() 가 표본을 못 채운다.
    start = end - int(duration) - 5

    testid = None
    m = _TESTID.search(command)
    if m:
        testid = m.group(1)

    project = Path(payload.get("cwd") or ".")
    collect = None
    for base in (project, project / "backend", Path(payload.get("cwd") or ".").parent):
        candidate = base / "loadtest" / "collect.py"
        if candidate.is_file():
            collect = candidate
            break
    if collect is None:
        emit("collect.py 를 못 찾았다 — backend/loadtest/collect.py 위치를 확인할 것.")
        return

    argv = [sys.executable, str(collect), str(start), str(end)]
    if testid:
        argv.append(testid)
    # 표는 구간을 한 숫자로 접는다. 시계열을 리포트와 같이 남겨 두면 나중에 "언제 꺾였나"를
    # 다시 물을 수 있다 — collect.py 가 testid 로 파일명을 만든다.
    raw_dir = collect.parents[2] / "docs" / "loadtest"
    if raw_dir.is_dir():
        # --panels: Grafana 대시보드가 정의해 둔 패널 쿼리까지 같은 파일에 담는다. 화면에서
        # 보는 값과 리포트의 값이 같아진다. 범위 질의 100여 개라 몇 초 더 걸린다.
        argv += ["--raw", str(raw_dir), "--panels"]
    try:
        done = subprocess.run(argv, capture_output=True, text=True, timeout=120)
    except subprocess.TimeoutExpired:
        emit("collect.py 가 120초 안에 끝나지 않았다(Prometheus 응답 지연?).")
        return

    if done.returncode != 0:
        emit("지표 수집 실패:\n" + (done.stdout + done.stderr).strip())
        return

    # testid 를 못 넘겨도 collect.py 가 구간의 데이터에서 되찾는다(명령문이 셸 변수를 쓰면
    # 훅은 확장 전 문자열만 본다). 그래서 여기서 경고하지 않는다 — 표의 머리글이 결과를 밝힌다.
    head = ("부하테스트가 끝났다. 아래는 Prometheus 에서 모은 이 런의 지표다.\n"
            "이 표를 그대로 쓰고 해석을 붙여 `docs/loadtest/<날짜>-<testid>.md` 로 남긴다. "
            "런 단위 p95/p99 는 위 k6 summary 값이 정본이다.\n\n")
    emit(head + done.stdout.strip())


def emit(context):
    json.dump({
        "hookSpecificOutput": {
            "hookEventName": "PostToolUse",
            "additionalContext": context,
        }
    }, sys.stdout)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
