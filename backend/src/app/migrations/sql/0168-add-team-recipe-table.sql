-- A team's own recipes for the design agent: the structure of a common piece
-- of UI, as JSON in the recipe format (app.common.agent.recipe). A team
-- recipe of the name of a built-in one is used in its place.
--
-- `origin` says where it came from: written in the team ('team'), saved from
-- a file by the agent ('saved'), or brought in as a file ('imported');
-- `origin_note` says from which.
CREATE TABLE team_recipe (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  team_id uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE DEFERRABLE,
  created_by uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,

  name text NOT NULL,
  content text NOT NULL,
  origin text NOT NULL DEFAULT 'team',
  origin_note text NULL,
  is_disabled boolean NOT NULL DEFAULT false,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE team_recipe
  ALTER COLUMN content SET STORAGE external;

CREATE UNIQUE INDEX team_recipe__team_id__name__idx ON team_recipe (team_id, name);
