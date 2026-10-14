-- Model endpoints an instance admin registered: an address that speaks the
-- OpenAI-compatible protocol, and the models it serves with what each can
-- do. Users pick from these; nobody but an admin adds an address.
CREATE TABLE ai_endpoint (
  id uuid NOT NULL DEFAULT uuid_generate_v4() PRIMARY KEY,
  name text NOT NULL,
  base_uri text NOT NULL,

  -- capability records, one per model; see agent/src/catalog.js
  models jsonb NOT NULL DEFAULT '[]',

  -- whether a user has to bring a key of their own to use it
  requires_key boolean NOT NULL DEFAULT false,

  created_by uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,
  created_at timestamptz NOT NULL DEFAULT now(),
  modified_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ai_endpoint__name__idx ON ai_endpoint (lower(name));
