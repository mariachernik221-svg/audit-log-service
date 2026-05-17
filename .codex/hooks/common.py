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
CODEX_DIR = HOOKS_DIR.parent
REPO_ROOT = CODEX_DIR.parent
SPEC_ROOT = REPO_ROOT / ".specs"
SPEC_TRACKED_FILES = frozenset({"requirements.md", "design.md", "tasks.md"})
RUNTIME_ROOT = Path.home() / ".codex" / "memories" / "audit-log-service-hooks"
STATE_DIR = RUNTIME_ROOT / "state"
LOG_DIR = RUNTIME_ROOT / "logs"
CAPTURE_LOG_PATH = LOG_DIR / "capture-spec-turn-start.jsonl"
STOP_LOG_PATH = LOG_DIR / "stop-spec-self-eval.jsonl"
NESTED_ENV_FLAG = "AUDIT_SPEC_HOOK_CODEX_NESTED"


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
    if not extra:
        return
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
    report_rel = f".specs/{feature}/eval-report-{stamp}.md"
    return (
        f"Run the repo-local `spec-self-eval` skill for feature `{feature}`.\n\n"
        "Inputs (use these verbatim — do not regenerate, do not substitute):\n"
        f"- feature: {feature}\n"
        f"- current_timestamp: {stamp}\n\n"
        "Requirements:\n"
        f"- Validate only `.specs/{feature}/`.\n"
        "- Follow `.codex/skills/spec-self-eval/SKILL.md` exactly.\n"
        f"- The output report MUST be a NEW file at exactly this path: `{report_rel}`. "
        f"Do not write to or update any other `eval-report-*.md` file. Ignore older eval reports in the folder.\n"
        f"- Use `{stamp}` (verbatim) as the report header timestamp.\n"
        "- The workspace is writable. Do not refuse for sandbox reasons.\n"
        "- Do not edit any spec files or the checklist.\n"
        f"- Final message: one sentence naming the report path `{report_rel}`.\n"
    )


def resolve_codex_executable() -> str:
    found = shutil.which("codex")
    if found:
        return found
    return "codex"


def _scan_eval_reports(feature: str) -> dict[Path, int]:
    feature_dir = SPEC_ROOT / feature
    if not feature_dir.exists():
        return {}
    return {
        path: path.stat().st_mtime_ns
        for path in feature_dir.glob("eval-report-*.md")
        if path.is_file()
    }


def _pick_written_report(feature: str, before: dict[Path, int], expected: Path) -> Path:
    after = _scan_eval_reports(feature)
    new_or_modified = [
        path for path, mtime in after.items()
        if before.get(path) != mtime
    ]
    if not new_or_modified:
        return expected
    if expected in new_or_modified:
        return expected
    new_or_modified.sort(key=lambda p: after[p], reverse=True)
    return new_or_modified[0]


def run_spec_self_eval(feature: str) -> dict[str, Any]:
    ensure_runtime_dirs()

    stamp = report_timestamp()
    report_path = expected_report_path(feature, stamp)
    prior_mtime_ns = report_path.stat().st_mtime_ns if report_path.exists() else None
    before_reports = _scan_eval_reports(feature)
    last_message_path = STATE_DIR / f"codex-exec-{safe_token(feature)}-{stamp}.txt"
    remove_file(last_message_path)

    command = [
        resolve_codex_executable(),
        "exec",
        "--ephemeral",
        "--disable",
        "hooks",
        "--skip-git-repo-check",
        "--dangerously-bypass-approvals-and-sandbox",
        "-C",
        str(REPO_ROOT),
        "-o",
        str(last_message_path),
        build_eval_prompt(feature, stamp),
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

    final_message = ""
    if last_message_path.exists():
        final_message = last_message_path.read_text(encoding="utf-8", errors="replace").strip()
        remove_file(last_message_path)

    report_path = _pick_written_report(feature, before_reports, report_path)
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


BLOCKING_ROW_RE = re.compile(
    r"^\|\s*\d+\s*\|\s*(.*?)\s*\|\s*\*\*(FAIL|WEAK)\*\*\s*\|",
    re.MULTILINE,
)
BLOCKING_BRACKET_RE = re.compile(r"^\[(FAIL|WEAK)\]\s*(.+)$", re.MULTILINE)
BLOCKING_SUMMARY_RE = re.compile(
    r"^\d+\s*/\s*\d+\s+PASS\.\s*(FAIL|WEAK)(?::|\s+on)?\s+item\s+\d+\s*(?::|—|-)\s*(.+)$",
    re.MULTILINE,
)


def extract_blocking_items(report_text: str) -> list[tuple[str, str]]:
    items = [(match.group(2), match.group(1).strip()) for match in BLOCKING_ROW_RE.finditer(report_text)]
    if items:
        return items

    items = [(match.group(1), match.group(2).strip()) for match in BLOCKING_BRACKET_RE.finditer(report_text)]
    if items:
        return items

    return [(match.group(1), match.group(2).strip()) for match in BLOCKING_SUMMARY_RE.finditer(report_text)]


def stop_block_payload(reason: str, message: str | None = None) -> str:
    payload: dict[str, Any] = {"decision": "block", "reason": reason}
    if message:
        payload["systemMessage"] = message
    return json.dumps(payload)


def nested_invocation() -> bool:
    return os.environ.get(NESTED_ENV_FLAG) == "1"
