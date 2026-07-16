# Phase 05 — The vibes skill + /vibes

**Status:** done

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions)
- [x] Check if any gaps have been filled by other work since plan creation
- [x] Review dependencies are met (Phases 02–04: ask_user + form UI + storage)
- [x] Read relevant source files to confirm assumptions

## Checklist

- [x] Built-in skill `penpot-project-vibes` (new "Setup" category, listed first) in `ask/catalog` (new category
      "Setup" or nearest fit; mode `review`), with a body that drives the
      flow: read_design first (adapt questions to what's already in the
      file), then ONE ask_user interview covering — what to design first /
      primary platform / logistics-or-domain question when relevant /
      overall vibe (multi-select adjectives + Other) / what makes it
      different / audience / how many directions / a name — then synthesize
      a concise design.md (identity, vibe words, audience, priorities,
      voice, do/don't) and save it via set_design_doc, then confirm with a
      short summary and suggest the natural next step
- [x] The body goes in — native `:body` on the catalog entry; `skill-body` serves catalog bodies verbatim, before the aikit+preamble path `aikit_bodies.cljs`-adjacent native storage (it is
      native-born — no reframing preamble; check how `skill-body` resolves
      built-ins and give this one a native body path)
- [x] Slash registry: `/vibes` fronts the skill (`:skill` link, menu dedupes, command hides when the skill is toggled off) sends the skill's trigger message
      (replacing the Phase 01 stub) so routing goes through the normal
      skills index
- [x] ~~Unit test~~ dropped (no-tests mode), body resolves without the aikit
      preamble
- [ ] Lint + typecheck pass
- [ ] **Live end-to-end verify — DEFERRED to post-merge live-test:**
      `/vibes` → interview renders → answers (incl. an "Other…" and a
      "Decide for me") → doc saved (check plugin-data + a collaborator tab)
      → new turn's output demonstrably honors the vibes (e.g. ask for a
      button; palette/tone follows the doc)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent_skills.cljs` — catalog entry +
  native body resolution
- `frontend/src/app/main/data/workspace/slash_commands.cljs` — real `/vibes`
  action
- `frontend/test/frontend_tests/data/agent_skills_test.cljs` — entry/body test

## Notes

- The interview questions live in the skill BODY (prose the model adapts),
  not hardcoded in CLJS — that's the point of the generic widget: the model
  can drop the logistics question for a portfolio site, adapt chips to the
  project, etc. The screenshots are the north star for tone and coverage.
- Keep the doc synthesis inside the turn (agent writes it, saves via tool) —
  no separate `skill-gen`-style completion; the user sees a summary in chat
  and the doc lands on the file in the same turn.
- Model note: interview quality depends on the model following a multi-step
  body; verify on Claude (the demo target), note anything flaky for others.
