# Branch code review — feature/ai-skills-prototype

**Date:** 2026-07-17
**Scope:** `git diff develop...HEAD` (261 files, ~37.5k insertions) plus the uncommitted working-tree changes to `agent.cljs` / `agent_tools.cljs` / `agent_tools_test.cljs`.
**Method:** 9 parallel finder angles (line-by-line ×2, removed-behavior, cross-file tracer, reuse, simplification, efficiency, altitude, conventions) → 44 candidates → dedup to 22 → one adversarial verifier per candidate. Every verdict below cites working-tree code.

Verdict tally: **13 confirmed, 5 plausible, 4 refuted.**

---

## Confirmed — correctness / security (ranked by severity)

### 1. Any authenticated user can rewrite app-level design skills
`backend/src/app/rpc/commands/design_skills.clj:78` — create/update/delete only run `check-edition-permissions!` inside `(when team-id ...)`; rows with `team_id NULL` (app scope) skip all checks, and the namespace is registered in `rpc.clj:345`. Any logged-in user can inject or delete the instance-wide skill bodies every team's dashboard fetches.
*Mitigating:* today `:design-skills` only feeds the dashboard UI — the agent's runtime context comes from `profile_skill`/`team_skill` — but the module docstring says these rows are intended as inherited agent context, which would make this a prompt-injection vector. The code comments acknowledge the gate as "future work".
**Fix:** require an admin flag (or at minimum reject `team-id nil` from the RPC) before this merges anywhere shared.

### 2. Cancelling mid-turn persists a history that denies executed tools
`frontend/src/app/main/data/workspace/agent.cljs:896` (publish) / `:725-741` (`cancel-history`) — `:turn-history` is published once per round carrying the assistant message *with tool-calls but no results*; `tool-round` executes the tools and conj's the results directly into the recursive `step` call with no intervening publish. A Stop during round N+1's streaming makes `cancel-history` cap the stale snapshot with "Cancelled by the user before this tool ran" for round N tools that **did** run and mutate the document. On resume the agent re-issues the mutations (double-deletes, duplicate shapes).
**Fix:** publish `:turn-history` again immediately after tool execution (before the next `stream-round`), or have `cancel-history` consult the executed-outcomes set.

### 3. `nest_shape` into a grid wipes user-authored tracks, spans and placements — and splits undo
`frontend/src/app/main/data/workspace/agent_tools.cljs:2521` → `rebuild-grid:2916` — the reflow fires for **any** grid parent (only guard is `ctl/grid-layout?`) and does `(assoc :layout-grid-columns (vec (repeat n ctl/default-track-value)) ... :layout-grid-cells {})`: a fixed `240px` track becomes `1fr`, all spans/manual placements are wiped. The reflow is a second `st/emit!` after `(rx/timer 80)` with no shared undo transaction, so one Ctrl+Z reverts only the cell rewrite, leaving the reparent.
**Fix:** preserve existing track definitions/cells (seat only the new child), and wrap relocate+reflow in one undo transaction (`dwu/start-undo-transaction` or `:undo-group`).

### 4. `create_shape`/`create_text`/`insert_image` corrupt the data model for group parents and component copies
`agent_tools.cljs:1804` (also `:2564`, `:1899`) — all three do `(assoc :parent-id pid :frame-id pid)` unconditionally, while the schema advertises "board/**group** id to nest into". `cb/add-object` stores `:frame-id` verbatim, so a group parent yields a shape whose `:frame-id` points at a group (the canonical path resolves it via `ctst/top-nested-frame`, `shapes.cljs:346-360`). There is also no `ctn/get-first-valid-parent` guard, so creating inside a component copy injects into copy-owned structure; and add + `reflow-parent!` are two separate commits (two undo steps), where `dwsh/add-shape` wraps everything in a single undo id.
**Fix:** build on `app.common.files.shapes-helpers/prepare-add-shape` (what `dwsh/add-shape` uses) instead of hand-rolling.

### 5. `rebuild-grid`'s `rseq` flip scrambles grids whose `:shapes` isn't canonical — reachable via the UI
`agent_tools.cljs:2926` — the flip is correct only when `:shapes` is in the reversed-reading order `ctl/reorder-grid-children` produces. Penpot's z-order commands (`vertical-order-selected`, `workspace.cljs:786`, wired to Cmd+]/[ and the context menu) reorder grid children with bare `pcb/change-parent` and **no** re-canonicalization — invisible on a grid since cells pin positions. Sequence: user sends grid children to back in reading order → agent re-asserts `set_layout {columns: N}` (existing grid skips `create-layout-from-id`, `agent_tools.cljs:2999`) → `assign-cells` seats every child reversed.
**Fix:** re-canonicalize (`ctl/reorder-grid-children`) before the flip, or make cell assignment flow-order-aware in the common layer.

### 6. `apply_tokens` silently *un*-applies already-bound tokens
`agent_tools.cljs:4138` — the tool emits `dwta/toggle-token` and returns `{:ok true}` unconditionally. `toggle-token` routes to `unapply-token` when `cfo/shapes-token-applied?` is truthy, so re-applying (a retry, or a batch where some shapes already carry the token) removes the binding while reporting success.
**Fix:** call an apply-only path (skip shapes where the token is already applied) instead of toggle.

### 7. One empty assistant turn permanently breaks OpenAI-dialect conversations
`agent.cljs:887-898` appends `{:role :assistant :text "" :tool-calls []}` to history on an empty reply; `encode-openai:287` serializes it as `{"role":"assistant","content":null}` with no `tool_calls` — which OpenAI-compatible APIs reject — while `encode-anthropic:221` substitutes a `"…"` text block for exactly this case. Every subsequent turn of that conversation 400s until cleared.
**Fix:** mirror the anthropic guard (placeholder text) or drop empty assistant messages at encode time.

### 8. Batch shape tools silently drop malformed ids and report full success
`agent_tools.cljs:2702` (`delete-shape`; same pattern in duplicate/group/ungroup/mask/unmask and `shape-ids:3145`) — `(into [] (comp (keep parse-uuid) (distinct)) shapeIds)` discards unparseable ids **before** `ids-problem` runs, which only checks emptiness and existence. `delete_shape` with one valid and one truncated id deletes one shape and reports `deleted 1` as success — the model believes both are gone.
**Fix:** validate raw input count vs parsed count and report skipped ids in the result.

### 9. Token-color enforcement falsely rejects rgb()-valued tokens
`agent_tools.cljs:1593` (`normalize-hex`) — returns nil for anything non-hex, but token color values are validated by tinycolor2 ("valid css color" — rgb()/hsl()/named all storable), the stored `Token` record has no `:resolved-value` field, and even the StyleDictionary path returns the original string unconverted (`style_dictionary.cljs:65`, contradicting its own docstring). An `rgb(99,102,241)` token drops out of `allowed-colors`, so `color-violation:1717` rejects the agent's hex equivalent of a legitimate token color.
**Fix:** normalize with `app.util.color/parse-css-color` (the canonical hex+rgb() normalizer) on both sides of the comparison.

### 10. `screenshot_page`/`render_board` error path crashes on non-transit error bodies
`frontend/src/app/main/repo.cljs:272-276` — `decode-blob-error` transit-decodes every ≥400 blob body with no content-type check or try/catch. Exporter down → nginx HTML 502 → `t/decode-str` throws a raw parse error before `handle-response` can classify 502→`:bad-gateway`; `screenshot-page`'s `(:code (ex-data cause))` dispatch gets nil.
*Scope correction from the verifier:* only the new `:blob? true` callers hit this; the pre-existing export flows pass text bodies and are unaffected.
**Fix:** guard on content-type and wrap the decode, falling through to status classification.

### 11. `case` mis-pairing in the legacy dashboard redirect (latent)
`frontend/src/app/main/ui.cljs:97` — inserting the bare keyword `:dashboard-agent-skills` into the flat test/result `case` made it pair with `:dashboard-legacy-team-settings` *as its result*, and left `:dashboard-settings` as an odd trailing default. Latent today: the router never sends that section here, and the fall-through coincidentally preserves behavior — but throw-on-unknown is silently lost and the form is wrong.
**Fix:** delete the stray keyword (it's already correctly handled in the non-legacy group at line ~221).

### 12. `modify_shape`'s 80ms settle readback races the layout reflow
`agent_tools.cljs:2406` — geometry writes emit `(ptk/data-event :layout/update ...)` consumed through `(rx/buffer-time 100)` (`shape_layout.cljs:135`), so layout-owned geometry settles 0–100ms+ after the write; the 80ms readback can report "settled at requested geometry" right before the parent layout repositions the shape — defeating the drift warning it exists to give.
*Note:* the same timer in `nest_shape` is harmless — the relocate applies synchronously (see refuted #4).
**Fix:** subscribe to the `:layout/update` stream (or the reflow completion) instead of a wall-clock timer.

### 13. `update_shapes` issues ~3 full commit passes per shape instead of batching
`agent_tools.cljs:2334-2386, 2450` — `emit-shape-update!` emits one `st/emit!` per attribute group (up to ~13), and the batch tool wraps only the *undo* in a transaction; each emission independently builds changes and runs `commit-changes` (process-changes + index update + WASM sync). A 50-shape × 3-attr batch = ~150 full passes. `shapes.cljs` even ships an unused `update-shapes-buffer-start/stop` aggregator built for exactly this.
**Fix:** group identical updates into one `(dwsh/update-shapes ids f)` call and/or use the buffer mechanism.

---

## Plausible (strong code evidence, not exercised live)

- **Plugin dock lifecycle** — `workspace.cljs:140-142` renders `#plugin-dock` only `(when-not hide-ui?)`; `plugin-modal.ts` `disconnectedCallback` never dispatches `close`, so hide-UI destroys a docked plugin's iframe with the manager still holding a stale reference, and the same-URL guard (`plugin-manager.ts:98`) then blocks reopening. Separately, `create-modal.ts:23-68` appends a second `dock:true` plugin into the same fixed 420px `overflow:hidden` host — fully clipped and unclosable. The dock also couples the published plugins-runtime to a hardcoded Penpot DOM id and exposes `dock` to all third-party plugins with no arbitration.
- **`agent_web.clj:170` charset 500** — attacker-controlled `charset=` reaches `(String. bytes charset)` outside the only IOException catch (which wraps just the HTTP call, lines 144-148); an invalid charset name throws uncaught → 500 instead of a clean fetch error.
- **AI panel re-render storm** — four new refs (`ai-panel-violations`, `skills-catalog`, `resolved-skills-enabled`, `slash-menu-entries`, `refs.cljs:349/789-803`) omit the `=` equality fn their siblings pass, so they notify on every state transaction; and `chat-tab*` derefs `workspace-page-objects` at component root (`ai_panel.cljs:817`) with a fully unmemoized transcript. Both compound: with the panel open, every canvas edit / zoom / SSE delta re-renders the whole transcript.
- **`read_design` recomputes the audit per call** — `agent_tools.cljs:1384` re-runs the ≤1000-shape scan + `allowed-colors` rebuild for one integer, though the panel watcher caches the same value (cache is panel-scoped and 500ms-debounced, so a fallback + freshness check is needed). Also `sets-and-themes` is evaluated twice back-to-back (`:1386-1387`).
- **`promote-skill` response shape** — the insert return lacks the JOIN-projected `:promoted-by-name`, and `source_profile_skill_id` is never written anywhere (dead column, contradicting the ns docstring). Latent: the sole caller discards the return and refetches.
- **netguard resolver parity** — the SSRF guard fails closed and re-validates every redirect hop, but both checks resolve via Node's getaddrinfo while Chromium resolves independently — a rebinding/parity TOCTOU the code itself documents as accepted residual risk (`netguard.cljs:10-12`, `screenshot_url.cljs:23-24`). Known and accepted; listed for completeness.

## Refuted (checked and safe)

- **`read-capped` negative write** (`agent_web.clj:116`) — the loop checks `(>= (.size out) limit)` after every write, so remaining ≥ 1 at every write site; boundary arithmetic works out exactly.
- **`create_tokens` fresh-file data loss** — the stale set-id capture is real, but `dwtl/create-token`'s WatchEvent re-resolves the set at processing time and potok processes events depth-first synchronously, so entry 2 finds entry 1's set. No replacement occurs.
- **Pricing/catalog desync** — `estimate-cost-usd` returns nil (not 0) for unpriced models; both UI consumers hide the `$` segment; the round-count brake covers unpriced models by design.
- **`unique-name` LIKE-escaping** — the LIKE over-fetch only populates a set checked by exact string membership; harmless. (The verbatim duplication between `team_skills.clj` and `profile_skills.clj` remains a cleanup item.)
- Also cleared by finders: markdown renderer XSS (token-tree rendering + `safe-href` allowlist), RPC name/shape parity across frontend/backend, per-profile scoping on agent-chat/skill-state RPCs, migration indexes vs query WHERE clauses, icon/test-runner registrations, Alt+B shortcut collision, `modify_shape` refactor behavior preservation.

## Cleanup backlog (unverified, low risk)

- `refs.cljs`: 14 copy-pasted `ai-panel-*` refs → one factory; `ai_panel.cljs` (data): 18 repeated `(if-let [file-id ...] ...)` wrappers → `update-panel` helper.
- `agent_tools.cljs`: ~28 repeated guard preambles + 9 copies of the uuid-parse transducer → `throw-problem` / `parse-ids` helpers (`redo-change` already drifted — it skips the problem check).
- `unique-name` + `row->skill` shared between `profile_skills.clj` / `team_skills.clj`.
- `vec-remove` (`ai_panel.cljs:1646`) → `app.common.data/remove-at-index`; `upload-image-url` (`agent_tools.cljs:4648`) → `dwm/upload-media-url`; hand-rolled click-outside in `integrations.cljs:735` → the shared `dropdown` component (loses Esc-to-close as written).
- Dead: `integrations.ai-provider.autosave-hint` and `.models.save-key-first` in `en.po`; the permanently-false `checkpoint-enabled?` machinery in `agent.cljs` (deliberately disabled — consider deleting rather than gating, git history is the re-enable path).
- `refresh-violations` rebuilds `allowed-colors` from the full tokens-lib on every debounced tick — memoize on tokens-lib identity.
