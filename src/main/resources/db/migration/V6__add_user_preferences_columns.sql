-- V6: add user work preferences columns (nullable — zero blast radius on existing rows)
-- All new columns are NULLABLE: no data migration needed; existing rows retain NULL.

ALTER TABLE user_profiles ADD COLUMN work_model VARCHAR(20);
ALTER TABLE user_profiles ADD COLUMN salary_floor INTEGER;
ALTER TABLE user_profiles ADD COLUMN locations TEXT;          -- JSON text via StringListConverter
ALTER TABLE user_profiles ADD COLUMN excluded_companies TEXT; -- JSON text via StringListConverter
