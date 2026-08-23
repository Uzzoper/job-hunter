-- V1: Consolidated baseline schema (SQLite dialect)
-- Replaces the former server-database migrations V1..V8 carrying the final schema.
-- Consolidation is safe per AGENTS.md — those migrations only ever ran on local machines.

CREATE TABLE jobs (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    title         VARCHAR(255) NOT NULL,
    company       VARCHAR(255) NOT NULL,
    url           VARCHAR(500) NOT NULL UNIQUE,
    description   TEXT,
    posted_at     DATE NOT NULL,
    source        VARCHAR(50) NOT NULL,
    contact_email VARCHAR(255),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    email         VARCHAR(255) NOT NULL UNIQUE,
    name          VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_profiles (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resume_text   TEXT NOT NULL,
    skills        TEXT NOT NULL, -- JSON text via StringListConverter (e.g. ["Java","Spring"])
    tone          VARCHAR(50) DEFAULT 'STARTUP',
    phone         VARCHAR(30),
    contact_email VARCHAR(255),
    portfolio_url VARCHAR(500),
    github_url    VARCHAR(500),
    linkedin_url  VARCHAR(500),
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_profiles_user_id UNIQUE (user_id)
);

CREATE TABLE job_analyses (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id         BIGINT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    user_id        BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    match_score    INTEGER NOT NULL,
    matched_skills TEXT NOT NULL, -- JSON text via StringListConverter
    missing_skills TEXT NOT NULL, -- JSON text via StringListConverter
    company_tone   VARCHAR(50) NOT NULL,
    summary        TEXT NOT NULL,
    analyzed_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_job_analyses_job_user UNIQUE (job_id, user_id)
);

CREATE TABLE email_drafts (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id       BIGINT NOT NULL REFERENCES jobs(id),
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subject      VARCHAR(255) NOT NULL,
    body         TEXT NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    sent_at      TIMESTAMP,
    CONSTRAINT uq_email_drafts_job_user UNIQUE (job_id, user_id)
);

CREATE TABLE user_projects (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    tech_stack  VARCHAR(500) NOT NULL
);

CREATE INDEX idx_user_projects_user_id ON user_projects(user_id);
