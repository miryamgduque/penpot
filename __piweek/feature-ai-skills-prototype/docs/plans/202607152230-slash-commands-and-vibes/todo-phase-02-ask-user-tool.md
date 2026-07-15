# Phase 02 — ask_user tool plumbing

**Status:** todo

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Review dependencies are met (none on Phase 01 — independent)
- [ ] Read relevant source files to confirm assumptions (`run-turn`'s
      `run-tool`, `send-message`'s cancel path, `execute-tool`)

## Checklist

- [ ] Write/update tests: question-schema validation, answers→tool-result
      encoding, pending-form state transitions (pure parts)
- [ ] Tool spec `ask_user` in `agent-tools/tool-specs`: input is
      `{title?, questions: [{id, question, hint?, type: "single"|"multi"|"text",
      options?: [string…], allow_other?, allow_decide?, optional?}]}` —
      description teaches the model when to interview vs. just ask in prose,
      and to keep it to ONE ask_user call per interview (≤ ~8 questions)
- [ ] `execute-tool "ask_user"`: validate the schema up front (invalid input
      errors immediately, naming the problem — same philosophy as the variant
      tools); store `{:questions … :answer-subject …}` under
      `[:ai-panel <file-id> :pending-form]`; return an rx observable created
      over an `rx/subject` that emits once when the form is submitted
- [ ] Submit event (`dwaip/submit-form answers`): pushes the answers into the
      subject, records a compact answers summary in the transcript, clears
      `:pending-form`
- [ ] Cancel safety: the observable's teardown (unsubscribe on
      `cancel-turn`'s `take-until`) clears `:pending-form`, so a cancelled
      turn never leaves a zombie form; the existing `cancel-history`
      machinery already synthesizes the errored tool_result
- [ ] Tool result: `{answers: {qid: value|values|text}}` with `"__decide__"`
      for "Decide for me" and other-text carried verbatim — documented in the
      tool description so the model reads it back reliably
- [ ] Busy semantics: turn stays busy while the form is pending (it is — the
      tool is mid-flight); verify the composer's existing gating is coherent
      (typing allowed, send blocked)
- [ ] Lint + typecheck pass
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `ask_user` spec +
  execution
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — `:pending-form`
  state, `submit-form`, cleanup on cancel/clear-chat
- `frontend/src/app/main/refs.cljs` — `ai-panel-pending-form` ref
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — schema/encoding
  tests

## Notes

- Design principle: the turn loop (`run-turn`) is untouched. `ask_user` is
  just a slow tool; the pending form is presentation state beside the
  transcript. This also means the canonical history stays well-formed
  (tool_use + tool_result pairs) with zero new persistence work.
- The subject must be module-level or stored outside app-db serialization
  concerns (an rx subject is not data) — keep it in a module atom keyed by
  file-id, with only the renderable questions in app-db.
- `clear-chat` is disabled while busy, and a pending form means busy — so no
  special interaction there, but assert it in review.
- 32-round cap: an interview costs one round — no risk.
