from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
from datetime import date, datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any

HOOKS_DIR = Path(__file__).resolve().parent
CLAUDE_DIR = HOOKS_DIR.parent
REPO_ROOT = CLAUDE_DIR.parent
SPEC_ROOT = REPO_ROOT / ".specs"
RUNTIME_ROOT = Path.home() / ".claude" / "memories" / "audit-log-service-hooks"
STATE_DIR = RUNTIME_ROOT / "state"
LOG_DIR = RUNTIME_ROOT / "logs"
CLAUDE_EXECUTABLE = Path(r"C:\Users\Mariya_Chernik\.local\bin\claude.exe")
CAPTURE_LOG_PATH = LOG_DIR / "capture-spec-turn-start.jsonl"
STOP_LOG_PATH = LOG_DIR / "stop-spec-self-eval.jsonl"
NESTED_ENV_FLAG = "AUDIT_SPEC_HOOK_NESTED"


def ensure_runtime_dirs() -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)


def read_hook_payload() -> dict[str, Any]:
    raw = ""
    try:
        raw = input_stream_text()
    except Exception:
        return {}
    if not raw.strip():
        return {}
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def input_stream_text() -> str:
    import sys

    return sys.stdin.read()


def utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def safe_token(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]", "_", value)


def state_file_path(session_id: str) -> Path:
    return STATE_DIR / f"{safe_token(session_id)}.json"


def write_json(path: Path, payload: dict[str, Any]) -> None:
    ensure_runtime_dirs()
    temp_path = path.with_suffix(path.suffix + ".tmp")
    temp_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
    temp_path.replace(path)


def read_json(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return payload if isinstance(payload, dict) else None


def remove_file(path: Path) -> None:
    try:
        path.unlink(missing_ok=True)
    except OSError:
        pass


def append_log(path: Path, payload: dict[str, Any]) -> None:
    ensure_runtime_dirs()
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(payload, sort_keys=True) + "\n")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def snapshot_specs() -> dict[str, str]:
    if not SPEC_ROOT.exists():
        return {}

    snapshot: dict[str, str] = {}
    for path in sorted(SPEC_ROOT.rglob("*")):
        if path.is_file():
            rel_path = path.relative_to(SPEC_ROOT).as_posix()
            snapshot[rel_path] = sha256_file(path)
    return snapshot


def feature_name_for_snapshot_path(relative_path: str) -> str | None:
    parts = PurePosixPath(relative_path).parts
    if len(parts) < 2:
        return None
    return parts[0]


def changed_features(before: dict[str, str], after: dict[str, str]) -> list[str]:
    features = {
        feature_name_for_snapshot_path(path)
        for path in sorted(set(before) | set(after))
        if before.get(path) != after.get(path)
    }
    return sorted(feature for feature in features if feature)


def expected_report_path(feature: str) -> Path:
    return SPEC_ROOT / feature / f"eval-report-{date.today().isoformat()}.md"


def relative_repo_path(path: Path) -> str:
    try:
        return path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def build_eval_prompt(feature: str) -> str:
    return (
        f"Run the repo-local `spec-self-eval` skill for feature `{feature}`.\n\n"
        "Requirements:\n"
        f"- Validate only `.specs/{feature}/`.\n"
        "- Follow `.claude/skills/spec-self-eval/SKILL.md` exactly.\n"
        "- Overwrite the dated eval report in that feature folder.\n"
        "- Do not edit any spec files or the checklist.\n"
        "- Final message: one sentence naming the report path.\n"
    )


def resolve_claude_executable() -> str:
    if CLAUDE_EXECUTABLE.exists():
        return str(CLAUDE_EXECUTABLE)
    found = shutil.which("claude")
    if found:
        return found
    return str(CLAUDE_EXECUTABLE)


def run_spec_self_eval(feature: str) -> dict[str, Any]:
    ensure_runtime_dirs()

    report_path = expected_report_path(feature)
    prior_mtime_ns = report_path.stat().st_mtime_ns if report_path.exists() else None

    command = [
        resolve_claude_executable(),
        "-p",
        build_eval_prompt(feature),
        "--permission-mode",
        "bypassPermissions",
        "--output-format",
        "text",
    ]

    env = os.environ.copy()
    env[NESTED_ENV_FLAG] = "1"

    result = subprocess.run(
        command,
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=600,
        env=env,
    )

    final_message = (result.stdout or "").strip()

    report_exists = report_path.exists()
    report_mtime_ns = report_path.stat().st_mtime_ns if report_exists else None
    report_updated = report_exists and (
        prior_mtime_ns is None or report_mtime_ns != prior_mtime_ns
    )
    report_text = ""
    if report_exists:
        report_text = report_path.read_text(encoding="utf-8", errors="replace")

    return {
        "command": command,
        "returncode": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "final_message": final_message,
        "report_path": report_path,
        "report_exists": report_exists,
        "report_updated": report_updated,
        "report_text": report_text,
    }


FAIL_ROW_RE = re.compile(
    r"^\|\s*\d+\s*\|\s*(.*?)\s*\|\s*\*\*(FAIL)\*\*\s*\|",
    re.MULTILINE,
)
FAIL_BRACKET_RE = re.compile(r"^\[FAIL\]\s*(.+)$", re.MULTILINE)


def extract_fail_items(report_text: str) -> list[str]:
    items = [match.group(1).strip() for match in FAIL_ROW_RE.finditer(report_text)]
    if items:
        return items
    return [match.group(1).strip() for match in FAIL_BRACKET_RE.finditer(report_text)]


def stop_block_payload(reason: str) -> str:
    return json.dumps({"decision": "block", "reason": reason})


def nested_invocation() -> bool:
    return os.environ.get(NESTED_ENV_FLAG) == "1"
