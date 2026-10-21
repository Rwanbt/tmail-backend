#!/usr/bin/env python3
"""Privileged, read-only snapshot for manual Email/send incident reconciliation.

Requires PostgreSQL psql and a database role with SELECT only. Never puts
MIME/request content on stdout or in command arguments. No automatic resend.
"""

import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys


KEY = re.compile(r"[0-9a-f]{64}\Z")
SCHEMA = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\Z")
SQL = Path(__file__).with_name("inspect-email-send-outbox.sql")


def supports_private_file_creation(os_name: str) -> bool:
    # Windows mode=0o600 is not an ACL guarantee; do not export sensitive mail
    # data until a native Windows ACL creation path is separately qualified.
    return os_name == "posix"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--submission-key", required=True, help="SHA-256 scoped idempotency fingerprint")
    parser.add_argument("--schema", default="public", help="configured PostgreSQL schema (default: public)")
    parser.add_argument("--output", required=True, type=Path, help="new, private JSON snapshot file (never overwritten)")
    args = parser.parse_args()
    if not KEY.fullmatch(args.submission_key) or not SCHEMA.fullmatch(args.schema):
        parser.error("invalid submission key or schema name")
    if not supports_private_file_creation(os.name):
        print("Secure MIME snapshots require a POSIX host with private file permissions", file=sys.stderr)
        return 1

    try:
        completed = subprocess.run(
            ["psql", "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1",
             "-v", f"submission_key={args.submission_key}",
             "-v", f"schema={args.schema}", "-f", str(SQL)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            check=False, timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired):
        print("PostgreSQL read-only inspection could not run", file=sys.stderr)
        return 1

    if completed.returncode != 0:
        # Do not copy arbitrary psql output into CI/application logs: it can
        # accidentally include an operator query or sensitive values.
        print("PostgreSQL read-only inspection failed; check role/schema access", file=sys.stderr)
        return 1

    try:
        lines = [line for line in completed.stdout.decode("utf-8").splitlines() if line.strip()]
        if len(lines) != 1:
            print("No single keyed durable outbox row was found", file=sys.stderr)
            return 2
        result = json.loads(lines[0])
        if result["submission_key"] != args.submission_key or result["request_payload"] is None:
            raise ValueError("not a keyed durable reservation")
        if result["state"] not in ("prepared", "dispatching", "accepted", "acceptance_unknown",
                                    "rejected_before_publish"):
            raise ValueError("invalid outbox state")
    except (UnicodeError, ValueError, KeyError, TypeError):
        print("Database returned an invalid outbox snapshot", file=sys.stderr)
        return 1

    # Create atomically and exclusively. On Unix, 0600 protects the original
    # request, recipient addresses and MIME, which may contain PII/secrets.
    path = args.output.expanduser()
    created = False
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        created = True
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            if stat.S_IMODE(os.fstat(handle.fileno()).st_mode) & 0o077:
                raise OSError("snapshot file is not private")
            json.dump(result, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
    except OSError:
        if created:
            try:
                path.unlink()
            except OSError:
                pass
        print("Cannot safely create new private snapshot file", file=sys.stderr)
        return 1

    print("Read-only snapshot created; do not replay automatically")
    return 0


if __name__ == "__main__":
    sys.exit(main())
