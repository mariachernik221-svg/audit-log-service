from __future__ import annotations

import json
import os
import subprocess
import sys
import traceback
from datetime import datetime, timezone
from pathlib import Path


def _diag(message: str) -> None:
    for path in (
        Path.home() / ".codex" / "memories" / "audit-log-service-hooks" / "logs" / "hook-diag.log",
        Path(os.environ.get("TEMP", r"C:\Windows\Temp")) / "audit-log-hook-diag.log",
    ):
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("a", encoding="utf-8") as f:
                f.write(message + "\n")
            return
        except Exception:
            continue


_diag(f"[stop-invoked] ts={datetime.now(timezone.utc).isoformat()} cwd={os.getcwd()} argv={sys.argv}")
try:
    _raw_stdin = sys.stdin.read()
    _diag(f"[stop-stdin] len={len(_raw_stdin)} content={_raw_stdin[:1000]!r}")
except Exception as _e:
    _diag(f"[stop-stdin-error] {_e}")
    _raw_stdin = ""

try:
    from common import REPO_ROOT
    from common import SPEC_ROOT
    from common import STATE_DIR
    from common import STOP_LOG_PATH
    from common import append_log
    from common import ensure_runtime_dirs
    from common import extract_fail_items
    from common import feature_name_for_snapshot_path
    from common import read_hook_payload
    from common import relative_repo_path
    from common import remove_file
    from common import run_spec_self_eval
    from common import safe_token
    from common import stop_block_payload
    from common import utc_timestamp
    _IMPORT_OK = True
except Exception:
    _diag(f"[stop-import-error]\n{traceback.format_exc()}")
    _IMPORT_OK = False

MAX_RETRIES = 3
_diag(f"[stop-imports-ok={_IMPORT_OK}]")


def emit_success() -> None:
    sys.stdout.write(json.dumps({
        "continue": True,
        "hookSpecificOutput": {"hookEventName": "Stop"},
    }))
    sys.stdout.flush()


def git_changed_spec_paths() -> list[str]:
    """Return relative-to-SPEC_ROOT paths under .specs/ that differ from HEAD or are untracked."""
    try:
        tracked = subprocess.run(
            ["git", "diff", "--name-only", "HEAD", "--", ".specs"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
        )
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard", "--", ".specs"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
        )
    except Exception:
        return []
    paths = set()
    for line in (tracked.stdout or "").splitlines() + (untracked.stdout or "").splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith(".specs/") or line.startswith(".specs\\"):
            rel = line[len(".specs/"):].replace("\\", "/")
        else:
            rel = line.replace("\\", "/")
        # Skip generated eval reports
        if Path(rel).name.startswith("eval-report-"):
            continue
        paths.add(rel)
    return sorted(paths)


def features_from_paths(paths: list[str]) -> list[str]:
    features = set()
    for p in paths:
        feat = feature_name_for_snapshot_path(p)
        if feat:
            features.add(feat)
    return sorted(features)


def retry_counter_path(session_id: str, turn_id: str) -> Path:
    return STATE_DIR / f"retry__{safe_token(session_id)}__{safe_token(turn_id)}.json"


def read_retry_count(path: Path) -> int:
    try:
        return int(json.loads(path.read_text(encoding="utf-8")).get("count", 0))
    except Exception:
        return 0


def write_retry_count(path: Path, count: int) -> None:
    ensure_runtime_dirs()
    path.write_text(json.dumps({"count": count}), encoding="utf-8")


def git_restore_specs_for_features(features: list[str]) -> list[str]:
    restored: list[str] = []
    for feat in features:
        target = f".specs/{feat}"
        try:
            subprocess.run(
                ["git", "checkout", "HEAD", "--", target],
                cwd=REPO_ROOT,
                capture_output=True,
                text=True,
                timeout=30,
            )
            subprocess.run(
                ["git", "clean", "-fd", "--", target],
                cwd=REPO_ROOT,
                capture_output=True,
                text=True,
                timeout=30,
            )
            restored.append(target)
        except Exception:
            pass
    return restored


def build_failure_reason(feature: str, report_path: str, fail_items: list[str]) -> str:
    lines = [
        f"`spec-self-eval` still has failing checklist items for `.specs/{feature}/`.",
        "",
    ]
    lines.extend(f"[FAIL] {item}" for item in fail_items)
    lines.extend([
        "",
        f"Report: `{report_path}`",
        "Fix the spec files, then try closing the turn again.",
    ])
    return "\n".join(lines)


def build_exec_error_reason(feature: str, result: dict) -> str:
    report_path = result["report_path"]
    stdout = str(result.get("stdout", "")).strip()
    stderr = str(result.get("stderr", "")).strip()
    final_message = str(result.get("final_message", "")).strip()
    lines = [
        f"Could not complete `spec-self-eval` for `.specs/{feature}/`.",
        f"Expected report: `{relative_repo_path(report_path)}`",
        f"`codex exec` exit code: {result.get('returncode')}",
    ]
    if final_message:
        lines.append(f"Final message: {final_message}")
    if stderr:
        lines.extend(["", "stderr:", stderr])
    elif stdout:
        lines.extend(["", "stdout:", stdout])
    else:
        lines.append("No output was produced by the nested `codex exec` run.")
    return "\n".join(lines)


def main() -> int:
    if not _IMPORT_OK:
        emit_success()
        return 0
    _diag("[stop-main-start]")
    try:
        try:
            payload = json.loads(_raw_stdin) if _raw_stdin.strip() else {}
            if not isinstance(payload, dict):
                payload = {}
        except Exception:
            payload = {}
        session_id = str(payload.get("session_id") or "unknown-session")
        turn_id = str(payload.get("turn_id") or "unknown-turn")

        paths = git_changed_spec_paths()
        features = features_from_paths(paths)
        counter_path = retry_counter_path(session_id, turn_id)
        retry_count = read_retry_count(counter_path)

        if retry_count >= MAX_RETRIES:
            restored = git_restore_specs_for_features(features)
            remove_file(counter_path)
            append_log(
                STOP_LOG_PATH,
                {
                    "finished_at": utc_timestamp(),
                    "session_id": session_id,
                    "turn_id": turn_id,
                    "outcome": "reverted_max_retries",
                    "retry_count": retry_count,
                    "features": features,
                    "restored": restored,
                },
            )
            emit_success()
            return 0

        if not features:
            remove_file(counter_path)
            append_log(
                STOP_LOG_PATH,
                {
                    "finished_at": utc_timestamp(),
                    "session_id": session_id,
                    "turn_id": turn_id,
                    "outcome": "pass_no_spec_changes",
                },
            )
            emit_success()
            return 0

        blocking_reasons: list[str] = []
        feature_results: list[dict] = []
        for feature in features:
            result = run_spec_self_eval(feature)
            report_path = relative_repo_path(result["report_path"])
            fail_items = extract_fail_items(str(result["report_text"]))
            outcome = "pass"

            if int(result["returncode"]) != 0 or not bool(result["report_exists"]):
                outcome = "exec_error"
                blocking_reasons.append(build_exec_error_reason(feature, result))
            elif fail_items:
                outcome = "fail"
                blocking_reasons.append(build_failure_reason(feature, report_path, fail_items))

            feature_results.append({
                "feature": feature,
                "outcome": outcome,
                "report_path": report_path,
                "fail_items": fail_items,
                "returncode": result["returncode"],
            })

        append_log(
            STOP_LOG_PATH,
            {
                "finished_at": utc_timestamp(),
                "session_id": session_id,
                "turn_id": turn_id,
                "features": features,
                "feature_results": feature_results,
                "outcome": "blocked" if blocking_reasons else "pass",
                "retry_count": retry_count,
            },
        )

        if blocking_reasons:
            write_retry_count(counter_path, retry_count + 1)
            sys.stdout.write(stop_block_payload("\n\n".join(blocking_reasons)))
            sys.stdout.flush()
        else:
            remove_file(counter_path)
            emit_success()
    except Exception as exc:
        try:
            append_log(
                STOP_LOG_PATH,
                {
                    "finished_at": utc_timestamp(),
                    "outcome": "error",
                    "error_type": type(exc).__name__,
                    "error_message": str(exc),
                    "traceback": traceback.format_exc(),
                },
            )
        except Exception:
            pass
        emit_success()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
