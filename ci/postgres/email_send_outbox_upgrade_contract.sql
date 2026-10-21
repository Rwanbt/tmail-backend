\set ON_ERROR_STOP on
-- This fixture deliberately uses the pre-upgrade schema and mixed historic states.
CREATE SCHEMA outbox_upgrade;
SET search_path TO outbox_upgrade;
\i ci/postgres/email_send_outbox.sql

INSERT INTO tmail_email_send_outbox(submission_key, payload_digest, owner_id, state, version)
VALUES
    (repeat('a',64), repeat('1',64), NULL, 'prepared', 0),
    (repeat('b',64), repeat('2',64), 'old-node', 'dispatching', 1),
    (repeat('c',64), repeat('3',64), NULL, 'accepted', 2),
    (repeat('d',64), repeat('4',64), NULL, 'acceptance_unknown', 2);

-- Execute twice to prove rerunning the migration is safe.
\i tmail-backend/apps/postgres/src/doc/release-instructions/email-send-outbox-durable-payload.sql
\i tmail-backend/apps/postgres/src/doc/release-instructions/email-send-outbox-durable-payload.sql

DO $$
BEGIN
    IF (SELECT count(*) FROM tmail_email_send_outbox) <> 4 THEN
        RAISE EXCEPTION 'migration lost historic rows';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM tmail_email_send_outbox
                   WHERE submission_key = repeat('b',64)
                   AND payload_digest = repeat('2',64)
                   AND state = 'dispatching' AND owner_id = 'old-node' AND version = 1
                   AND request_payload IS NULL AND mime_payload IS NULL) THEN
        RAISE EXCEPTION 'migration changed a running legacy dispatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM tmail_email_send_outbox
                   WHERE submission_key = repeat('c',64)
                   AND state = 'accepted' AND version = 2
                   AND request_payload IS NULL) THEN
        RAISE EXCEPTION 'migration changed accepted history';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM tmail_email_send_outbox
                   WHERE submission_key = repeat('d',64)
                   AND state = 'acceptance_unknown' AND version = 2) THEN
        RAISE EXCEPTION 'migration made an ambiguous ACK retryable';
    END IF;
    IF (SELECT count(*) FROM information_schema.columns
        WHERE table_schema = 'outbox_upgrade'
          AND table_name = 'tmail_email_send_outbox'
          AND column_name IN ('request_payload','mime_payload','mail_from',
                              'recipients_json','auth_user','submission_id')) <> 6 THEN
        RAISE EXCEPTION 'migration missed durable payload columns';
    END IF;
END
$$;

-- New keyed submissions cannot move into dispatch without complete stored data.
INSERT INTO tmail_email_send_outbox(submission_key, payload_digest, request_payload)
VALUES (repeat('e',64), repeat('5',64), '{"from":"sender@example.com"}');
DO $$
BEGIN
    BEGIN
        UPDATE tmail_email_send_outbox SET state = 'dispatching', owner_id = 'bypass', version = 1
         WHERE submission_key = repeat('e',64);
        RAISE EXCEPTION 'durable reservation was dispatched without MIME';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
END
$$;

UPDATE tmail_email_send_outbox
   SET mime_payload = decode('48656c6c6f', 'hex'),
       mail_from = 'sender@example.com',
       recipients_json = '["recipient@example.com"]',
       auth_user = 'owner@example.com',
       submission_id = 'fixed-submission',
       state = 'dispatching',
       owner_id = 'node-a',
       version = 1
 WHERE submission_key = repeat('e',64) AND version = 0 AND state = 'prepared';
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM tmail_email_send_outbox
                   WHERE submission_key = repeat('e',64)
                   AND state = 'dispatching' AND owner_id = 'node-a'
                   AND encode(mime_payload, 'hex') = '48656c6c6f') THEN
        RAISE EXCEPTION 'complete durable payload could not dispatch';
    END IF;
END
$$;
