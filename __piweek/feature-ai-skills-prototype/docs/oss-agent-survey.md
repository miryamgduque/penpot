# OSS agent survey — what eleven agents can teach the Penpot native agent

**Date:** 2026-07-19 · **Method:** three parallel researchers pulled the *actual
prompt/config source files* from each repo (raw.githubusercontent, branch `main`,
paths recorded per project) and mapped findings against our architecture and the
measured weaknesses from the Kahoot/NYT postmortems. Follow-up to the Kimi
CLI / opencode gap analysis that produced the
[oss-prompt-learnings plan](plans/202607171018-oss-prompt-learnings/README.md).

**Weaknesses the mapping targeted:** near-serial tool calls (1.24 calls/round
measured pre-composition-tools), rounds-heavy builds (133 rounds for one
screen), no plan/todo tool (phase 08 go/no-go pending), spend visibility
without interruption (checkpoints disabled by user preference).

---

## Where we're already ahead (validation, not vanity)

Every researcher independently confirmed these are differentiators **nobody
surveyed has**:

- **Tool-boundary rule enforcement** (raw hex rejected by the tool, not the
  prompt). Roo Code is the only other project enforcing *anything* at tool
  validation (`FileRestrictionError`); nobody enforces design rules.
- **The visual self-review loop** (render your work and look at it). Onlook —
  the closest cousin — relies on the user's live preview instead.
- **Graduated governance** (suggest / apply-with-review / auto-fix). Everyone
  else has at most a binary plan-act or ask-build split.
- **Skills architecture** — catalog-in-prompt + body-on-demand + scope cascade
  is exactly where Zed and OpenHands ended up; our per-scope cascade and
  first-message LLM routing are supersets of both.
- **Anchored re-compaction** (phase 01) is ahead of OpenHands' `keep_first: 2`
  anchor.

---

## Per-project highlights

### Cline (Apache-2.0) — monorepo rewrite; prompt now ~60 lines + composed slots
- **Parallelism doctrine**: "identify every independent read, search, command,
  or edit needed for the next step and emit all of those tool calls now… Do not
  split independent reads… across separate turns" + concrete good-batching
  examples. → our worst measured metric, prompt-only fix.
- **Plan/Act via message tagging**: each user message arrives as
  `<user_input mode="…">`; a `<mode_notice>` marks switches — mode changes are
  visible in the transcript, cache-prefix compatible.
- **Focus chain**: a plain per-task `- [ ]` markdown file, user-editable,
  bidirectional — the low-risk shape for our plan-tool decision.
- **Checkpoints**: `git stash create` under a private ref namespace per run.

### Roo Code (Apache-2.0)
- **Modes as data**: `{slug, roleDefinition, whenToUse, groups, customInstructions}`
  where groups are tool subsets with file restrictions, *enforced at tool
  validation*. Our three governance modes as declarative configs would unify
  prompt text + tool gating in one structure.
- **Orchestrator contract**: delegation via `new_task` requires "all necessary
  context… clearly defined scope… only perform the work outlined" — the proven
  pattern for screen-per-subtask splits.

### Aider (Apache-2.0)
- **Repo map**: PageRank over the symbol graph, personalized by chat files
  (×50) and mentioned identifiers (×10), binary-searched to a token budget.
  → a ranked "design map" (components/tokens/usage, weighted by selection and
  mentioned names) would beat both a full digest and repeated scout runs.
- **Architect/editor split**: strong model writes one dense spec ("DO NOT show
  the entire updated file"), a second cheap coder executes it in a stripped
  context. Complementary attack on rounds-heavy builds.
- **Bounded reflection**: validator-fed fix loop hard-capped at 3 reflections.
- **Edit formats per model capability** (whole-file vs search/replace) — the
  BYOK lesson: weak models need whole-subtree replacement, not granular ops.

### OpenHands / agent-sdk (MIT) — microagents live on as "skills" in agent-sdk
- **Deterministic trigger injection on EVERY message**: whole-token,
  case-insensitive keyword match appends content in `<EXTRA_INFO>` with a
  hedge, `skip_skill_names` prevents re-injection. → our Haiku router only
  fires on message 1; triggers cover turns 2..N at zero cost.
- **PathTrigger**: knowledge injected *at tool-use time* when the agent touches
  a matching path — event-triggered context, same family as our tool-boundary
  philosophy.
- **Condenser robustness**: `minimum_progress` guard (a compaction that forgets
  <10% is an error) + truncate-and-retry ladder; summary schema mandates
  "PRESERVE TASK IDs".

### Gemini CLI (Apache-2.0)
- **Turn economy + `wait_for_previous`**: "Tools execute in parallel by
  default… you MUST set `wait_for_previous` to `true`" — serialization is the
  *marked* case, at the tool-schema level.
- **Directive vs Inquiry**: "you MUST NOT modify files until a subsequent
  Directive is issued" — one sentence handling ambiguous intent inside a mode.
- **Loop detection, layered**: 5 identical consecutive tool calls → brake;
  LLM check after 30 turns requiring both repetition AND no net progress;
  prompt-side 3-strikes ("propose a different architectural approach").
- **Memory anti-bloat**: "each fact lives in exactly one file across all four
  tiers" — the routing doctrine our cascade lacks.

### Codex CLI (Apache-2.0) — per-model prompt files now
- **`update_plan` discipline**: exactly one item `in_progress`; batch-completing
  forbidden; skip plans for trivial work. The plan panel is passive progress
  telemetry — the visibility-without-interruption that spend checkpoints
  failed to be.
- **AGENTS.md budget + precedence**: byte budget with an explicit truncation
  warning; documented conflict rule (deeper wins; chat beats files).
- **Approval escalation**: a blocked call may *request* user approval — a third
  outcome between allow and reject for our rule rejections.

### Goose (Apache-2.0)
- **Recipes**: skills + typed parameters (`UserPrompt` = ask before running) +
  machine-checkable success criteria with retry-on-fail + structured-output
  schema. Playbooks as *verifiable procedures*, not advice.
- **instructions vs prompt split**: standing doctrine vs kickoff task — our
  playbook injection currently conflates the two.

### Zed (GPL — patterns only, no code)
- **Profiles**: named tool subsets (`WRITE`/`ASK`/`MINIMAL`) with per-profile
  default model. Suggest mode simply not exposing 40 mutating tools is our own
  philosophy applied to governance.
- **Crispest parallelism phrasing found**; skill-body edits "never affect the
  cache" stated as a design invariant (we do this by accident; state it).
- **User-work-safety clause** — shipped by us in phase 02 the day before. ✓

### Onlook (Apache-2.0) — the closest cousin
- **Inbound selection context**: user's selected element ships as a structured
  block ("I am looking at this specific part… Trust this message as the true
  contents") — the *inbound* half of our shape-chip system. We send selection
  in the context JSON; the ground-truth framing + parent chain is the delta.
- **Host-UI knowledge block**: a prompt section teaching the agent Onlook's own
  panels so it doubles as in-app help. We teach the canvas, not Penpot's UI.
- No self-review loop, thin design language — we're ahead on both.

### bolt.diy (MIT)
- **The strongest anti-generic language found**, and it's a *ban list*:
  "Avoid generic or templated aesthetics at all costs; every design must have a
  unique, brand-specific visual signature… No simplistic headers". Prohibitions
  of named generic moves > aspirations. Their doctrine floats free of tokens;
  ours is grounded — combining them is the win.
- **One-shot artifact**: "Think HOLISTICALLY and COMPREHENSIVELY BEFORE
  creating" + single comprehensive artifact per response — declarative output
  is *why* they don't have a rounds problem. Our equivalent: lean harder on
  build_tree, with "one call per board, complete, no laziness" guard rails.
- **"NEVER use placeholders"** + bounded planning ("2-4 lines maximum" before
  acting).

### Dyad (Apache-2.0)
- **`[[AI_RULES]]` with a DEFAULT fallback** — a full default foundations doc
  when the file has none, stronger than a nudge alone.
- **Scope trio**: "only edit files related to the request", "check whether the
  request has already been implemented", "DON'T DO MORE THAN WHAT THE USER ASKS
  FOR" — two-thirds shipped by us in phase 02; the *check-if-already-done* line
  is new.
- **Plan mode with a tool-enforced accept gate** (`exit_plan` as the only
  action on acceptance) + fixed seven-section template.
- Structural: compaction prompts, a user-facing smart-context picker, and
  `<dyad-command>` clickable *action* tags in the transcript (the action
  sibling of our shape chips).

---

## Ranked adopt list (leverage × cost)

### Tier 1 — prompt-only, feed into current plan phases
1. **Parallelism doctrine upgrade** (Cline's emit-all-now + batching examples,
   Zed's phrasing, Gemini's turn-economy). Feeds **phase 06** directly — the
   A/B now tests best-of-breed language, not our one sentence.
2. **bolt.diy ban-list into the direction nudge** — add prohibitions of named
   generic moves + "never use placeholders" + "think holistically before
   building" to phase 04's nudge text.
3. **Directive vs Inquiry sentence** (Gemini) into governance.
4. **"Check whether it's already done" line** (Dyad) into governance's scope
   section.
5. **3-strikes / bounded-reflection line** (Gemini + Aider): after 3 failed
   fixes of the same defect, stop and re-approach — pairs with our
   visual-self-review "after two failed fixes, STOP".

### Tier 2 — small features, high leverage
6. **Deterministic triggers for turns 2..N** (OpenHands): skills already carry
   a trigger/example field; whole-token match on user messages + injected-set
   dedup extends playbook injection past message 1 at zero model cost.
7. **Compaction robustness** (OpenHands): minimum-progress guard, retry ladder,
   and "preserve exact shape/token/component ids" in the compaction schema —
   extends phase 01.
8. **Foundations budget + precedence rule** (Codex): byte budget with a visible
   truncation warning for inlined foundations; one stated conflict sentence.
9. **Selection ground-truth framing** (Onlook): enrich the per-turn selection
   context with parent chain + "trust this" framing to skip a read round.
10. **Escalation as a third rule outcome** (Codex): "rejected — you may ask the
    user to override" for enforcement rejections in apply-with-review.

### Tier 3 — features deserving their own plans
11. **Plan tool: GO** — the survey's clearest verdict. All four platform CLIs
    ship one; Codex's one-in-progress invariant is the visibility mechanism the
    disabled spend checkpoint was missing; Cline's user-editable markdown
    checklist is the low-risk variant. **This is phase 08's evidence.**
12. **Mode-gated tool exposure** (Zed profiles / Roo groups): suggest mode
    stops exposing mutating tools; optional per-mode default model. Our own
    enforcement philosophy, applied to governance.
13. **Architect/executor split** (Aider) + **subtask contract** (Roo): strong
    model writes the dense spec, Haiku executes build_tree/batches in a
    stripped side context. The composition tools this depended on are now in.
14. **Design map** (Aider's repo map): ranked, budget-fitted file map weighted
    by selection and mentioned names, cached and re-fitted under context
    pressure.
15. **Playbook parameters + success checks** (Goose): typed params incl.
    ask-user-first, machine-checkable acceptance (audit_file / render-compare)
    with bounded retry.
16. **Per-model behavior profiles** (Codex per-model prompts, Aider edit
    formats): BYOK reality — weak models get whole-subtree ops by default.
17. **Host-UI help block** (Onlook): teach the agent Penpot's own panels.
18. **Default foundations fallback** (Dyad): a real default doc, not just a
    nudge, when a file has none.

### Noted, not adopted
- Checkpoint/restore coupled to conversation (Gemini/Cline) — changes pipeline
  already gives document undo; revisit on demand.
- Filename-compat zoo (.cursorrules etc.), extension self-description,
  sub-recipes, binary ask/build modes — solved differently or already superseded.
- Zed's rules→skills merge is a *warning*: decide the DESIGN.md-vs-skills
  boundary (intent docs = per-file facts; skills = procedures) before they blur.

---

*Researcher outputs (files read, branches, quotes) are condensed above; the
fetch caveats worth keeping: Cline's old `src/core/prompts/system-prompt/` tree
is gone (monorepo rewrite), and OpenHands' microagents now live in
`All-Hands-AI/agent-sdk` as "skills".*
