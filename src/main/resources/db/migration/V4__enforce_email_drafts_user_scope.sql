-- V4: Enforce per-user email drafts (one draft per job per user)
DELETE FROM email_drafts WHERE user_id IS NULL;

DELETE FROM email_drafts a
    USING email_drafts b
WHERE a.id < b.id
  AND a.job_id = b.job_id
  AND a.user_id = b.user_id;

ALTER TABLE email_drafts
    ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE email_drafts
    ADD CONSTRAINT uq_email_drafts_job_user UNIQUE (job_id, user_id);
