-- V13: self-hosted bot-only — remove multi-user auth, single profile.
-- Rebuilds affected tables without user_id; preserves job/analysis/draft/project data
-- where possible. users table is dropped (single owner, no accounts).

-- 1. profile (was user_profiles): add name/email, drop user_id + unique constraint
CREATE TABLE profile (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          VARCHAR(255) NOT NULL DEFAULT 'Owner',
    email         VARCHAR(255),
    resume_text   TEXT NOT NULL,
    skills        TEXT NOT NULL,
    tone          VARCHAR(50) DEFAULT 'STARTUP',
    phone         VARCHAR(30),
    contact_email VARCHAR(255),
    portfolio_url VARCHAR(500),
    github_url    VARCHAR(500),
    linkedin_url  VARCHAR(500),
    work_preference TEXT,
    salary_floor  INTEGER,
    excluded_companies TEXT,
    keywords      TEXT,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO profile (id, name, email, resume_text, skills, tone, phone, contact_email,
                     portfolio_url, github_url, linkedin_url, work_preference, salary_floor,
                     excluded_companies, keywords, updated_at)
SELECT up.id,
       COALESCE(u.name, 'Owner'),
       u.email,
       up.resume_text, up.skills, up.tone, up.phone, up.contact_email,
       up.portfolio_url, up.github_url, up.linkedin_url, up.work_preference, up.salary_floor,
       up.excluded_companies, up.keywords, up.updated_at
FROM user_profiles up
LEFT JOIN users u ON u.id = up.user_id
LIMIT 1;

-- 2. job_analyses: drop user_id, unique on job_id
CREATE TABLE job_analyses_new (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id         BIGINT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    match_score    INTEGER NOT NULL,
    matched_skills TEXT NOT NULL,
    missing_skills TEXT NOT NULL,
    company_tone   VARCHAR(50) NOT NULL,
    summary        TEXT NOT NULL,
    analyzed_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_job_analyses_job UNIQUE (job_id)
);
INSERT INTO job_analyses_new (id, job_id, match_score, matched_skills, missing_skills, company_tone, summary, analyzed_at)
SELECT id, job_id, match_score, matched_skills, missing_skills, company_tone, summary, analyzed_at
FROM job_analyses;
DROP TABLE job_analyses;
ALTER TABLE job_analyses_new RENAME TO job_analyses;

-- 3. email_drafts: drop user_id, unique on job_id
CREATE TABLE email_drafts_new (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id       BIGINT NOT NULL REFERENCES jobs(id),
    subject      VARCHAR(255) NOT NULL,
    body         TEXT NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    sent_at      TIMESTAMP,
    recipient_email VARCHAR(255),
    lifecycle_state VARCHAR(30),
    CONSTRAINT uq_email_drafts_job UNIQUE (job_id)
);
INSERT INTO email_drafts_new (id, job_id, subject, body, status, generated_at, sent_at, recipient_email, lifecycle_state)
SELECT id, job_id, subject, body, status, generated_at, sent_at, recipient_email, lifecycle_state
FROM email_drafts;
DROP TABLE email_drafts;
ALTER TABLE email_drafts_new RENAME TO email_drafts;

-- 4. user_projects: drop the FK to users; keep table name + user_id column
--    (value now stores the single profile's id). SQLite can't drop FK easily,
--    so rebuild in place.
CREATE TABLE user_projects_new (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     BIGINT NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    tech_stack  VARCHAR(500) NOT NULL
);
INSERT INTO user_projects_new (id, user_id, name, description, tech_stack)
SELECT id, (SELECT id FROM profile LIMIT 1), name, description, tech_stack FROM user_projects;
DROP TABLE user_projects;
ALTER TABLE user_projects_new RENAME TO user_projects;
CREATE INDEX idx_user_projects_user_id ON user_projects(user_id);

-- 5. Drop old tables
DROP TABLE IF EXISTS user_profiles;
DROP TABLE IF EXISTS users;

-- 6. Indexes
CREATE INDEX IF NOT EXISTS idx_job_analyses_job_id ON job_analyses(job_id);
CREATE INDEX IF NOT EXISTS idx_email_drafts_job_id ON email_drafts(job_id);

-- Send-idempotency backstop (V4): at most one SENT draft per (job_id, recipient_email).
CREATE UNIQUE INDEX IF NOT EXISTS uq_email_drafts_sent_recipient
    ON email_drafts(job_id, recipient_email)
    WHERE status = 'SENT' AND recipient_email IS NOT NULL;
