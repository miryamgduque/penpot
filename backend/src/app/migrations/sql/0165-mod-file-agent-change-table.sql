-- What takes a commit of the agent back: the changes that undo it, kept so a
-- turn can be reverted, and when it was reverted.
ALTER TABLE file_agent_change
  ADD COLUMN undo_changes bytea NULL,
  ADD COLUMN reverted_at timestamptz NULL;

ALTER TABLE file_agent_change
  ALTER COLUMN undo_changes SET STORAGE external;
