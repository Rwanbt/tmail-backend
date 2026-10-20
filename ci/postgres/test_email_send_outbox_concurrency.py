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

def run_concurrently(sql, size=12):
    with concurrent.futures.ThreadPoolExecutor(max_workers=size) as pool:
        return list(pool.map(query, [sql] * size))

def main():
    key = "1" * 64
    payload = "2" * 64
    registrations = run_concurrently(
        f"SELECT tmail_register_email_send('{key}','{payload}')")
    assert registrations.count("registered") == 1, registrations
    assert registrations.count("existing") == 11, registrations
    claims = run_concurrently(
        f"SELECT coalesce(tmail_claim_email_send('{key}',0,'node'),-1)")
    assert claims.count("1") == 1, claims
    assert claims.count("-1") == 11, claims
    assert query(f"SELECT state FROM tmail_email_send_outbox WHERE submission_key='{key}'") == "dispatching"
    # Acknowledgement vs lost-ACK racers: only one owner/version transition wins.
    terminal_key = "3" * 64
    query(f"SELECT tmail_register_email_send('{terminal_key}','{payload}')")
    assert query(f"SELECT tmail_claim_email_send('{terminal_key}',0,'node')") == "1"
    terminal_results = concurrent.futures.ThreadPoolExecutor(max_workers=12)
    try:
        tasks = [terminal_results.submit(query,
                 f"SELECT coalesce(tmail_complete_email_send('{terminal_key}',1,'node',{str(i % 2 == 0).lower()}),-1)")
                 for i in range(12)]
        completions = [task.result() for task in tasks]
    finally:
        terminal_results.shutdown(wait=True)
    assert completions.count("2") == 1, completions
    assert completions.count("-1") == 11, completions
    terminal_state = query(f"SELECT state FROM tmail_email_send_outbox WHERE submission_key='{terminal_key}'")
    assert terminal_state in ("accepted", "acceptance_unknown"), terminal_state
    assert query(f"SELECT coalesce(tmail_claim_email_send('{terminal_key}',2,'another-node'),-1)") == "-1"
    print("PASS: 12 concurrent registrations, claims, and terminal ACK races")

if __name__ == "__main__":
    try:
        main()
    except (AssertionError, subprocess.CalledProcessError) as error:
        print(f"FAIL: PostgreSQL concurrent outbox contract: {error}", file=sys.stderr)
        raise
