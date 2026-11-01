-- The external MCP servers a profile connected to the design agent: tools of
-- other services (a tracker, a document store) the agent can call for them.
CREATE TABLE profile_mcp_server (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,

  -- the namespace of the server's tools: mcp__<name>__<tool>
  name text NOT NULL,
  url text NOT NULL,

  -- the bearer token for the server, when it needs one; encrypted like a
  -- provider key (`enc:v1:<jwe>`), never plain text, never sent to a client
  token text NULL,

  is_enabled boolean NOT NULL DEFAULT true,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX profile_mcp_server__profile_id__name__idx
  ON profile_mcp_server (profile_id, name);
