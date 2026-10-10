-- Team-promoted skills (US #12): a personal profile_skill promoted to a team
-- lands here, shaped the same way (label/category/reactive/body) so it merges
-- into every member's agent catalog by name. Team-side management is story #13.
CREATE TABLE team_skill (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  team_id uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE DEFERRABLE,
  promoted_by uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,
  source_profile_skill_id uuid NULL REFERENCES profile_skill(id) ON DELETE SET NULL DEFERRABLE,

  name text NOT NULL,
  label text NOT NULL,
  category text NOT NULL,
  reactive text NOT NULL,
  trigger_on text NULL,
  description text NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE team_skill
  ALTER COLUMN body SET STORAGE external,
  ALTER COLUMN description SET STORAGE external;

CREATE UNIQUE INDEX team_skill__team_id__name__idx ON team_skill (team_id, name);
CREATE INDEX team_skill__team_id__idx ON team_skill (team_id);
