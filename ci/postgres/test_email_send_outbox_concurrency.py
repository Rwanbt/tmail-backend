#!/usr/bin/env python3
"""Exercise concurrent outbox registration/claim against real PostgreSQL."""
import concurrent.futures
import os
import subprocess
import sys

DB = ["psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-h", "localhost",
      "-U", "outbox_test", "-d", "outbox_test"]

def query(sql):
    return subprocess.check_output(DB + ["-c", sql], text=True, env=os.environ).strip()

def concurrent(sql, size=12):
    with concurrent.futures.ThreadPoolExecutor(max_workers=size) as pool:
        return list(pool.map(query, [sql] * size))

def main():
    key = "1" * 64
    payload = "2" * 64
    registrations = concurrent(
        f"SELECT tmail_register_email_send('{key}','{payload}')")
    assert registrations.count("registered") == 1, registrations
    assert registrations.count("existing") == 11, registrations
    claims = concurrent(
        f"SELECT coalesce(tmail_claim_email_send('{key}',0,'node'),-1)")
    assert claims.count("1") == 1, claims
    assert claims.count("-1") == 11, claims
    assert query(f"SELECT state FROM tmail_email_send_outbox WHERE submission_key='{key}'") == "dispatching"
    print("PASS: 12 concurrent registrations and 12 competing CAS claims")

if __name__ == "__main__":
    try:
        main()
    except (AssertionError, subprocess.CalledProcessError) as error:
        print(f"FAIL: PostgreSQL concurrent outbox contract: {error}", file=sys.stderr)
        raise
