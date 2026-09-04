-- V4: Add recipient_email snapshot to email_drafts and enforce send idempotency
--
-- email_drafts gains a nullable recipient_email column: a snapshot of job.contactEmail
-- taken at generation / send time (issue #27). No backfill is needed — existing rows get
-- NULL, which never participates in the idempotency key.
--
-- The partial unique index guarantees a sender is never contacted twice for the same
-- (job_id, recipient_email) pair. SQLite >= 3.8 supports partial indexes; the WHERE
-- literal must match the Java enum name exactly ('SENT', case-sensitive). Server-side
-- race protection is a last-resort backstop; the application guards are the primary path.
ALTER TABLE email_drafts ADD COLUMN recipient_email VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uq_email_drafts_sent_recipient
    ON email_drafts(job_id, recipient_email) WHERE status = 'SENT';
