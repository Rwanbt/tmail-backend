\set ON_ERROR_STOP on
-- psql supplies schema and submission_key as safely quoted variables.
-- No DML, ownership mutation or broker publication is permitted here.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL search_path TO :"schema";
SELECT row_to_json(snapshot)::text
FROM (
    SELECT submission_key, payload_digest, state, version, owner_id,
           request_payload, encode(mime_payload, 'base64') AS mime_base64,
           mail_from, recipients_json, auth_user, submission_id,
           created_at, updated_at
    FROM tmail_email_send_outbox
    WHERE submission_key = :'submission_key'
      AND request_payload IS NOT NULL
) AS snapshot;
COMMIT;
