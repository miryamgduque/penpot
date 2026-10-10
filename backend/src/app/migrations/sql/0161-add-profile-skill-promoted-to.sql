-- The light link (US #12): a promoted personal skill points at the team_skill it
-- became. A non-null value also marks the personal copy inactive for its owner.
ALTER TABLE profile_skill
  ADD COLUMN promoted_to uuid NULL REFERENCES team_skill(id) ON DELETE SET NULL DEFERRABLE;
