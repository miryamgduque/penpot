-- The model a profile chose for the agent's side jobs: summaries, scouting,
-- matching. It is one model of one connected provider, so it is kept on that
-- provider's row and no other row of the profile has one.
ALTER TABLE profile_ai_provider
  ADD COLUMN helper_model text NULL;
