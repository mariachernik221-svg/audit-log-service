from __future__ import annotations

from common import CAPTURE_LOG_PATH
from common import append_log
from common import read_hook_payload
from common import snapshot_specs
from common import state_file_path
from common import utc_timestamp
from common import write_json


def main() -> int:
    payload = read_hook_payload()
    session_id = str(payload.get("session_id") or "unknown-session")
    turn_id = str(payload.get("turn_id") or "unknown-turn")
    snapshot = snapshot_specs()
    state_path = state_file_path(session_id, turn_id)

    write_json(
        state_path,
        {
            "captured_at": utc_timestamp(),
            "session_id": session_id,
            "turn_id": turn_id,
            "files": snapshot,
        },
    )
    append_log(
        CAPTURE_LOG_PATH,
        {
            "captured_at": utc_timestamp(),
            "session_id": session_id,
            "turn_id": turn_id,
            "file_count": len(snapshot),
            "state_path": str(state_path),
        },
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
