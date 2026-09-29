CREATE TABLE profile_ai_provider (
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,
  provider text NOT NULL,

  -- NOTE(prototype): stored in plain text; encrypt with the instance
  -- secret before this leaves the prototype stage.
  api_key text NOT NULL,

  -- models the user enabled for the chat pool, subset of the provider's
  -- live-fetched model list
  enabled_models jsonb NOT NULL DEFAULT '[]',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY (profile_id, provider)
);
