# Phase 10 — Deferred follow-ups

**Status:** todo

Running list of things intentionally deferred while porting the agent, so they
don't get lost. Grouped by area; each notes where it was deferred and what
"done" looks like. Promote any of these to its own phase/plan when picked up.

## Skills (Phase 07 — the big ones)

- [ ] **Skill bodies in CLJS.** `get_design_skills` currently returns catalog
      **metadata only** (name/category/mode/blurb) — the agent can't read the
      full aikit markdown to *follow* a skill. Generate a CLJS `aikit.gen.cljs`
      from `skills-core` (mirror `ai-skills/scripts/import-aikit.mjs`, which
      already does this for the MCP server) and have `agent-skills/catalog-manifest`
      + the tool return real bodies on demand. *Done =* the agent fetches a
      skill's body and follows its playbook.
- [ ] **Persisted enabled-state.** `agent-skills/enabled-skills` uses the static
      `:enabled` defaults. When Miryam's **US #7 Phase 02** (persisted enabled/
      disabled layer + payload) lands, read that instead so user toggles reach
      the agent's routing index + `get_design_skills`. Coordinate with her.
- [ ] **`enforced-rules` real source.** Phase 06's enforcement reads
      `[:ai-panel <file-id> :enforced-rules]`, populated only via
      `dwaip/set-enforced-rules` (manual/console). Wire it to whatever governs
      per-file rules (`token-only-colors`, `layer-naming`, …) — a rules concept
      separate from the catalog's `mode` skills. Until then enforcement is off
      by default.

## Tools (minor deferrals)

- [ ] **`apply_tokens` undo grouping** (Phase 05) — each application is its own
      undo entry because token resolution is async (StyleDictionary) and doesn't
      fit a synchronous undo transaction. Revisit if one-step undo matters.
- [ ] **`create_color_token` target set** (Phase 05) — dropped the `set?` param;
      always uses the current/auto-created set. Add set selection/creation if
      needed.
- [ ] **`create_text` fixed width** (Phase 04) — auto-width only; no `width?`
      (fixed-width, grow-height) variant.
- [ ] **Gradient / image color enforcement** (Phase 06) — only string hex
      fills/strokes are checked; gradients and image fills are skipped (matches
      the source guard). Extend if gradient tokens become enforceable.

## Bigger, out-of-scope-for-the-port (own plans)

- [ ] **Live violations ledger + change-watcher + `skill-triggered` toasts** —
      the plugin's `plugin.ts` watched shapes and accumulated violations; the
      native port ships only the on-demand `audit_file` (Phase 08). A live
      watcher is its own story.
- [ ] **Skills / Audit / Tokens manager UI tabs** — the deep manager UI from
      `ai-skills` (`Skills.tsx`/`Audit.tsx`/`Tokens.tsx`) — partly overlaps the
      existing native dashboard; deferred per the plan's scope boundary.
- [ ] **MCP external-agent parity** — the MCP path still reads the old
      bundled/pluginData skills, not the native catalog / enabled-state.
- [ ] **Delete the `ai-skills` React app** — final cleanup once the port is
      fully verified and nothing else references it.
