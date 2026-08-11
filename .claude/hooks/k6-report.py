#!/usr/bin/env python3
"""k6 부하테스트가 끝나면 Prometheus 지표 표를 컨텍스트로 올린다(PostToolUse/Bash).

왜 훅인가: 지표 수집을 잊는 것이 가장 흔한 실패다. 스킬 절차를 따르지 않고 k6 를 직접 돌린
경우에도 이게 붙는다. 반대로 **사용자가 자기 터미널에서 돌린 k6 에는 붙지 않는다**(훅은 이
세션의 tool 호출에만 발동) — 그때는 사람이 collect.py 를 직접 부른다.

── 첫 설계가 실패한 이유 (2026-08-11, 실사용 세션에서 발동 0회) ──────────────
처음에는 k6 출력에서 `running (1m36s)` 를 파싱해 측정 구간을 역산했다. 실제로 써 보니
**한 번도 발동하지 않았다**:
  1. 계단 런은 `run_in_background` 로 돌렸다 — 부하 중에 `docker stats` 를 병행 샘플링해야
     하는 정당한 이유가 있었다. 그런데 훅이 백그라운드를 건너뛰게 되어 있었다.
  2. 포그라운드 런은 출력을 `| tail -30` 으로 잘라서 `running (…)` 줄이 사라졌다.
게다가 그 실행 시간에는 **setup(계정별 bcrypt 로그인)이 포함**돼, 계정이 많으면 창의 대부분이
setup 이 되어 수치가 왜곡됐다(요청당 왕복 수가 13.0 대신 14~20).

그래서 출력 파싱을 전부 버렸다. 구간은 `collect.py --latest` 가 Prometheus 의 VU 타임라인에서
도출한다 — 백그라운드·출력 필터링·긴 setup 에 모두 영향받지 않는다.
"""
import json
import re
import subprocess
import sys
import time
from pathlib import Path

# 런이 이 시간 안에 끝났을 때만 보고한다. 명령문에 `k6 run` 이라는 **문자열만** 들어 있는 경우
# (문서 수정, grep, 이 훅 자체의 테스트)에 옛 런의 표를 올리지 않기 위한 가드다.
FRESH_SECONDS = 600


def find_collect(cwd):
    for base in (Path(cwd), Path(cwd) / "backend", Path(cwd).parent):
        candidate = base / "loadtest" / "collect.py"
        if candidate.is_file():
            return candidate
    return None


def main():
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return  # 훅 입력이 이상하면 조용히 빠진다. 훅이 작업을 막아서는 안 된다.

    tool_input = payload.get("tool_input") or {}
    command = tool_input.get("command") or ""
    if not re.search(r"\bk6\b.*\brun\b", command):
        return

    collect = find_collect(payload.get("cwd") or ".")
    if collect is None:
        emit("collect.py 를 못 찾았다 — backend/loadtest/collect.py 위치를 확인할 것.")
        return

    # 백그라운드 실행이면 이 훅은 '시작' 시점에 불린다. 그때는 아직 데이터가 없으니 표를 만들 수
    # 없고, 대신 끝난 뒤 무엇을 부르면 되는지 알려 준다(백그라운드는 금지 사항이 아니다 —
    # 컨테이너 CPU 를 병행 샘플링하려면 필요하다).
    if tool_input.get("run_in_background"):
        emit("k6 를 백그라운드로 돌렸다. 런이 끝난 뒤 아래를 실행해 지표를 모을 것 "
             "— 측정 구간은 Prometheus 에서 자동으로 도출된다(인자로 시각을 넘기지 않는다):\n"
             f"    {collect} --latest --steps --raw docs/loadtest --panels")
        return

    argv = [sys.executable, str(collect), "--latest", "--steps"]
    raw_dir = collect.parents[2] / "docs" / "loadtest"
    if raw_dir.is_dir():
        # 표는 구간을 한 숫자로 접는다. 시계열과 대시보드 패널 값을 리포트와 같이 남겨 두면
        # 나중에 "언제 꺾였나"를 다시 물을 수 있다.
        argv += ["--raw", str(raw_dir), "--panels"]
    try:
        done = subprocess.run(argv, capture_output=True, text=True, timeout=300)
    except subprocess.TimeoutExpired:
        emit("collect.py 가 300초 안에 끝나지 않았다(Prometheus 응답 지연?).")
        return

    if done.returncode != 0:
        emit("지표 수집 실패:\n" + (done.stdout + done.stderr).strip())
        return

    # 최신 런이 오래전에 끝났으면 이 명령은 실제 k6 실행이 아니었다고 보고 조용히 빠진다.
    fresh = re.search(r"epoch (\d+)~(\d+)", done.stdout)
    if fresh and time.time() - int(fresh.group(2)) > FRESH_SECONDS:
        return

    # 같은 런을 두 번 보고하지 않는다. 명령문에 `k6 run` 이라는 문자열만 들어 있는 경우
    # (문서 수정 등)가 방금 끝난 런의 신선도 창 안에 들어오면 표가 중복으로 올라온다.
    seen = collect.parent / ".reported-runs"
    run_id = re.search(r"testid=`([^`]+)`", done.stdout)
    if run_id:
        already = seen.read_text(encoding="utf-8").split() if seen.is_file() else []
        if run_id.group(1) in already:
            return
        seen.write_text(" ".join(already[-49:] + [run_id.group(1)]), encoding="utf-8")

    emit("부하테스트가 끝났다. 아래는 Prometheus 에서 모은 이 런의 지표다.\n"
         "이 표를 그대로 쓰고 해석을 붙여 `docs/loadtest/<날짜>-<testid>.md` 로 남긴다. "
         "런 단위 p95/p99 는 k6 가 종료할 때 찍는 summary 가 정본이다.\n\n"
         + done.stdout.strip())


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
