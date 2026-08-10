-- Backfill contact_email for existing jobs
-- Extracts the first valid email from title/description
-- Ignores noreply/donotreply/apply and placeholder domains
--
-- Usage: psql -h localhost -U your_db_user -d job_hunter -f docs/scripts/backfill-contact-emails.sql

WITH candidates AS (
    SELECT
        id,
        (regexp_matches(
            COALESCE(NULLIF(title, ''), '') || ' ' || COALESCE(NULLIF(description, ''), ''),
            '[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}'
        ))[1] AS email
    FROM jobs
    WHERE contact_email IS NULL
)
UPDATE jobs j
SET contact_email = c.email
FROM candidates c
WHERE j.id = c.id
  AND c.email IS NOT NULL
  AND LOWER(c.email) NOT LIKE 'noreply@%'
  AND LOWER(c.email) NOT LIKE 'donotreply@%'
  AND LOWER(c.email) NOT LIKE 'no-reply@%'
  AND LOWER(c.email) NOT LIKE 'apply@%'
  AND LOWER(SPLIT_PART(c.email, '@', 2)) NOT IN (
      'example.com',
      'exemplo.com',
      'test.com',
      'domain.com',
      'yourdomain.com',
      'seuemail.com'
  );
