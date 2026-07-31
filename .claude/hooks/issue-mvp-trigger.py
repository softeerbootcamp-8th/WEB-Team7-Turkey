#!/usr/bin/env python3
"""UserPromptSubmit 훅 — 이슈 번호가 등장하면 mvp-feature 스킬을 강제로 로드시킨다.

스킬의 description 만으로도 대개 트리거되지만, "142번 부탁" 처럼 짧은 요청에서는
모델이 스킬을 건너뛰고 바로 코드를 쓰기 시작하는 경우가 있다. 그러면 계약 확정 게이트도,
테스트 3층도, 작업 기록 문서도 통째로 빠진다. 이 훅은 그 구멍을 막는다.

동작:
  - 프롬프트에서 이슈 참조(#142, 이슈 142, 142번 이슈, GitHub 이슈 URL)를 찾는다.
  - 찾으면 스킬을 로드하라는 지시를 컨텍스트에 주입한다.
  - 같은 세션에서 같은 이슈 번호로는 한 번만 주입한다. 매 턴 주입하면 토큰만 먹고,
    이 스킬의 존재 이유가 토큰 절약이라 자기모순이 된다.

훅은 절대 프롬프트 제출을 막아서는 안 되므로, 어떤 예외가 나도 조용히 종료한다.
"""

import json
import os
import re
import sys
import tempfile

ISSUE_PATTERNS = [
    re.compile(r"github\.com/[\w.-]+/[\w.-]+/issues/(\d{1,6})"),
    re.compile(r"#(\d{1,6})\b"),
    re.compile(r"(?:이슈|issue)\s*#?\s*(\d{1,6})\b", re.IGNORECASE),
    re.compile(r"(\d{1,6})\s*번\s*이슈"),
    re.compile(r"이슈\s*(\d{1,6})\s*번"),
]

GUIDANCE = """\
[자동 주입 · mvp-feature 훅] 이 요청에 이슈 번호({issues})가 있다.

이 저장소의 이슈 기반 기능 개발은 `mvp-feature` 스킬이 정본 절차다. 코드를 쓰기 전에
Skill 도구로 `mvp-feature` 를 먼저 로드하고, 그 단계(이슈 읽기 → 범위 판정 → 계약 확정 게이트
→ 구현 → 단위·통합·E2E 테스트 → 작업 기록 문서)를 따른다.

스킬을 건너뛰고 바로 구현하면 사람 확인 게이트와 기록 문서가 빠져서, 나중에 "왜 이렇게 했는지"를
아무도 답할 수 없게 된다. 그게 이 절차가 있는 이유다.

예외: 이 요청이 기능 구현이 아니라 단순 질문·코드 리뷰·버그 원인 조사·문서 수정이라면
스킬을 로드하지 말고, 그렇게 판단한 이유를 한 줄로 밝힌 뒤 요청한 일만 한다.\
"""


def find_issue_numbers(prompt: str) -> list:
    found = []
    for pattern in ISSUE_PATTERNS:
        for match in pattern.findall(prompt):
            if match not in found:
                found.append(match)
    return found


def state_path(session_id: str) -> str:
    """세션별 주입 이력 파일. .git 아래에 두어 커밋 대상에서 자연스럽게 빠지게 한다."""
    project_dir = os.environ.get("CLAUDE_PROJECT_DIR", "")
    base = os.path.join(project_dir, ".git")
    if not (project_dir and os.path.isdir(base)):
        base = tempfile.gettempdir()
    directory = os.path.join(base, "mvp-feature-trigger")
    os.makedirs(directory, exist_ok=True)
    safe_session = re.sub(r"[^A-Za-z0-9_.-]", "_", session_id or "unknown")
    return os.path.join(directory, f"{safe_session}.json")


def already_injected(path: str, issues: list) -> bool:
    """이번 프롬프트의 이슈가 전부 이미 주입된 것이면 다시 넣지 않는다."""
    try:
        with open(path, encoding="utf-8") as handle:
            seen = set(json.load(handle))
    except (OSError, ValueError):
        seen = set()

    fresh = [issue for issue in issues if issue not in seen]
    if not fresh:
        return True

    seen.update(fresh)
    try:
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(sorted(seen), handle)
    except OSError:
        pass  # 기록에 실패해도 주입 자체는 진행한다
    return False


def main() -> None:
    payload = json.load(sys.stdin)
    prompt = payload.get("prompt") or ""

    issues = find_issue_numbers(prompt)
    if not issues:
        return

    if already_injected(state_path(payload.get("session_id", "")), issues):
        return

    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "UserPromptSubmit",
            "additionalContext": GUIDANCE.format(issues=", ".join("#" + n for n in issues)),
        }
    }, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # 훅 실패가 사용자 프롬프트를 막는 일은 없어야 한다.
        pass
    sys.exit(0)
