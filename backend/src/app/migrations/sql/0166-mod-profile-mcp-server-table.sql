-- The tools of a server its owner switched off, as a JSON list of the
-- server's own tool names: the agent is never offered them.
ALTER TABLE profile_mcp_server
  ADD COLUMN disabled_tools text NULL;
