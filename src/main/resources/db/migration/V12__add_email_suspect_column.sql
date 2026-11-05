-- LinkedIn posts ingest spec: rows collected from the bot's feed watch carry a
-- contact e-mail that may be a free provider (gmail/hotmail/...); the flag keeps
-- those reviewable instead of silently trusted. docs/specs/linkedin-posts-ingest.md
ALTER TABLE jobs ADD COLUMN email_suspect BOOLEAN NOT NULL DEFAULT 0;
