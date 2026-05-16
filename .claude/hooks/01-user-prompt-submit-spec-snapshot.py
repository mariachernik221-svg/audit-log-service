from __future__ import annotations

import sys

from common import CAPTURE_LOG_PATH
from common import append_log
from common import emit_status
from common import nested_invocation
from common import read_hook_payload
from common import snapshot_specs_with_content
from common import state_file_path
from common import utc_timestamp
from common import write_json

def main() -> int:
    if nested_invocation():
        return 0

    sys.stderr.write("[spec-snapshot] started...\n")
    try:
        payload = read_hook_payload()
        session_id = str(payload.get("session_id") or "unknown-session")
        snapshot = snapshot_specs_with_content()
        state_path = state_file_path(session_id)
        captured_at = utc_timestamp()

        write_json(
            state_path,
            {
                "captured_at": captured_at,
                "session_id": session_id,
                "files": snapshot,
            },
        )
        append_log(
            CAPTURE_LOG_PATH,
            {
                "captured_at": captured_at,
                "session_id": session_id,
                "file_count": len(snapshot),
                "state_path": str(state_path),
            },
        )
        emit_status(f"[spec-snapshot] captured {len(snapshot)} file(s) from .specs/")
    except Exception as exc:
        emit_status(f"[spec-snapshot] skipped ({type(exc).__name__}: {exc})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
