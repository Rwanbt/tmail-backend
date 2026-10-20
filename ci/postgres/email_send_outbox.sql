-- Experimental Email/send outbox storage contract.
-- Not connected to the production dispatcher. A durable queue acceptance
-- protocol and consumer deduplication are still required before enabling it.
CREATE TABLE IF NOT EXISTS tmail_email_send_outbox (
  submission_key CHAR(64) PRIMARY KEY,
  payload_digest CHAR(64) NOT NULL,
  owner_id TEXT,
  state TEXT NOT NULL DEFAULT 'prepared'
    CHECK (state IN ('prepared', 'dispatching', 'acceptance_unknown', 'accepted', 'rejected_before_publish')),
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT outbox_dispatch_owner_consistency CHECK (
    (state = 'dispatching' AND owner_id IS NOT NULL AND length(owner_id) > 0)
    OR (state <> 'dispatching' AND owner_id IS NULL)
  ),
  CONSTRAINT outbox_digest_format CHECK (
    submission_key ~ '^[0-9a-f]{64}$'
    AND payload_digest ~ '^[0-9a-f]{64}$'
  )
);

-- Atomic compare-and-set claim: after commit, exactly one node owns a
-- prepared submission at the expected version. Never reclaim an uncertain
-- or previously dispatching submission merely because a lease expired.
CREATE OR REPLACE FUNCTION tmail_claim_email_send(
  p_key CHAR(64), p_version BIGINT, p_owner TEXT)
RETURNS BIGINT LANGUAGE SQL AS $$
  UPDATE tmail_email_send_outbox
     SET state = 'dispatching', owner_id = p_owner,
         version = version + 1, updated_at = now()
   WHERE submission_key = p_key
     AND version = p_version
     AND state = 'prepared'
     AND p_owner IS NOT NULL AND length(p_owner) > 0
  RETURNING version;
$$;
