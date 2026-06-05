-- V5: Enforce one profile per user
-- Cleanup: keep only the most recent profile per user if duplicates exist
DELETE FROM user_profiles a
    USING user_profiles b
WHERE a.id < b.id
  AND a.user_id = b.user_id;

ALTER TABLE user_profiles
    ADD CONSTRAINT uq_user_profiles_user_id UNIQUE (user_id);
