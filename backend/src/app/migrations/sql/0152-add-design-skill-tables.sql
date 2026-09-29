CREATE TABLE design_skill (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  team_id uuid NULL REFERENCES team(id) ON DELETE CASCADE DEFERRABLE,

  name text NOT NULL,
  kind text NOT NULL DEFAULT 'skill',
  enforcement text NOT NULL DEFAULT 'advisory',
  is_mandatory boolean NOT NULL DEFAULT false,
  trigger_on text NULL,
  description text NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',
  is_enabled boolean NOT NULL DEFAULT true,
  origin text NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE design_skill
  ALTER COLUMN body SET STORAGE external,
  ALTER COLUMN description SET STORAGE external;

CREATE INDEX design_skill__team_id__idx ON design_skill (team_id);
CREATE UNIQUE INDEX design_skill__team_id__name__idx ON design_skill (team_id, name) WHERE team_id IS NOT NULL;
CREATE UNIQUE INDEX design_skill__app__name__idx ON design_skill (name) WHERE team_id IS NULL;

CREATE TABLE design_skill_override (
  team_id uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE DEFERRABLE,
  skill_name text NOT NULL,
  is_enabled boolean NOT NULL DEFAULT false,

  PRIMARY KEY (team_id, skill_name)
);
