-- Per-user on/off override of the design agent for one file. The account
-- setting lives in profile.props (:agent-disabled); a row here is an
-- exception to it for one file, and the most specific setting wins. Only
-- true exceptions are stored: choosing the account's own value for a file
-- deletes its row. Private to the user; collaborators are not affected.
CREATE TABLE profile_agent_state (
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,

  enabled boolean NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY (profile_id, file_id)
);

-- file deletion cascade needs its own path (the primary key leads with
-- profile_id)
CREATE INDEX profile_agent_state__file_id__idx
  ON profile_agent_state (file_id);
