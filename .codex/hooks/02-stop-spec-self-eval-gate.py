from __future__ import annotations

import sys

from common import STOP_LOG_PATH
from common import append_log
from common import changed_features
from common import emit_status
from common import extract_blocking_items
from common import hashes_from_content_snapshot
from common import nested_invocation
from common import read_hook_payload
from common import read_json
from common import relative_repo_path
from common import remove_file
from common import restore_specs_for_features
from common import run_spec_self_eval
from common import snapshot_specs
from common import state_file_path
from common import stop_block_payload
from common import utc_timestamp
from common import write_json

MAX_RETRIES = 3


def build_failure_reason(
    feature: str, report_path: str, blocking_items: list[tuple[str, str]]
) -> str:
    lines = [
        f"`spec-self-eval` still has blocking checklist items for `.specs/{feature}/`.",
        "",
    ]
    lines.extend(f"[{status}] {item}" for status, item in blocking_items)
    lines.extend(
        [
            "",
            f"Report: `{report_path}`",
            "Fix the spec files, then try closing the turn again.",
        ]
    )
    return "\n".join(lines)


def build_exec_error_reason(feature: str, result: dict[str, object]) -> str:
    report_path = result["report_path"]
    stdout = str(result["stdout"]).strip()
    stderr = str(result["stderr"]).strip()
    final_message = str(result["final_message"]).strip()
    lines = [
        f"Could not complete `spec-self-eval` for `.specs/{feature}/`.",
        f"Expected report: `{relative_repo_path(report_path)}`",
        f"`codex exec` exit code: {result['returncode']}",
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
    if nested_invocation():
        return 0

    sys.stderr.write("[spec-self-eval-gate] started...\n")
    payload = read_hook_payload()
    session_id = str(payload.get("session_id") or "unknown-session")
    stop_hook_active = bool(payload.get("stop_hook_active") or False)

    state_path = state_file_path(session_id)
    baseline = read_json(state_path)

    if baseline is None:
        append_log(
            STOP_LOG_PATH,
            {
                "finished_at": utc_timestamp(),
                "session_id": session_id,
                "outcome": "skipped_missing_baseline",
                "stop_hook_active": stop_hook_active,
            },
        )
        emit_status("[spec-self-eval-gate] skipped (no baseline snapshot)")
        return 0

    retry_count = int(baseline.get("retry_count", 0))
    raw_files = baseline.get("files", {})
    baseline_files = raw_files if isinstance(raw_files, dict) else {}
    before_hashes = hashes_from_content_snapshot(baseline_files)
    after = snapshot_specs()
    features = changed_features(before_hashes, after)

    if retry_count >= MAX_RETRIES:
        restored = restore_specs_for_features(baseline_files, features)
        remove_file(state_path)
        append_log(
            STOP_LOG_PATH,
            {
                "finished_at": utc_timestamp(),
                "session_id": session_id,
                "outcome": "reverted_max_retries",
                "retry_count": retry_count,
                "features": features,
                "restored": restored,
                "stop_hook_active": stop_hook_active,
            },
        )
        emit_status(
            f"[spec-self-eval-gate] max retries {MAX_RETRIES} reached — "
            f"reverted {len(restored)} spec file(s) to baseline"
        )
        return 0

    if not features:
        remove_file(state_path)
        append_log(
            STOP_LOG_PATH,
            {
                "finished_at": utc_timestamp(),
                "session_id": session_id,
                "outcome": "pass_no_spec_changes",
            },
        )
        emit_status("[spec-self-eval-gate] pass (no spec changes)")
        return 0

    blocking_reasons: list[str] = []
    feature_results: list[dict[str, object]] = []
    for feature in features:
        result = run_spec_self_eval(feature)
        report_path = relative_repo_path(result["report_path"])
        blocking_items = extract_blocking_items(str(result["report_text"]))
        outcome = "pass"

        if int(result["returncode"]) != 0 or not bool(result["report_exists"]):
            outcome = "exec_error"
            blocking_reasons.append(build_exec_error_reason(feature, result))
        elif blocking_items:
            outcome = "fail" if any(status == "FAIL" for status, _ in blocking_items) else "weak"
            blocking_reasons.append(build_failure_reason(feature, report_path, blocking_items))

        feature_results.append(
            {
                "feature": feature,
                "outcome": outcome,
                "report_path": report_path,
                "blocking_items": [{"status": status, "item": item} for status, item in blocking_items],
                "returncode": result["returncode"],
            }
        )

    append_log(
        STOP_LOG_PATH,
        {
            "finished_at": utc_timestamp(),
            "session_id": session_id,
            "features": features,
            "feature_results": feature_results,
            "outcome": "blocked" if blocking_reasons else "pass",
            "retry_count": retry_count,
        },
    )

    if blocking_reasons:
        new_count = retry_count + 1
        baseline["retry_count"] = new_count
        baseline["awaiting_continuation"] = True
        write_json(state_path, baseline)
        message = (
            f"[spec-self-eval-gate] BLOCK ({len(features)} feature(s): "
            f"{', '.join(features)}, retry {new_count}/{MAX_RETRIES})"
        )
        sys.stderr.write(message + "\n")
        sys.stdout.write(stop_block_payload("\n\n".join(blocking_reasons), message))
    else:
        remove_file(state_path)
        emit_status(
            f"[spec-self-eval-gate] pass ({len(features)} feature(s): {', '.join(features)})"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
