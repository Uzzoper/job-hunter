-- V2: add company_website column and email/website indexes
-- Part of the email-enrichment spec (P1+P2): companyWebsite capture + hasEmail query index.

ALTER TABLE jobs ADD COLUMN company_website VARCHAR(512);

CREATE INDEX idx_jobs_contact_email ON jobs(contact_email);
CREATE INDEX idx_jobs_company_website ON jobs(company_website);