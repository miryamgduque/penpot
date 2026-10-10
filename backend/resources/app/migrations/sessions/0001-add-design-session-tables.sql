--- Design session recordings.
---
--- This database is deliberately ISOLATED from Penpot's product tables: it can
--- be dropped, moved or scaled independently, and Penpot boots and runs normally
--- when it is absent. That isolation is why `file_id` and `profile_id` here are
--- plain uuids and NOT foreign keys — nothing can cascade across databases.
---
--- The consequence, accepted deliberately: deleting a file or a profile leaves
--- its recordings orphaned here. For a prototype that is preferable to coupling
--- the two databases; a real deployment would need a reaper task.

CREATE TABLE IF NOT EXISTS design_session (
  id uuid PRIMARY KEY,

  --- plain uuids by design; see the note above
  file_id    uuid NOT NULL,
  profile_id uuid NOT NULL,

  --- the browser tab that recorded this session. session_id is per tab, not per
  --- person, so (profile_id, session_id) is what identifies a recorder.
  session_id uuid NOT NULL,

  started_at  timestamptz NOT NULL DEFAULT now(),
  stopped_at  timestamptz NULL,

  --- why the recording ended: manual | event-cap | time-cap | file-closed.
  --- NULL while still recording. A cap must never look like a clean stop — the
  --- critique reasons from this.
  stop_reason text NULL,

  --- how many raw commits the client's bounded buffer let go. Non-zero means the
  --- session is not a complete raw record, and says so rather than implying it.
  raw_dropped bigint NOT NULL DEFAULT 0,

  --- the semantic timeline: transit-encoded vector of events. Raw ops are
  --- deliberately NOT stored — they are ephemeral by design decision, kept only
  --- client-side for the life of the recording.
  events jsonb NOT NULL DEFAULT '[]',

  --- an agent critique of this session, once one has been produced. Stored so it
  --- can be re-read without re-spending tokens.
  review text NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE design_session
  ALTER COLUMN events SET STORAGE external;

--- the list query: newest sessions for one file
CREATE INDEX IF NOT EXISTS design_session__file_id__started_at__idx
    ON design_session (file_id, started_at DESC);

--- "what have I recorded lately", across files
CREATE INDEX IF NOT EXISTS design_session__profile_id__started_at__idx
    ON design_session (profile_id, started_at DESC);
