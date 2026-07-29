-- V6: Add sent_at column to email_drafts table
ALTER TABLE email_drafts ADD COLUMN sent_at TIMESTAMP;
