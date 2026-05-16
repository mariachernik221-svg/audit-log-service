from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any

HOOKS_DIR = Path(__file__).resolve().parent
CLAUDE_DIR = HOOKS_DIR.parent
REPO_ROOT = CLAUDE_DIR.parent
SPEC_ROOT = REPO_ROOT / ".specs"
SPEC_TRACKED_FILES = frozenset({"requirements.md", "design.md", "tasks.md"})
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
        raw = sys.stdin.read()
    except Exception:
        return {}
    if not raw.strip():
        return {}
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def emit_status(message: str, **extra: Any) -> None:
    sys.stderr.write(message + "\n")
    payload: dict[str, Any] = {"systemMessage": message, **extra}
    sys.stdout.write(json.dumps(payload))


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
        if path.is_file() and path.name in SPEC_TRACKED_FILES:
            rel_path = path.relative_to(SPEC_ROOT).as_posix()
            snapshot[rel_path] = sha256_file(path)
    return snapshot


def snapshot_specs_with_content() -> dict[str, dict[str, str]]:
    if not SPEC_ROOT.exists():
        return {}

    snapshot: dict[str, dict[str, str]] = {}
    for path in sorted(SPEC_ROOT.rglob("*")):
        if path.is_file() and path.name in SPEC_TRACKED_FILES:
            rel_path = path.relative_to(SPEC_ROOT).as_posix()
            data = path.read_bytes()
            snapshot[rel_path] = {
                "hash": hashlib.sha256(data).hexdigest(),
                "content_b64": base64.b64encode(data).decode("ascii"),
            }
    return snapshot


def hashes_from_content_snapshot(snapshot: dict[str, Any]) -> dict[str, str]:
    out: dict[str, str] = {}
    for path, entry in snapshot.items():
        if isinstance(entry, dict) and "hash" in entry:
            out[path] = str(entry["hash"])
        elif isinstance(entry, str):
            out[path] = entry
    return out


def restore_specs_for_features(
    baseline: dict[str, dict[str, str]],
    features: list[str],
) -> list[str]:
    restored: list[str] = []
    feature_set = set(features)
    baseline_paths = set(baseline.keys())

    for rel_path, entry in baseline.items():
        feature = feature_name_for_snapshot_path(rel_path)
        if feature not in feature_set:
            continue
        if not isinstance(entry, dict) or "content_b64" not in entry:
            continue
        target = SPEC_ROOT / rel_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(base64.b64decode(entry["content_b64"]))
        restored.append(rel_path)

    for path in sorted(SPEC_ROOT.rglob("*")):
        if not path.is_file():
            continue
        if path.name not in SPEC_TRACKED_FILES:
            continue
        rel_path = path.relative_to(SPEC_ROOT).as_posix()
        feature = feature_name_for_snapshot_path(rel_path)
        if feature not in feature_set:
            continue
        if rel_path not in baseline_paths:
            try:
                path.unlink()
                restored.append(f"(removed) {rel_path}")
            except OSError:
                pass

    return sorted(restored)


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


def report_timestamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d_%H-%M-%SZ")


def expected_report_path(feature: str, stamp: str) -> Path:
    return SPEC_ROOT / feature / f"eval-report-{stamp}.md"


def relative_repo_path(path: Path) -> str:
    try:
        return path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def build_eval_prompt(feature: str, stamp: str) -> str:
    return (
        f"Run the repo-local `spec-self-eval` skill for feature `{feature}`.\n\n"
        "Requirements:\n"
        f"- Validate only `.specs/{feature}/`.\n"
        "- Follow `.claude/skills/spec-self-eval/SKILL.md` exactly.\n"
        f"- Use `<current_timestamp>` = `{stamp}` for both the report filename and the report header — do not generate a different value.\n"
        f"- Write the report to `.specs/{feature}/eval-report-{stamp}.md`, overwriting if it exists.\n"
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

    stamp = report_timestamp()
    report_path = expected_report_path(feature, stamp)
    prior_mtime_ns = report_path.stat().st_mtime_ns if report_path.exists() else None

    command = [
        resolve_claude_executable(),
        "-p",
        build_eval_prompt(feature, stamp),
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


def nested_invocation() -> bool:
    return os.environ.get(NESTED_ENV_FLAG) == "1"
