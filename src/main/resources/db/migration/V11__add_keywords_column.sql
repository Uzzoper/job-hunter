-- V11: add keywords column for editable search keywords (issue #79 phase 1)
-- Additive only: NULL = unset. Stored as TEXT with JSON list via StringListConverter.
ALTER TABLE user_profiles ADD COLUMN keywords TEXT;
