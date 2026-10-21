#!/usr/bin/env python3
"""Real PostgreSQL contract for read-only post-crash Email/send inspection."""

import base64
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[2]
INSPECTOR = (ROOT / "tmail-backend/apps/postgres/src/doc/release-instructions"
             / "inspect_email_send_outbox.py")


def psql(*args):
    result = subprocess.run(
        ["psql", "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1",
         "-h", "127.0.0.1", "-U", "outbox_test", "-d", "outbox_test", *args],
        check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=ROOT,
        text=True,
    )
    return result.stdout.strip()


def main():
    spec = importlib.util.spec_from_file_location("operator_inspector", INSPECTOR)
    inspector = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(inspector)
    assert inspector.supports_private_file_creation("posix")
    assert not inspector.supports_private_file_creation("nt"), "Windows must fail closed without verified ACLs"
    inspection_env = os.environ.copy()
    inspection_env.update(PGHOST="127.0.0.1", PGPORT="5432",
                          PGUSER="outbox_test", PGDATABASE="outbox_test")
    psql("-f", "ci/postgres/email_send_outbox_inspection_fixture.sql")
    before = psql("-c", "SELECT md5(string_agg(row_to_json(t)::text, ':' ORDER BY submission_key)) "
                  "FROM (SELECT * FROM outbox_inspection_contract.tmail_email_send_outbox) t")
    with tempfile.TemporaryDirectory() as directory:
        for key_digit, state, expected_version, expected_mime in [
                ("1", "prepared", 1, False),
                ("2", "dispatching", 1, True),
                ("3", "acceptance_unknown", 2, True),
                ("4", "accepted", 2, True)]:
            path = Path(directory) / f"snapshot-{key_digit}.json"
            args = [
                sys.executable, str(INSPECTOR),
                "--submission-key", key_digit * 64,
                "--schema", "outbox_inspection_contract",
                "--output", str(path),
            ]
            # Separate process + DB connection simulates restart/reconnection
            # without incorrectly claiming that PostgreSQL itself was rebooted.
            result = subprocess.run(args, cwd=ROOT, env=inspection_env, check=False,
                                    text=True, capture_output=True)
            assert result.returncode == 0, result.stderr
            assert path.is_file()
            assert not (stat.S_IMODE(path.stat().st_mode) & 0o077)
            data = json.loads(path.read_text(encoding="utf-8"))
            assert data["submission_key"] == key_digit * 64
            assert data["state"] == state
            assert data["version"] == expected_version
            assert data["request_payload"]
            assert (data["mime_base64"] is not None) == expected_mime
            if expected_mime:
                assert base64.b64decode(data["mime_base64"]) == b"Hello\r\n"
                assert data["recipients_json"] == '["recipient@example.com"]'
                assert data["mail_from"] == "sender@example.com"
            # Private exports cannot be clobbered on a mistaken rerun.
            repeat = subprocess.run(args, cwd=ROOT, env=inspection_env, check=False,
                                    text=True, capture_output=True)
            assert repeat.returncode != 0
            assert json.loads(path.read_text(encoding="utf-8")) == data

        # A legacy row has no durable payload; do not mislabel it as
        # inspectable or infer a synthetic idempotency identity for it.
        for key in ("5" * 64, "6" * 64):
            output = Path(directory) / f"missing-{key[0]}.json"
            result = subprocess.run([
                sys.executable, str(INSPECTOR), "--submission-key", key,
                "--schema", "outbox_inspection_contract", "--output", str(output)],
                cwd=ROOT, env=inspection_env, check=False,
                text=True, capture_output=True)
            assert result.returncode == 2
            assert not output.exists()

        # Injection is rejected before connecting or touching any file.
        invalid = subprocess.run([
            sys.executable, str(INSPECTOR), "--submission-key", "x' OR TRUE --",
            "--schema", "public", "--output", str(Path(directory) / "injected")],
            cwd=ROOT, env=inspection_env, check=False,
            text=True, capture_output=True)
        assert invalid.returncode != 0

    after = psql("-c", "SELECT md5(string_agg(row_to_json(t)::text, ':' ORDER BY submission_key)) "
                 "FROM (SELECT * FROM outbox_inspection_contract.tmail_email_send_outbox) t")
    assert before == after, "operator inspection modified PostgreSQL outbox state"
    print("PASS: four crash-state exports, private outputs, legacy filtering, no DML")


if __name__ == "__main__":
    main()
