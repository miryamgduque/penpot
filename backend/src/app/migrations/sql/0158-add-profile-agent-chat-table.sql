CREATE TABLE profile_agent_chat (
  -- client-generated: the panel allocates the id on first save so the
  -- save is a plain idempotent upsert with no insert/update round-trip
  id uuid PRIMARY KEY,

  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,

  -- derived from the first user message; shown in the conversation list
  title text NOT NULL DEFAULT '',

  -- transit-encoded {:messages … :history … :usage …} (see db/tjson). Transit
  -- rather than plain json because the canonical agent history is
  -- keyword-heavy CLJS data that plain json would mangle on the round-trip.
  data jsonb NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE profile_agent_chat
  ALTER COLUMN data SET STORAGE external;

-- the list query: this profile's conversations for one file, newest first
CREATE INDEX profile_agent_chat__profile_id__file_id__idx
  ON profile_agent_chat (profile_id, file_id, updated_at DESC);

-- file deletion cascade needs its own path (the composite above leads
-- with profile_id)
CREATE INDEX profile_agent_chat__file_id__idx
  ON profile_agent_chat (file_id);
