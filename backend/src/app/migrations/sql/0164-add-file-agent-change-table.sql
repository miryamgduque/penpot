-- What the design agent changed, commit by commit: which conversation and
-- which turn made it, with which tool. The file's own change log
-- (file_change) cannot answer that: its rows carry no such attribution and
-- are garbage collected an hour after they are written.
CREATE TABLE file_agent_change (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,
  -- who started the turn; the agent acts with that profile's permissions
  profile_id uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,

  -- the conversation as the panel knows it, and the turn the backend opened
  conversation_id text NOT NULL,
  turn_id uuid NOT NULL,

  tool text NOT NULL,
  -- the revision the file was at after this commit
  revn bigint NOT NULL,
  -- the changes as committed (blob encoded, like file_change.changes)
  changes bytea NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE file_agent_change
  ALTER COLUMN changes SET STORAGE external;

-- "what did this turn change" and "what did this conversation change"
CREATE INDEX file_agent_change__file_id__turn_id__idx
  ON file_agent_change (file_id, turn_id, created_at);

CREATE INDEX file_agent_change__file_id__conversation_id__idx
  ON file_agent_change (file_id, conversation_id, created_at);
