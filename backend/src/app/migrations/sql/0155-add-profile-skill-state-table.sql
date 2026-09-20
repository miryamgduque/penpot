CREATE TABLE profile_skill_state (
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,
  skill text NOT NULL,

  -- NULL file_id = the user's account-wide default for this skill; a set
  -- file_id = the user's override for that one file. Both scopes are private
  -- to the user (per-profile) and are never shared with other collaborators
  -- on the file (contrast design_skill_override, the team baseline).
  file_id uuid NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,

  enabled boolean NOT NULL DEFAULT true,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- One row per (profile, skill) at account scope, plus one per file. NULLs are
-- distinct in a plain unique index, so key on COALESCE(file_id, …) to collapse
-- the account row (file_id NULL) into a single slot; the upsert repeats this
-- expression as its ON CONFLICT target.
CREATE UNIQUE INDEX profile_skill_state__unique__idx
  ON profile_skill_state (profile_id, skill, COALESCE(file_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE INDEX profile_skill_state__file_id__idx ON profile_skill_state (file_id);
