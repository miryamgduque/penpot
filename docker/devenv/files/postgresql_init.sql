CREATE DATABASE penpot_test;
CREATE DATABASE penpot_telemetry;

-- Design session recordings live in their own database, isolated from the
-- product tables: it can be dropped, moved or scaled without touching Penpot,
-- and Penpot boots normally when it is absent (recording simply degrades).
-- See PENPOT_SESSIONS_DATABASE_URI and app.migrations/session-migrations.
CREATE DATABASE penpot_sessions;

-- ...and its test counterpart, so the backend suite never writes into the
-- database a running devenv is recording into.
CREATE DATABASE penpot_sessions_test;
