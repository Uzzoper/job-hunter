-- V3: Create users, user_profiles, job_analyses, and link email drafts to users
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    name          VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP DEFAULT NOW()
);

CREATE TABLE user_profiles (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resume_text TEXT NOT NULL,
    skills      TEXT[] NOT NULL,
    tone        VARCHAR(50) DEFAULT 'STARTUP',
    updated_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id)
);

-- Remove single-user match_score from jobs
ALTER TABLE jobs DROP COLUMN IF EXISTS match_score;

-- Create user-scoped job analyses
CREATE TABLE job_analyses (
    id             BIGSERIAL PRIMARY KEY,
    job_id         BIGINT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    user_id        BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    match_score    INTEGER NOT NULL,
    matched_skills TEXT[] NOT NULL,
    missing_skills TEXT[] NOT NULL,
    company_tone   VARCHAR(50) NOT NULL,
    summary        TEXT NOT NULL,
    analyzed_at    TIMESTAMP DEFAULT NOW(),
    UNIQUE(job_id, user_id)
);

-- Link email drafts to users (NOT NULL from the start, with uniqueness)
ALTER TABLE email_drafts ADD COLUMN user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE email_drafts ADD CONSTRAINT uq_email_drafts_job_user UNIQUE (job_id, user_id);
