-- The "on-call" reactive behavior is renamed to "on-demand" (clearer that the
-- skill acts only when the user asks). Remap any rows carrying the old value.
UPDATE profile_skill
   SET reactive = 'on-demand'
 WHERE reactive = 'on-call';
