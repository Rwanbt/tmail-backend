\set ON_ERROR_STOP on
\i ci/postgres/email_send_outbox.sql
INSERT INTO tmail_email_send_outbox(submission_key, payload_digest)
VALUES (repeat('a',64),repeat('b',64));
DO $$
DECLARE v BIGINT;
BEGIN
 SELECT tmail_claim_email_send(repeat('a',64),0,'node-1') INTO v;
 IF v IS DISTINCT FROM 1 THEN RAISE EXCEPTION 'first atomic claim failed'; END IF;
 SELECT tmail_claim_email_send(repeat('a',64),0,'node-2') INTO v;
 IF v IS NOT NULL THEN RAISE EXCEPTION 'stale competing node claimed submission'; END IF;
 SELECT tmail_claim_email_send(repeat('a',64),1,'node-2') INTO v;
 IF v IS NOT NULL THEN RAISE EXCEPTION 'dispatching submission was claimed twice'; END IF;
 UPDATE tmail_email_send_outbox SET state='acceptance_unknown', owner_id=NULL, version=2
 WHERE submission_key=repeat('a',64) AND state='dispatching' AND owner_id='node-1';
 SELECT tmail_claim_email_send(repeat('a',64),2,'node-2') INTO v;
 IF v IS NOT NULL THEN RAISE EXCEPTION 'unknown acceptance became retryable'; END IF;
 IF (SELECT count(*) FROM tmail_email_send_outbox WHERE submission_key=repeat('a',64)) <> 1
 THEN RAISE EXCEPTION 'outbox lost unique persisted submission'; END IF;
END $$;
-- A conflicting payload must not replace the original row.
INSERT INTO tmail_email_send_outbox(submission_key,payload_digest)
VALUES (repeat('a',64),repeat('c',64))
ON CONFLICT (submission_key) DO NOTHING;
DO $$
BEGIN
 IF (SELECT payload_digest FROM tmail_email_send_outbox WHERE submission_key=repeat('a',64))
    IS DISTINCT FROM repeat('b',64)
 THEN RAISE EXCEPTION 'conflicting payload replaced immutable identity'; END IF;
END $$;

-- Functional registration contract: first insert, identical retry, conflict.
DO $$
DECLARE status TEXT;
BEGIN
 SELECT tmail_register_email_send(repeat('d',64), repeat('e',64)) INTO status;
 IF status IS DISTINCT FROM 'registered' THEN RAISE EXCEPTION 'new key not registered'; END IF;
 SELECT tmail_register_email_send(repeat('d',64), repeat('e',64)) INTO status;
 IF status IS DISTINCT FROM 'existing' THEN RAISE EXCEPTION 'identical retry not recognized'; END IF;
 SELECT tmail_register_email_send(repeat('d',64), repeat('f',64)) INTO status;
 IF status IS DISTINCT FROM 'payload_conflict' THEN RAISE EXCEPTION 'conflicting retry accepted'; END IF;
 IF (SELECT payload_digest FROM tmail_email_send_outbox WHERE submission_key=repeat('d',64))
    IS DISTINCT FROM repeat('e',64)
 THEN RAISE EXCEPTION 'registration overwrote original payload'; END IF;
END $$;

-- Durable completion with a competing owner, a stale version and a lost ACK.
DO $$
DECLARE result_version BIGINT;
BEGIN
 INSERT INTO tmail_email_send_outbox(submission_key,payload_digest)
 VALUES (repeat('8',64),repeat('9',64));
 SELECT tmail_claim_email_send(repeat('8',64),0,'node-a') INTO result_version;
 IF result_version IS DISTINCT FROM 1 THEN RAISE EXCEPTION 'claim missing'; END IF;
 SELECT tmail_complete_email_send(repeat('8',64),1,'node-b',true) INTO result_version;
 IF result_version IS NOT NULL THEN RAISE EXCEPTION 'foreign owner accepted mail'; END IF;
 SELECT tmail_complete_email_send(repeat('8',64),0,'node-a',true) INTO result_version;
 IF result_version IS NOT NULL THEN RAISE EXCEPTION 'stale version accepted mail'; END IF;
 SELECT tmail_complete_email_send(repeat('8',64),1,'node-a',false) INTO result_version;
 IF result_version IS DISTINCT FROM 2 THEN RAISE EXCEPTION 'lost ACK not durable'; END IF;
 SELECT tmail_complete_email_send(repeat('8',64),2,'node-a',true) INTO result_version;
 IF result_version IS NOT NULL THEN RAISE EXCEPTION 'uncertain mail overwritten as accepted'; END IF;
 SELECT tmail_claim_email_send(repeat('8',64),2,'node-b') INTO result_version;
 IF result_version IS NOT NULL THEN RAISE EXCEPTION 'uncertain mail became dispatchable'; END IF;
 IF (SELECT state FROM tmail_email_send_outbox WHERE submission_key=repeat('8',64))
   IS DISTINCT FROM 'acceptance_unknown' THEN RAISE EXCEPTION 'uncertainty lost'; END IF;
END $$;
