-- Per-member arrival acknowledgement for promoted team skills (US #52). Absence
-- of a row means "not yet seen" (arrived); a row means the member dismissed the
-- arrival notice or opened the skill's detail from it. Deliberately separate
-- from profile_skill_state (US #8/#9), which is file-scoped and skill-name-keyed
-- and means "is this skill on/off for me" — a different axis from "have I
-- acknowledged this promotion."
CREATE TABLE team_skill_seen (
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,
  team_skill_id uuid NOT NULL REFERENCES team_skill(id) ON DELETE CASCADE DEFERRABLE,
  seen_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (profile_id, team_skill_id)
);
