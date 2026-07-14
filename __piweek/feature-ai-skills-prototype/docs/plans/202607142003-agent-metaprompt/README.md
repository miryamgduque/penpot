<!-- TRANSIENT — part of the __piweek/ team scratch. Delete before the PR is finalized. -->

# Agent chat metaprompt — validate the harness model, then fix what it finds

**Status:** doing
**Created:** 2026-07-14
**Apps:** `frontend`
**User story:** [US #26 — Agent chat metaprompt](https://tree.taiga.io/project/miryam-all-in-penpot/us/26)
**Depends on:** [Port the `ai-skills` chat agent to native CLJS](../202607132113-port-ai-skills-to-cljs/) (done) — this plan reworks the metaprompt that plan scaffolded.
**Related:** [Built-in skills catalog](../completed/202607141430-builtin-skills-catalog/) (Miryam, US #7) — owns the catalog + Skills tab this plan splits.

## Context

US #26 asks how agentic IDEs (Cursor, Claude Code, Codex) embed rules and knowledge in a
metaprompt, and how to do the same in our chat. We now have a full native agent, so the
question is not theoretical: we can **measure** our harness against the model instead of
arguing about it.

### The hypothesis being tested

Every agent harness converges on three layers around the model, and knowledge reaches it
through three separate channels:

1. **Instructions** (system prompt) — behavioural guidance. Not raw tool schemas as prose.
2. **Tools** — a structured API channel (`tools` param), not prose.
3. **Skills / rules** — progressive disclosure: name + description always in context (cheap),
   the full body loads only when relevant.

Plus two cross-cutting claims:

- **Prompt layering for caching** — static content first (system → tool defs → project
  context), volatile content last. Providers cache the stable prefix at ~10% of fresh-token
  cost. *Corollary: don't mutate rules/config mid-session — it invalidates the largest
  cached prefix.*
- **Design agents must see** — unlike a code agent, the model needs a **rendered image** of
  what it just produced, re-fed after each edit, or it goes blind and hallucinates confidence.

### Why we can actually test this

The Phase 09 spend meter already reports **`% cached`** per conversation. That is a live
instrument for the layering hypothesis — no new telemetry needed to get a first signal.
`audit_file` is the instrument for the token-first hypothesis: it counts violations the
agent's own output introduced.

### What reading the code already tells us (to be confirmed by measurement)

- **Suspected defect — volatile content inside the cached prefix.** `ai-panel/send-message`
  rebuilds the system prompt every turn via `agent/build-system-prompt`, which embeds the
  current `{file, page, selection}` JSON *inside* the very block that carries the Anthropic
  `cache_control: ephemeral` marker. Changing the selection should therefore rewrite the
  cached prefix and force a full re-read of system + tool defs. Phase 01 measures it, Phase 02
  fixes it. (We observed 97% cached across turns where the selection never changed — the
  hypothesis predicts that collapses the moment it does.)
- **Progressive disclosure is a stub.** The routing index is in the prompt, but
  `get_design_skills` returns catalog **metadata only** — there are no bodies to load.
- **Part of the catalog is not user-facing knowledge at all.** The aikit corpus carries six
  *shared docs* (`modes-and-policies`, `naming-conventions`, `tokens-schema`,
  `state-management`, `penpot-mcp-tool-reference`, `plugin-api-gotchas`) alongside the ten
  workflow skills. Two are actively wrong for the native agent: `penpot-mcp-tool-reference`
  describes an MCP path the embedded chat does not use, and `plugin-api-gotchas` describes a
  plugin API our native tools never touch.

### Decisions locked in with the user (2026-07-14)

1. **Measure, then fix.** Each phase runs its experiment *and* implements the improvement it
   justifies, so the hypothesis and its fix land as one reviewable unit — with the meter
   proving the fix worked.
2. **Inner knowledge vs. user-facing skills.** Some existing skills become **inner
   knowledge**: reworked (no MCP mentions), folded into the metaprompt as always-on, and
   **not** exposed in the enable/disable UI. The rest stay user-facing, toggleable skills.
   This is the always-loaded ↔ load-on-demand axis applied to our own corpus.
3. **All four hypotheses are in scope**: layering/caching, progressive disclosure (bodies),
   token/component-first bias, and the agent-must-see (render + re-feed) claim.

## Phases

1. [Phase 01 — Measure the layering](./done-phase-01-measure-layering.md) — the
   selection-change experiment. ✅ **done — hypothesis CONFIRMED**: stable conversation caches
   at 96%, changing the selection drops it to **0% every turn** (~9× the per-turn cost).
2. [Phase 02 — Fix the layering](./done-phase-02-fix-layering.md) — volatile context out of
   the cached prefix. ✅ **done — fixed**: selection changes went from **0% → 95/92/89% cached**
   (~8.3× cheaper per turn), with the agent still correctly reading the selection.
3. [Phase 03 — Inner knowledge vs. user-facing skills](./done-phase-03-inner-knowledge.md) —
   ✅ **done**: an always-on layer (governance + naming + native-tool notes) with **0 MCP and 0
   plugin-API references**, invisible to the Skills tab and non-toggleable. Costs ~+$0.0006/turn
   on opus (re-read at 0.1×) and the agent demonstrably follows it.
4. [Phase 04 — Skill bodies on demand](./done-phase-04-skill-bodies.md) — ✅ **done**: a
   generated, section-stripped corpus (−36%) served on a named fetch. **The index does cause a
   fetch**, and fixing the index to carry the skill's `name` cut a wasted round (3→2 requests).
   The body earns its keep only where it states what the model cannot know (workflow order,
   checkpoints) — on well-known domains it adds nothing measurable.
5. [Phase 05 — Per-skill load policy](./done-phase-05-load-policy.md) — ⛔ **dropped, with
   reasons.** All four of `always | index | gated | off` turned out redundant, default, moot, or
   already shipped by US #8. The field would be a second mechanism disagreeing with the ones we
   have. No code; the decision is the deliverable.
6. [Phase 06 — Token/component-first bias](./done-phase-06-token-bias.md) — ✅ **done, hypothesis
   refuted as measured**: **0 → 0** violations either way. Tokens are used because `read_design`
   makes them *visible*, not because the layer advocates them. The layer's real effect is real
   and consistent (`h3`/`p` vs "Heading"/"Body", 4/4 runs) but **`audit_file` is structurally
   blind to it** — it scores the floor, not the house style. The instrument, not the layer, is
   the finding.
7. [Phase 07 — The agent must see](./todo-phase-07-render-refeed.md) — `render_region` +
   re-feed after edits; measure the quality delta. Biggest build, gated on 01–06.

## Acceptance Criteria

- The layering hypothesis is **settled with numbers**, not opinion: a documented before/after
  of `% cached` across a selection change.
- Volatile per-turn context no longer sits inside the cached prefix.
- The metaprompt carries a small, always-on **inner knowledge** layer that contains no MCP or
  plugin-API references, and none of it appears in the Skills enable/disable UI.
- `get_design_skills` returns real skill bodies, and a skill's playbook demonstrably changes
  the agent's behaviour.
- The token/component-first claim is measured with `audit_file`, on vs. off.
- The see/re-feed claim is either demonstrated with a render loop or explicitly deferred with
  a reason.
- `clj-kondo`, `cljfmt`, and `shadow-cljs compile main` stay clean; each phase is verified
  live in the devenv.

## Carried forward (not done)

These are open, and named here so they are not lost in a `done-` file:

- **Measure per-skill body value** (≥3 runs × body-on/off, per skill). Phase 04 found the
  accessibility body cost ~1,250 tokens and changed nothing measurable while the foundations body
  alone produced the phased workflow — but that was n=1. Any body that earns nothing should be
  dropped from the generated corpus. Inherited from Phase 05, not reached in Phase 06.
- **Find an instrument that can see house-style adherence.** Phase 06's central finding is that
  `audit_file` scores the floor (no default names, no raw hex) and is blind to the ceiling (which
  semantic vocabulary, which token tier). Scoring that needs a judge model or a convention-aware
  linter. Without one, we cannot honestly claim the skills layer improves quality — only that it
  changes output in ways a human can see.
- **Re-run Experiment G against a token-less fixture.** With tokens visible the agent never
  attempts a raw hex, so enforcement never fires and G is untestable. The interesting case is a
  brief needing a colour no token covers.

## Open questions / risks

- **Is a native render even reachable?** Phase 07 assumes we can export a board/shape to PNG
  from CLJS (the plugin API could; the internal exporter is a different surface). If that
  proves expensive, Phase 07 becomes its own story rather than bloating this plan.
- **Cache measurement is provider-dependent.** Anthropic reports
  `cache_read_input_tokens` / `cache_creation_input_tokens`; OpenAI-compatible providers only
  report `cached_tokens` (no write count). Experiments run on Claude models.
- **Miryam owns the catalog + Skills tab.** Phase 03 changes what appears there — coordinate
  before landing, since inner knowledge disappearing from the toggle list is a visible change
  to her US #7 surface.
- **A 5-minute cache TTL** means an idle gap between experiment turns can look like a cache
  miss. Experiments must run turns back-to-back and record wall-clock.
