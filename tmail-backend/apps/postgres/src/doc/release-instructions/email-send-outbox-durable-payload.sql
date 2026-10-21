-- Run before deploying PostgreSQL Email/send with durable idempotency keys.
-- Execute with the application's configured PostgreSQL search_path (usually public).
-- This migration preserves every existing outbox row, version, owner and state.
-- A missing table or incompatible existing column is an error.
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE tmail_email_send_outbox
    ADD COLUMN IF NOT EXISTS request_payload text,
    ADD COLUMN IF NOT EXISTS mime_payload bytea,
    ADD COLUMN IF NOT EXISTS mail_from varchar(320),
    ADD COLUMN IF NOT EXISTS recipients_json text,
    ADD COLUMN IF NOT EXISTS auth_user varchar(512),
    ADD COLUMN IF NOT EXISTS submission_id varchar(255);

-- ADD COLUMN IF NOT EXISTS alone would silently accept a wrongly typed column.
DO $$
BEGIN
    IF (SELECT count(*) FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'tmail_email_send_outbox'
          AND (
              (column_name = 'request_payload' AND data_type = 'text')
              OR (column_name = 'mime_payload' AND data_type = 'bytea')
              OR (column_name = 'mail_from' AND data_type = 'character varying' AND character_maximum_length = 320)
              OR (column_name = 'recipients_json' AND data_type = 'text')
              OR (column_name = 'auth_user' AND data_type = 'character varying' AND character_maximum_length = 512)
              OR (column_name = 'submission_id' AND data_type = 'character varying' AND character_maximum_length = 255)
          )) <> 6 THEN
        RAISE EXCEPTION 'Email/send outbox schema has incompatible durable payload columns';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tmail_email_send_outbox'::regclass
           AND conname = 'outbox_dispatch_payload_valid'
    ) THEN
        ALTER TABLE tmail_email_send_outbox
            ADD CONSTRAINT outbox_dispatch_payload_valid CHECK (
                request_payload IS NULL
                OR state = 'prepared'
                OR (
                    mime_payload IS NOT NULL
                    AND mail_from IS NOT NULL
                    AND recipients_json IS NOT NULL
                    AND auth_user IS NOT NULL
                    AND submission_id IS NOT NULL
                )
            );
    END IF;
END
$$;
COMMIT;
