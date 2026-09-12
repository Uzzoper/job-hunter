-- V8: explicit application lifecycle state (issue #56).
-- New column is NULLABLE: non-SENT rows retain NULL (no lifecycle yet).
-- Honest backfill: historical SENT markers carry no evidence, so they map to
-- SUBMITTED — never VERIFIED. Promoting phantoms to verified would repeat the
-- original sin (see ApplicationLifecycle.fromEmailStatus).

ALTER TABLE email_drafts ADD COLUMN lifecycle_state VARCHAR(30);

UPDATE email_drafts SET lifecycle_state = 'SUBMITTED' WHERE status = 'SENT';
