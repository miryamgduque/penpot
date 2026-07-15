CREATE TABLE profile_skill (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,

  name text NOT NULL,
  label text NOT NULL,
  category text NOT NULL,
  mode text NOT NULL,
  trigger_on text NULL,
  description text NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',
  is_enabled boolean NOT NULL DEFAULT true,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE profile_skill
  ALTER COLUMN body SET STORAGE external,
  ALTER COLUMN description SET STORAGE external;

CREATE UNIQUE INDEX profile_skill__profile_id__name__idx ON profile_skill (profile_id, name);
CREATE INDEX profile_skill__profile_id__idx ON profile_skill (profile_id);
