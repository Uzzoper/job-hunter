-- V1: Create jobs table for storing job listings
CREATE TABLE jobs (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    company     VARCHAR(255) NOT NULL,
    url         VARCHAR(500) NOT NULL UNIQUE,
    description TEXT,
    posted_at   DATE NOT NULL,
    match_score INTEGER,
    created_at  TIMESTAMP DEFAULT NOW()
);
