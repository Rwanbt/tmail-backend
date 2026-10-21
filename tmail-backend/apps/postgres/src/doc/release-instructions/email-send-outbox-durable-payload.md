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

CI exercises the script against a populated legacy schema, repeats it, checks
that historic states survive unchanged, and verifies the new dispatch
constraint using real PostgreSQL.
