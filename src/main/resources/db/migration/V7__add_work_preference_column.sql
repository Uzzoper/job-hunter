-- V7: add sealed work-preference column (Remote | Hybrid | Onsite sum type)
-- Additive only: V6 stays valid. Existing V6 columns (work_model, locations)
-- are no longer mapped by the persistence layer after this migration; they
-- remain in the schema untouched. NULL work_preference = preference unknown.
-- Spec: docs/specs/user-preferences.md (sum-type evolution).

ALTER TABLE user_profiles ADD COLUMN work_preference TEXT; -- discriminated JSON via WorkPreferenceConverter