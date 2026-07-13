-- V4: Add source column to jobs table
ALTER TABLE jobs ADD COLUMN source VARCHAR(50) NOT NULL DEFAULT 'unknown';

-- Backfill existing jobs based on URL patterns
UPDATE jobs SET source = 'gupy' WHERE url LIKE '%gupy.com.br%' OR url LIKE '%gupy.io%';
UPDATE jobs SET source = 'infojobs' WHERE url LIKE '%infojobs%';

-- Remove default after backfill
ALTER TABLE jobs ALTER COLUMN source DROP DEFAULT;