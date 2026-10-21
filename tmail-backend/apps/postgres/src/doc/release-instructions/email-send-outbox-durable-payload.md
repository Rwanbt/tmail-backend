# PostgreSQL Email/send durable payload upgrade (PR #11)

This optional Email/send integration uses the existing
`tmail_email_send_outbox` table. James creates a missing table at startup,
but `CREATE TABLE IF NOT EXISTS` **does not add columns to an older table**.
For an existing PostgreSQL deployment, execute the additive migration
`email-send-outbox-durable-payload.sql` before enabling client
`idempotencyKey` traffic.

1. Back up the existing database and pause schema changes. Use a migration
   role authorized to alter `tmail_email_send_outbox`.
2. Connect to the same database and schema as `postgres.properties`
   (`database.schema`; usually `public`). Set the session `search_path`
   explicitly if it differs from the default.
3. Run `psql -X -v ON_ERROR_STOP=1 -f email-send-outbox-durable-payload.sql`.
   The transaction uses a five-second lock timeout and fails without partial
   changes when a lock is unavailable or an existing column has the wrong type.
4. Verify the six nullable columns and the
   `outbox_dispatch_payload_valid` constraint before starting new server
   nodes. Repeat the script safely if it was previously applied.

The migration preserves every existing submission key, payload fingerprint,
owner, version and status. Historical rows have NULL durable payload columns.
Only a new, opt-in keyed submission is expected to persist the original
request, MIME bytes and resolved envelope before publication. A keyed row
cannot enter `dispatching` without the MIME and envelope fields.

Reconciliation remains manual for interrupted publication: **never infer that
RabbitMQ rejected a message from a missing acknowledgement, and never
automatically replay `dispatching` or `acceptance_unknown` rows**. A crash
between initial reservation and MIME staging can leave the sender copy and a
`prepared` row. Treat that state as requiring operator inspection. Existing
clients without an idempotency key still use the legacy path; distributed
Cassandra/RabbitMQ applications are not covered by this change. The database
contains unencrypted MIME payloads unless storage encryption is configured;
review access, backups and retention before enabling.

### Conservative crash-window quarantine on replay

If the client retries the same *keyed* request after 30 minutes of outbox
inactivity, the PostgreSQL server atomically fences the previous request:

- A fully staged `dispatching` row becomes `acceptance_unknown`, loses its
  owner and increments its CAS version. Any late completion by the old owner
  fails the CAS.
- A `prepared` row with version 0 remains `prepared` but advances to version 1.
  This prevents the original process from later invoking the version-0
  `stageAndClaim`, even if it has appended a sender copy. Preserving the
  `prepared` state avoids inventing missing MIME and keeps the existing
  complete-MIME database constraint valid.

This uses the **database** clock; a fresh claim refreshes `updated_at`. The
retry still receives an unknown outcome, never a second append or queue
publication. Recent claims, already terminal rows and legacy hash-only
registrations are untouched. Fenced prepared rows cannot be automatically
recovered or replayed.

This is a fail-closed classification and fencing mechanism, **not successful
delivery recovery**: broker acceptance and the sender mailbox copy must still
be reconciled by an operator. A long-running original publisher may finish
after quarantine, but it cannot convert the quarantined row to `accepted`;
operators must not interpret the unknown state as guaranteed non-delivery.

CI exercises the script against a populated legacy schema, repeats it, checks
that historic states survive unchanged, and verifies the new dispatch
constraint using real PostgreSQL.

### Privileged read-only reconciliation export

After a crash or missing broker acknowledgement, an operator can inspect
the exact **keyed** reservation without changing its state or republishing.
Run `inspect_email_send_outbox.py` from a host with PostgreSQL's `psql`
installed, using a dedicated database login with **SELECT** on
`tmail_email_send_outbox` and **USAGE** on the application schema. No table
write permissions or JMAP endpoint are required.

Set `PGHOST`, `PGPORT`, `PGDATABASE`, and `PGUSER` to your PostgreSQL
connection settings and provide credentials through a protected `.pgpass`
file or the deployment's existing secret manager. Do not put passwords in
shell arguments or copy the generated JSON to shared CI logs. The input
is the 64-character *server fingerprint* of the scoped key, not the raw
`idempotencyKey` sent by the client. The server computes that fingerprint
as SHA-256 of UTF-8 `authenticated_username + NUL + idempotencyKey`.

```sh
python3 inspect_email_send_outbox.py \
  --submission-key '<64 lowercase hex fingerprint>' \
  --schema public \
  --output /restricted/incidents/email-send-incident.json
```

The output file must **not already exist**. The script uses a PostgreSQL
`REPEATABLE READ, READ ONLY` transaction and creates the snapshot with
mode 0600 on Unix and refuses to write if the resulting file is group/world
accessible. **Windows hosts are currently refused**, because POSIX file
modes do not establish private Windows ACLs. Run from a trusted Linux host
or secure Linux container instead. It never prints MIME or request data to
standard output.
The JSON includes request, original MIME as base64, resolved envelope,
current state/version/owner and timestamps. Treat it as sensitive mail
content; restrict access, retention and backups.

For `prepared` with no MIME, check the sender mailbox and the original
request; the absence of MIME does not prove that no sender copy was appended.
For `dispatching` and `acceptance_unknown`, compare the submission ID and
MIME with queue/broker and delivered-mail evidence. Even `accepted` reflects
the application's recorded enqueue outcome, not verified recipient delivery.
**Never automatically resend from this export or assert exactly-once.**
Unkeyed legacy requests have no durable identity and intentionally produce
no export from this tool.
