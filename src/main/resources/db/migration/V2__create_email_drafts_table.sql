-- V2: Create email_drafts table for storing generated email drafts
CREATE TABLE email_drafts (
    id           BIGSERIAL PRIMARY KEY,
    job_id       BIGINT NOT NULL REFERENCES jobs(id),
    subject      VARCHAR(255) NOT NULL,
    body         TEXT NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    generated_at TIMESTAMP DEFAULT NOW()
);
