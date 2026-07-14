# Phase 03 — Inner knowledge vs. user-facing skills

**Status:** done

## Goal

Apply the always-loaded ↔ load-on-demand axis to our own corpus. Some of what we inherited is
not a *skill a user chooses* — it is knowledge the agent must always have to be correct at all.
That becomes **inner knowledge**: reworked for the native agent (no MCP, no plugin API), folded
into the metaprompt as always-on, and **absent from the enable/disable UI**. The rest stay
user-facing skills the user can toggle.

## Before Start

- [x] Re-read the aikit corpus split: six shared docs (`modes-and-policies`,
      `naming-conventions`, `tokens-schema`, `state-management`, `penpot-mcp-tool-reference`,
      `plugin-api-gotchas`) vs. the ten workflow skills in `agent-skills/catalog`
- [x] Re-read `agent/build-system-prompt` — the operating modes are already distilled inline;
      this phase generalises that one-off into a real layer
- [x] Re-read `agent-skills/system-prompt-section` (routing index) and `catalog-manifest`
- [x] **Coordinate with Miryam** — *not needed: the shared docs were never in her catalog; verified the tab is unchanged.* Originally: — inner knowledge disappearing from the Skills tab is a
      visible change to her US #7 surface

## The split (as implemented)

> **Correction to the plan:** the aikit corpus has **4** shared docs, not 6. `tokens-schema`
> and `state-management` appear as cross-references *inside* other bodies, not as importable
> entries — the earlier table invented them. The real corpus is 15 entries: 11 workflow skills
> (the 10 in our catalog + `penpot-router`) and 4 shared docs.

| Item | Size | Disposition | Why |
|---|---|---|---|
| `modes-and-policies` | 5.5k | **Split** — governance half → inner knowledge | Governance must always hold: a user must not be able to switch off "ask before destructive changes". But only ~half the doc is governance; its **fill-policy** and **token-modes** sections are procedural *and* written against `applyToken()` / `clone()` / `createComponent()` / `theme.addSet()` — plugin-API calls we do not have. Those stay for Phase 04 skill bodies, after rework. |
| `naming-conventions` | 2.6k | **Inner knowledge** (minus one section) | Shapes every response and is cheap. Dropped its run-identifier section (`RUN_ID` + `setSharedPluginData` — plugin-data specific) and its dead cross-refs to `tokens-schema.json` / `state-management.md`. Notably it already states our `layer-naming` audit rule ("never ship `Rectangle 12`"). |
| `penpot-mcp-tool-reference` | 4.7k | **Dropped from the embedded path** | Describes the MCP door the embedded chat does not use. Still served to external MCP agents — this is about which corpus *our* prompt is built from. |
| `plugin-api-gotchas` | 10.9k | **Replaced**, not reworked | 8 plugin-API references; almost all of it describes quirks that do not exist for our tools. Replaced with a short **native-tool notes** section written from what the port actually taught us. |
| The 10 workflow skills | — | **User-facing** (unchanged) | Genuine choices: audits, build playbooks, auto-fix. |

**The coordination risk the plan flagged evaporated:** the shared docs were **never in the CLJS
catalog**, so nothing disappears from Miryam's Skills tab. This phase is purely additive to the
prompt. Verified live — the tab still renders the same 3 groups and 10 skills.

## Checklist

- [x] Confirm the split above with the user (the `state-management` and `plugin-api-gotchas`
      rows are the uncertain ones)
- [x] Introduce an **inner-knowledge layer** in `agent-skills` distinct from `catalog`: always
      loaded, no `:enabled` flag, never returned by `catalog-manifest`
- [x] Rework each retained doc for the native agent: **no MCP mentions, no plugin-API mentions**
- [x] `build-system-prompt` composes: persona → operating modes → inner knowledge → skills
      routing index. Stable content only (Phase 02 moved context out).
- [x] Verify the Skills tab shows only user-facing skills — inner knowledge is invisible and
      untoggleable
- [x] **Measure the cost of the layer.** Always-on means always paid (albeit cached). Record
      the system-prompt token count before/after; if it balloons, that is an argument for
      Phase 05's `gated` policy rather than for cutting the knowledge.
- [x] **Check whether growth pushes us past the cacheable minimum** — it did NOT; see Cost table. — see the note below; if it
      does, record the before/after `% cached` on **haiku** as well as opus, because that is
      where the win would show up.
- [x] Confirm `% cached` is unaffected — inner knowledge is static, so it belongs in the cached
      prefix and should cost ~10% on re-read
- [x] `clj-kondo` 0/0, `cljfmt` clean, `shadow-cljs compile main` → 0 warnings
- [x] Human approval; commit `:recycle: Split agent inner knowledge from user-facing skills`

## After Finish

- [x] Rename `todo-`→`done-`, update README link
- [x] Note the final split — Phase 04 only generates bodies for what stayed user-facing

## Files

- `frontend/src/app/main/data/workspace/agent_skills.cljs` — the `inner-knowledge` layer
  (`governance` + `naming-conventions` + `native-tool-notes`), alongside the untouched `catalog`
- `frontend/src/app/main/data/workspace/agent.cljs` — `build-system-prompt` now inlines
  `ask/inner-knowledge` instead of its own 4-line governance paraphrase
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — **unchanged**, as predicted: it renders
  `catalog`, and inner knowledge deliberately is not in it

## Verification

- **No forbidden references:** 0 MCP mentions and 0 plugin-API mentions in the always-on layer
  (asserted against the live string, not by eye).
- **No leak into the user surface:** `catalog-manifest` still returns 9 enabled skills, none of
  them inner knowledge; the Skills tab still renders 3 groups × the same 10 cards.
- **The governance is actually followed, not merely present.** Asked the agent point-blank:
  *"May you rename an auto-named layer without asking? May you delete a shared component without
  asking?"* → *"(1) Yes — renaming an auto-named layer to a semantic name is in the auto-fix safe
  set. (2) No — deleting a shared component needs your explicit approval."* It cited the safe set
  by name. That is the layer doing its job.
- `clj-kondo` 0/0 · `cljfmt` clean · `shadow-cljs compile main` → 0 warnings.

## Cost of the always-on layer

Measured live. The layer is 3,735 chars (~930 tokens); the whole system prompt is now 4,892
chars (~1,220 tokens).

| Model | Prefix before | Prefix after | Δ | % cached after | Cost of the layer / turn |
|---|---|---|---|---|---|
| `claude-opus-4-8` | 2,562 | **3,719** | +1,157 | **94%** (was 93–96) | **+~$0.0006** — re-read at 0.1× |
| `claude-haiku-4-5` | ~2,341 | ~3,220 | +880 | **0%** (unchanged) | +~$0.0009 — full price, never cached |

On opus the layer is nearly free: +1,157 tokens that re-read at 0.1× costs about six
hundredths of a cent per turn, for governance and naming that shape every response. On haiku it
is paid at full price every turn, but ~$0.0009 is still noise.

**Honest negative: the Phase 01 "growth might cross the cache floor" idea did not pan out.**
Haiku's prefix went 2,341 → 3,220 and still caches **nothing** — the documented floor is ~4,096
and we are ~900 tokens short. The insight was directionally right and is still the reason not to
*fear* this layer, but it did not pay off here. **Do not pad the prompt to reach the floor** —
that would be bloat bought for a cache trick, and it would violate this phase's own bar (content
must earn "always"). If a later phase adds real always-on content and happens to cross 4,096,
haiku caching switches on as a bonus; that is the only acceptable way to get there.

## Notes

- The interesting tension: inner knowledge is the *most* valuable content and the only content
  the user cannot turn off, so it is also the easiest place to quietly bloat every single
  request. The discipline is that it must earn "always" — anything procedural belongs in a
  skill body (Phase 04), not here.
- **Phase 01 inverted one assumption here, and it is worth stating plainly: on the caching
  axis, a *bigger* always-on layer is better.** Anthropic's minimum cacheable prefix is ~4096
  tokens for Opus 4.8 / Haiku 4.5; our tools+system prefix is only ~2.5k, and haiku
  consequently **never caches at all** (measured: 3 identical turns, `cache-read 0` every
  time). Growing the stable layer pushes the prefix clear of that floor, so the knowledge added
  here could turn caching on for models that get none today — and cached tokens re-read at
  0.1×. Do not reflexively minimise this layer to "save tokens"; below the floor you pay
  **full price for everything, every turn**. Measure, don't assume.
- Dropping `penpot-mcp-tool-reference` from the embedded path is not deleting it: the MCP
  server still serves external agents and still needs it. This is about which corpus the
  *embedded* agent's prompt is built from.
