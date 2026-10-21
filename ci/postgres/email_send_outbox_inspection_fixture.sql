\set ON_ERROR_STOP on
CREATE SCHEMA outbox_inspection_contract;
SET search_path TO outbox_inspection_contract;
\i ci/postgres/email_send_outbox.sql
\i tmail-backend/apps/postgres/src/doc/release-instructions/email-send-outbox-durable-payload.sql

-- Reconstructed persistent rows after app restart or ambiguous broker ACK.
-- No outbox claim or delivery operation is allowed while inspecting them.
INSERT INTO tmail_email_send_outbox
    (submission_key, payload_digest, request_payload, state, version,
     owner_id, mime_payload, mail_from, recipients_json, auth_user, submission_id)
VALUES
    (repeat('1',64), repeat('a',64), '{"subject":"crash-before-stage"}',
     'prepared', 1, NULL, NULL, NULL, NULL, NULL, NULL),
    (repeat('2',64), repeat('b',64), '{"subject":"maybe-in-queue"}',
     'dispatching', 1, 'unreachable-node', decode('48656c6c6f0d0a', 'hex'),
     'sender@example.com', '["recipient@example.com"]',
     'sender@example.com', 'original-submission'),
    (repeat('3',64), repeat('c',64), '{"subject":"ack-lost"}',
     'acceptance_unknown', 2, NULL, decode('48656c6c6f0d0a', 'hex'),
     'sender@example.com', '["recipient@example.com"]',
     'sender@example.com', 'original-submission-2'),
    (repeat('4',64), repeat('d',64), '{"subject":"accepted"}',
     'accepted', 2, NULL, decode('48656c6c6f0d0a', 'hex'),
     'sender@example.com', '["recipient@example.com"]',
     'sender@example.com', 'original-submission-3'),
    (repeat('5',64), repeat('e',64), NULL,
     'prepared', 0, NULL, NULL, NULL, NULL, NULL, NULL);
