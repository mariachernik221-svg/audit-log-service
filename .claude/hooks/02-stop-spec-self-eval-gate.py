from __future__ import annotations

import sys

from common import STOP_LOG_PATH
from common import append_log
from common import changed_features
from common import extract_fail_items
from common import nested_invocation
from common import read_hook_payload
from common import read_json
from common import relative_repo_path
from common import remove_file
from common import run_spec_self_eval
from common import snapshot_specs
from common import state_file_path
from common import stop_block_payload
from common import utc_timestamp


def build_failure_reason(feature: str, report_path: str, fail_items: list[str]) -> str:
    lines = [
        f"`spec-self-eval` still has failing checklist items for `.specs/{feature}/`.",
        "",
    ]
    lines.extend(f"[FAIL] {item}" for item in fail_items)
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
        f"`claude -p` exit code: {result['returncode']}",
    ]
    if final_message:
        lines.append(f"Final message: {final_message}")
    if stderr:
        lines.extend(["", "stderr:", stderr])
    elif stdout:
        lines.extend(["", "stdout:", stdout])
    else:
        lines.append("No output was produced by the nested `claude -p` run.")
    return "\n".join(lines)


def main() -> int:
    if nested_invocation():
        return 0

    payload = read_hook_payload()
    session_id = str(payload.get("session_id") or "unknown-session")
    stop_hook_active = bool(payload.get("stop_hook_active") or False)

    if stop_hook_active:
        append_log(
            STOP_LOG_PATH,
            {
                "finished_at": utc_timestamp(),
                "session_id": session_id,
                "outcome": "skipped_stop_hook_active",
            },
        )
        return 0

    state_path = state_file_path(session_id)
    baseline = read_json(state_path)

    if baseline is None:
        append_log(
            STOP_LOG_PATH,
            {
                "finished_at": utc_timestamp(),
                "session_id": session_id,
                "outcome": "skipped_missing_baseline",
            },
        )
        return 0

    before = baseline.get("files", {})
    after = snapshot_specs()
    features = changed_features(before if isinstance(before, dict) else {}, after)
    remove_file(state_path)

    if not features:
        append_log(
            STOP_LOG_PATH,
            {
                "finished_at": utc_timestamp(),
                "session_id": session_id,
                "outcome": "pass_no_spec_changes",
            },
        )
        return 0

    blocking_reasons: list[str] = []
    feature_results: list[dict[str, object]] = []
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

        feature_results.append(
            {
                "feature": feature,
                "outcome": outcome,
                "report_path": report_path,
                "fail_items": fail_items,
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
        },
    )

    if blocking_reasons:
        sys.stdout.write(stop_block_payload("\n\n".join(blocking_reasons)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
