-- US #14: a skill's per-skill "mode" (suggest/review/autofix) is retired in
-- favour of its reactive behavior (on-call/observer). Rename the column and
-- remap existing rows: the only mode that watched anything was autofix, so it
-- becomes observer; everything else is on-call.
ALTER TABLE profile_skill RENAME COLUMN mode TO reactive;

UPDATE profile_skill
   SET reactive = CASE WHEN reactive = 'autofix' THEN 'observer' ELSE 'on-call' END;
