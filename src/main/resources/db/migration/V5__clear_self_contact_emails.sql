-- V5: Clear self-match contact emails
-- A registered user's own email must never be kept as a job contact email
-- (job #256 had the owner's personal email attributed as the company contact).
-- This clears any existing rows so the normalizer/enricher self-match guard applies
-- from this migration forward.
-- trim() mirrors the guard's normalization (trim + lowercase) for parity.
UPDATE jobs
SET contact_email = NULL
WHERE lower(trim(contact_email)) IN (SELECT lower(trim(email)) FROM users);