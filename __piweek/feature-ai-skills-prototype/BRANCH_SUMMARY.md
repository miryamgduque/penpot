<!-- TRANSIENT — delete this whole folder before the PR is finalized. -->

# `feature/ai-skills-prototype` — Branch Summary

**What this branch is:** a native AI design agent built into the Penpot workspace, governed by a
**skills & rules** system — design intent that lives with the file/team/instance, is inherited by
the agent, and whose enforced rules are checked at the write path. 282 commits, ~23.7k insertions
across 98 files in the frontend (ClojureScript), the backend (RPC + 8 migrations), and the
plugins-runtime. Synced with upstream `penpot/penpot` develop @ `33d478b532` (2026-07-19).

This doc summarizes every functionality area: what it does, the design motivation behind it, and
how it was implemented. Deeper per-stream detail lives in the phased plans under
[`docs/plans/`](docs/plans/).

---

## 1. The All-In Penpot agent panel

**What it does.** A native right-docked workspace panel (like Layers/Assets) hosting the AI chat.
Toggled with a Lucide *bot* toolbar button or **Alt+B**. Chat is the panel's default home; Skills is
a secondary view behind a muted header icon. The panel surfaces the current page + selection as a
context chip, supports image attachments, a `/` slash-command menu, a Cursor-style model picker, a
live **spend meter**, resizable width, and an A−/A+ font-size stepper.

**Design motivation.** The AI feature started as two plugin-iframe panels whose open state was
cached per page and lost on navigation. The goal (US #2) was a single native panel bound to the
file that survives navigation. Later stories refined the hierarchy: chat and skills shouldn't read
as equal-weight tabs when chat is the primary surface (US #35), and a user with no provider
connected should see one obvious call to action instead of a dead chat affordance (the
"connect a provider" empty-state card). The width/font work answered a plain ergonomic complaint:
a fixed 360px column with 12px text is cramped for long transcripts.

**Implementation.**
- UI: [`ai_panel.cljs`](../../frontend/src/app/main/ui/workspace/ai_panel.cljs) (~2.4k lines) +
  [`ai_panel.scss`](../../frontend/src/app/main/ui/workspace/ai_panel.scss); state/events in
  [`data/workspace/ai_panel.cljs`](../../frontend/src/app/main/data/workspace/ai_panel.cljs).
- Mounted from [`workspace.cljs`](../../frontend/src/app/main/ui/workspace.cljs); toolbar button in
  [`right_header.cljs`](../../frontend/src/app/main/ui/workspace/right_header.cljs); shortcut in
  [`shortcuts.cljs`](../../frontend/src/app/main/data/workspace/shortcuts.cljs).
- Open state is **file-bound and in-memory**: survives SPA navigation, resets on hard reload —
  an exact fit for the spec. Width and font scale persist globally (`use-persisted-state`,
  a `--ai-font-scale` CSS variable through steps `[0.85 … 1.45]`).
- The old plugin-iframe relay for this surface was fully retired.

## 2. The native agent loop (CLJS port)

**What it does.** A real agentic turn loop running natively in the frontend — no iframe, no
`postMessage` bridge, no plugin runtime. Streams responses (SSE), executes design tools between
rounds, supports stop/cancel, works with Anthropic, OpenAI, Zhipu and Moonshot models, and lets the
user switch provider mid-conversation.

**Design motivation.** The predecessor was a React app in a plugin iframe driving the public
`penpot.*` API through `execute_code`. Porting the agent itself to native CLJS removed the bridge
tax and — more importantly — moved every capability to a **fixed set of typed native tools**
calling Penpot's internal mutation events, where enforcement and validation can live at the tool
boundary instead of inside arbitrary generated code.

**Implementation.**
- [`agent.cljs`](../../frontend/src/app/main/data/workspace/agent.cljs): history is kept in one
  **canonical provider-agnostic message model** and re-encoded per round into the Anthropic or
  OpenAI dialect (`encode-anthropic` / `encode-openai`) — this is what makes mid-conversation
  provider switching possible.
- All requests go through a backend **dumb-pipe proxy**
  ([`ai_providers.clj`](../../backend/src/app/rpc/commands/ai_providers.clj)):
  `::ai-agent-round-stream` re-emits provider SSE frames verbatim; API keys never reach the browser.
- `run-turn` is a recursive round loop capped at 32 rounds; cancelled turns synthesize
  `tool_result`s at the canonical layer so the model and the transcript never disagree.
- Streaming UX (US #27): token streaming, a working stop button, collapsible tool-call groups,
  markdown rendered via `marked.lexer` → rumext elements (never `innerHTML`).

## 3. The agent's design capabilities — 57 native tools

**What it does.** The agent can read a design as structured data or as code, and write nearly the
whole design surface: shapes, text, flex/grid layout, components/instances/variants, tokens and
themes (including dark mode), boolean operations, masks, SVG icon drawing, pages, comments, version
snapshots, and undo/redo.

**Design motivation.** Early sessions exposed the gap crisply: asked to "create Penpot variants,"
the agent produced fake `Property=Value` frames — *nothing was missing from Penpot; the gap was the
agent's tool registry*. Three failure patterns drove the roadmap: "the agent can create, barely
modify, and cannot delete"; "everything is color-shaped"; "absolute positioning is the only
positioning." Tools were added in demand-ordered waves (tallied from the 15 aikit skill playbooks),
under a standing rule: **never ship a tool that emulates** — if something can't be done properly,
the agent saying "I can't" is the correct behavior.

**Implementation.**
- [`agent_tools.cljs`](../../frontend/src/app/main/data/workspace/agent_tools.cljs): 57 tools
  declared in `tool-specs`, dispatched in `execute-tool`, each returning an rx observable. Since
  2026-07-19 the implementations live in ten family namespaces under
  [`agent_tools/`](../../frontend/src/app/main/data/workspace/agent_tools/) (common / read /
  structure / layout / components / document / tokens / media / agentic / compose; strictly
  downward require graph, script-generated split, zero behavior change) — the root file keeps the
  specs, the dispatch, and the playbook nudge.
- Writes go through Penpot's normal changes pipeline, so every agent action is **undoable** and
  syncs to collaborators like any human edit.
- Validation happens before emitting, and error messages name the fix ("validate before emitting,
  and name the fix in the message").
- Highlights: `read_design`/`generate_code` (design as code), `set_layout`/`set_layout_child`
  (flex + grid, late children flow in), `create_from_svg` (the agent draws icons),
  `create_boolean`, `mask_shapes`, `create_variant`/`switch_variant`/`swap_component`/
  `detach_instance` (drive a placed instance), token tools + `activate_theme` (dark mode via
  `modes/light`/`modes/dark` token sets), `leave_comment`/`list_comments`, `save_version`,
  `create_page`/`switch_page`, `undo_change`/`redo_change`.

## 4. Agent vision — the model sees, both ways

**What it does.** The agent can take its own screenshot of a board (`render_board`) and the user
can attach images to a message (up to 5, local-only).

**Design motivation.** "Our agent is blind. It reasons entirely over JSON… no image ever reaches
the model." A design agent that can't see its own output can't judge visual quality; a user who
can't paste a reference can't communicate one.

**Implementation.** One shared foundation — an image content block in the canonical message model,
encoded by both provider codecs. `render_board` uses the synchronous WASM renderer
(`render-shape-pixels`), avoiding the exporter service; output is bounded (1568px, WebP).
Attachments are base64 in the message, **never stored** in media storage; old images are pruned
from history (kept only on the last 2 user turns). Verified live: Claude read test text off an
attachment, and Opus reached for `render_board` unprompted.

## 5. Skills & rules — the governance system

**What it does.** Two kinds of cascading design intent, **App → Team → File** (+ per-user):
a **skill** is agent knowledge (playbooks, conventions); a **rule** is a checkable constraint
(advisory / triggered / **enforced**). Ships with the official **penpot-ai-kit** as the built-in
platform set, grouped in a card catalog (Audits / Build / Setup). Users can toggle individual
skills (account-wide default + private per-file override), create personal skills by describing
them to the agent, and invoke any skill via `/` slash commands. Each skill declares a **reactive
behavior**: *On-demand* (acts when invoked) or *Observer* (ambient awareness + notifications).

**Design motivation, story by story:**
- *Built-in catalog (US #7):* "a baseline set of skills without any setup, so I get useful,
  reliable behavior the first time I open the panel."
- *Enable/disable (US #8):* "this is about removing what I don't want" — hence per-user, private,
  instant-apply toggles with no confirmation ceremony.
- *Discreet controls (US #30):* "so the Skills tab doesn't overwhelm me with a wall of controls" —
  per-row switches became a muted ⋯ menu, dimmed "Off" pills, an All/Enabled filter.
- *Create a skill (US #9):* "describe what I need to the Agent, so I don't have to write a
  structured skill document by hand" — guided capture (what/trigger/behavior), then the model
  generates the doc; **the user never sees the raw structure**.
- *Reactive behavior (US #14):* retire the internal `:mode` (suggest/review/autofix) in favor of a
  user-facing axis describing *when* a skill acts. The load-bearing insight: "reactive behavior is
  the user-facing name for the watcher."
- *Promote to team (US #12):* a personal skill that proves useful can be promoted to the team —
  promotion **moves** the skill (no lingering personal copy), team skills are fetched and merged
  into everyone's panel, and a Team Dashboard "Agent Skills" page lists them.

**Implementation.**
- **The skill model:** [`agent_skills.cljs`](../../frontend/src/app/main/data/workspace/agent_skills.cljs)
  holds the two-layer knowledge model (always-on `inner-knowledge` vs on-demand skill bodies —
  see §9) and the `resolve-enabled` cascade (built-in default → account → per-file; in the DB
  cascade, higher-scope `mandatory` skills can raise but never loosen enforcement). During the
  prototype the model lived in a shared TS package, `skills-core/` — removed from the branch once
  the native port made it redundant. User-created skills in
  [`skill_gen.cljs`](../../frontend/src/app/main/data/workspace/skill_gen.cljs) /
  [`user_skills.cljs`](../../frontend/src/app/main/data/workspace/user_skills.cljs); toggles in
  [`skill_state.cljs`](../../frontend/src/app/main/data/workspace/skill_state.cljs); composer menu
  in [`slash_commands.cljs`](../../frontend/src/app/main/data/workspace/slash_commands.cljs).
- **DB:** `design_skill` + `design_skill_override` (app/team scopes, seeded from penpot-ai-kit),
  `profile_skill` (user-created), `profile_skill_state` (toggles) — migrations 0152–0157/0159,
  RPC in [`design_skills.clj`](../../backend/src/app/rpc/commands/design_skills.clj),
  [`profile_skills.clj`](../../backend/src/app/rpc/commands/profile_skills.clj),
  [`skill_state.clj`](../../backend/src/app/rpc/commands/skill_state.clj), and
  [`team_skills.clj`](../../backend/src/app/rpc/commands/team_skills.clj) (US #12 promotion).
- **Dashboard:** a native Skills & Rules page
  ([`dashboard/skills.cljs`](../../frontend/src/app/main/ui/dashboard/skills.cljs)) for team CRUD
  and per-entry toggling of the inherited app set.

## 6. Enforcement at the write path

**What it does.** Rules marked *enforced* are actually checked where writes happen, not just
suggested in prompt text. The flagship rule is `token-only-colors`: raw hex fills/strokes are
rejected; colors must come from design tokens.

**Design motivation.** Advisory prompt text is a suggestion; enforcement is a property of the
platform. If intent lives with the file, the model must hit a real wall, whatever it decides to do.

**Implementation.** Enforcement lives at the **tool boundary** — the color/fill/stroke paths in
`agent_tools.cljs` reject disallowed raw colors with a `{:rule …}`-tagged error before a change is
ever emitted, and the error message names the fix (use a token). Allowed colors are collected from
the file's design tokens and library colors; the checks are covered by `agent_tools_test.cljs`.

## 7. Observer skills & the auto-fix watcher

**What it does.** With the panel open, Observer skills watch the file ambiently: a strip appears
(~1.5s) when someone violates a watched rule (e.g. default `Rectangle` layer names, raw colors),
with a **Fix it now** action that asks the agent to batch-fix, and a redesigned notification widget.

**Design motivation.** Auto-fix as a governance *mode* wasn't a watcher — detection only happened
when you ran `audit_file`. The goal was ambient awareness: a live set of affected elements that
grows and shrinks as people work — while spending almost nothing. Key constraints: **at most one
detection request in flight, ever**, and **consent = panel open** (closed panel → zero work).

**Implementation** (watcher section of
[`data/workspace/ai_panel.cljs`](../../frontend/src/app/main/data/workspace/ai_panel.cljs)):
- **Deterministic tick:** a 500ms-debounced re-scan of dirty shapes against enabled Observer rules —
  regex/structural checks, zero model calls, recomputed for free on every change.
- **Semantic tick:** a 4s-idle-debounced, batched, single-in-flight **Haiku** pass
  (`detect-round`, ≤50 dirty shapes) for `detect: "model"` rules — measured at **~$0.0007/tick**.
- **Fix-it-now** composes a visible user-style message in the current session (no hidden second
  session), grouped per rule, capped at 20 ids, queued one-deep if a turn is running.
- Watched rules are **seeded from the enabled Observer skills** — without this the watcher would
  have shipped dead.
- Known gap: the semantic tick over-flags (Haiku flags valid PascalCase/role names).

## 8. DESIGN.md foundations & the vibes interview

**What it does.** `/vibes` runs a structured interview and distills the answers into a
**DESIGN.md**-format document (YAML frontmatter with machine-readable tokens + markdown body,
adopting the [google-labs-code/design.md](https://github.com/google-labs-code/design.md) format)
stored on the file and inlined into every system prompt. Tokens render as swatches; frontmatter is
edited through a **structured form, never raw YAML**. This generalizes into **Foundations**: named
per-file entries of standing design context (Vibes, Tone of voice, …) behind a compass header
icon — a list view, plus a detail view with agent-guided editing, each entry agent-authored.

**Design motivation.** Standing design context the agent reads from — project taste shouldn't be
re-explained every conversation. The interview exists because a doc a user must hand-author never
gets written; the structured editor exists because frontmatter YAML is not a user surface.

**Implementation.**
- [`design_md.cljs`](../../frontend/src/app/main/data/workspace/design_md.cljs) — a pure
  parse/serialize/validate core (string-keyed to preserve token names), requirable by tools and UI
  without cycles.
- [`design_doc.cljs`](../../frontend/src/app/main/data/workspace/design_doc.cljs) — foundations
  stored in the file's plugin-data (`:penpot-vibes` namespace), so they ride the changes pipeline:
  shared, synced, undoable, and they travel on export.
- [`elicitation.cljs`](../../frontend/src/app/main/data/workspace/elicitation.cljs) + the generic
  `ask_user` tool — **any** skill can interview the user; vibes is just the first customer. The
  interview script lives in the skill body (prose the model adapts), not hardcoded UI.
- Agent writes via the `set_foundation` tool.

## 9. Metaprompt layering & token efficiency

**What it does.** Makes long agent sessions roughly an order of magnitude cheaper, with a live
spend meter as the instrument: prompt caching of the conversation history, dead-tool-result
pruning, a runaway brake, auto-compaction, and a cheap side-context for exploration.

**Design motivation.** "Sessions in the agent panel spend far more tokens than the work they
produce." A code audit found history was never cached (cost quadratic in rounds — a build-screen
turn burned ~300k uncached tokens ≈ $0.90–1.50), and a real incident where Haiku spent ~$4 building
an unrequested landing page motivated the brake. The metaprompt work (US #26) ran as
**measure-then-fix**: each phase was an experiment, and two phases ended by refuting their own
hypothesis and shipping a decision instead of code.

**Implementation** (all in [`agent.cljs`](../../frontend/src/app/main/data/workspace/agent.cljs)):
- **Caching:** two `cache_control` breakpoints — system block + last history block — so each round
  re-reads prior rounds at ~0.1×. The measured headline: moving the volatile
  `{file, page, selection}` context **out of the cached prefix** took selection-change turns from
  0% → ~90% cached (~8.3× cheaper).
- **History hygiene:** `stub-stale-tool-results` (cheap, targeted) → `compact-history` (Haiku
  summarize-and-restart past 100k chars) → `trim-history` (blunt 150k backstop).
- **Runaway brake:** a pause-and-ask checkpoint at 12 rounds or $1.00, resumable with carried
  usage; a pending checkpoint is dropped on conversation switch. **Both brakes are currently
  disabled by user direction** (`checkpoint-enabled?` / `round-cap-enabled?` in `agent.cljs` —
  disabled, not removed): interrupting real design work cost more than it saved, the user has the
  stop button and the live meter, and flipping either boolean restores the pause. With both off,
  a turn ends only when the model stops calling tools or the user stops it.
- **Side-turns:** `run-side-turn` — a bounded, read-only, buffered Haiku loop powering the
  `explore_design` scout (broad reading delegated out of the expensive context, returns a ≤6k-char
  digest), the watcher's semantic tick, and auto-compaction.
- **Knowledge layering:** always-on `inner-knowledge` (governance, naming, tool behavior) vs skill
  bodies served on demand — replacing a proposed per-skill `load` field that measurement showed to
  be redundant.
- Long conversations also get a "summarize into a fresh chat" suggestion in the UI.

## 10. Durable per-file chat history

**What it does.** Multiple named conversations per file, private to each user, surviving hard
refresh: a conversation switcher with browse/resume/rename/delete, auto-titled from the first
message, "Clear" reframed as "New chat".

**Design motivation.** Chat was per-file but in-memory — a hard refresh dropped everything. Durable
history was the long-deferred US #5.

**Implementation.** `profile_agent_chat` table (migration 0158; transit-encoded jsonb `data`), RPC
in [`agent_chats.clj`](../../backend/src/app/rpc/commands/agent_chats.clj), client logic in
[`agent_chats.cljs`](../../frontend/src/app/main/data/workspace/agent_chats.cljs). Persistence
happens only at **turn boundaries** (a stored row is always a consistent snapshot — tool_use/
tool_result pairs intact), and **images are stripped before every save**, keeping the "attachments
are never stored" promise and bounding row size.

## 11. Provider configuration & model catalog

**What it does.** An "Agents" section on the settings/integrations page: connectable cards for
Anthropic, OpenAI, Zhipu and Moonshot with write-only API keys, live model fetch, and per-model
enable checkboxes that form the chat's model pool, shown in a grouped Cursor-style picker.

**Implementation.** `profile_ai_provider` table (migration 0154 — key stored plaintext, flagged as
a prototype shortcut), RPC + streaming proxy in
[`ai_providers.clj`](../../backend/src/app/rpc/commands/ai_providers.clj), UI in
[`settings/integrations.cljs`](../../frontend/src/app/main/ui/settings/integrations.cljs), and a
hand-maintained model catalog in
[`data/ai_providers.cljs`](../../frontend/src/app/main/data/ai_providers.cljs) whose `:vision`
flag drives per-turn image encoding.

## 12. Dockable plugin panels (plugins-runtime)

**What it does.** Plugins can pass `dock: true` to `openUI` and render as an integrated side panel
instead of a floating window. This was the bridge era's host mechanism (the prototype Skills plugin
docked into the workspace — since removed) and remains a general runtime capability.

**Implementation.** `open-ui-options.schema.ts` (+`dock`), `create-modal.ts` (append into the
host's `#plugin-dock` container, dispatch `resize`), `plugin-modal.ts` (skip drag when docked),
`plugin.modal.css`. The workspace renders the `#plugin-dock` aside.

## 13. Honest tools — the Kahoot postmortem

**What it does.** Makes the tool boundary tell the truth about Penpot's semantics instead of
letting the model discover them the hard way. Sources: a call-by-call review of two live sessions
([`docs/kahoot-session-postmortem.md`](docs/kahoot-session-postmortem.md)) where ~150 of 223 calls
were spent fighting the tools, not designing.

**Design motivation.** Penpot lays flex children out in **reverse `:shapes`-vector order**, and the
tools spoke vector space — so creation order rendered backwards, `nest_shape index 0` meant *last*,
and the model "solved" it by authoring `row-reverse` layouts (semantically backwards files).
Several events also filter silently (`relocate-shapes`, grid cell pinning), which reads as success
to an agent and produces wild-goose diagnosis loops.

**Implementation** ([`agent_tools.cljs`](../../frontend/src/app/main/data/workspace/agent_tools.cljs)):
- **Tools speak reading order.** Creation into a laid-out board lands in creation order;
  `nest_shape`'s index is the flow position; `nest-vector-index` also compensates
  `insert-at-index`'s pre-removal indexing on same-parent reorders. Grids follow: `rebuild-grid`
  seats cells in flow order and re-nesting reseats them (`reflow-grid-cells`).
- **No silent failures.** `nest_shape` validates (stale ids, cycles, copy-owned parents) and reads
  the move back after the pipeline settles; `delete_shape` discloses cascaded children;
  `modify_shape` returns the **settled** geometry and names the owner when a write didn't take
  (this is what let the next session crack absolute-child coordinates in 3 rounds).
- **Capability honesty:** `set_layout_child` accepts fix/auto on a board that itself has a layout
  (Penpot's own "Flex board" hug); `create_token` teaches sets-before-tokens; `insert_image` points
  at a keyword-capable placeholder source.
- **Knowledge that was stranded now loads:** the ai-kit re-import ships each skill's
  `references/*.md` behind a second `get_design_skills {name, reference}` disclosure level, a
  native `ui-element-taxonomy` reference teaches reference-image decomposition, and the always-on
  inner knowledge gained the layout doctrine ("sloppy ≠ absolute positioning") and a
  render-and-look self-review rule.

## 14. Context-window strategy — the NYT postmortem

**What it does.** Cuts the structural cost of long build sessions and removes their failure modes.
Source: a session that spent 133 rounds / ~$3.91 on one screen and hard-aborted twice at the 4M
payload cap ([`docs/nyt-session-postmortem.md`](docs/nyt-session-postmortem.md)). The finding:
caching already worked (94% hits) — cost is `rounds × prefix`, and only collapsing rounds moves it.

**Implementation.**
- **Composition tools** (the big lever): `build_tree` (one call builds a nested, laid-out,
  token-bound subtree; partial failures return the ids that exist for targeted repair),
  `clone_shape` (N copies into a laid-out parent with per-clone name/text/image overrides matched
  by layer name — the session's 25-round card dance in one call), `create_tokens` and
  `update_shapes` (all-or-nothing batches). Inner knowledge tells the agent to reach for them
  first and to issue independent calls together.
- **Image lifecycle** (the abort-killer): `render_board` budgets its output — photographic renders
  re-encode as JPEG q0.8 (~10× smaller; measured 556k vs multi-MB base64), oversized ones are
  refused with the fix named; and an over-cap payload strips its images (omission notes remain)
  and retries once before erroring.
- **Playbook injection:** the first message of a conversation runs one tool-less Haiku round that
  names the matching skill; the body is injected into the user message itself — cache-compatible,
  zero extra agent rounds, no reliance on the model choosing to fetch. A deterministic one-shot
  nudge backstops conversations where nothing matched.
- Prefix measured rather than dieted: 57 tool specs ≈ 11k tokens is cents per session at cached
  rates; the win of trimming would be window headroom, not money — deferred.

## 15. Native agent vs MCP — what the branch taught us

The branch started with an MCP door beside the embedded agent and deliberately retired it. What
follows is the honest scorecard, informed by shipping both.

**Where native wins:**

- **No drift.** An MCP server + skill kit version independently of the app; the native agent, its
  tools, and its skills ship **in the project with every release** — the tool boundary is compiled
  against the same internals it mutates, so a Penpot refactor breaks it at build time, not at a
  user's desk.
- **Skills are organizational objects, not files on someone's laptop.** Multi-level and reusable —
  built-in/app scope, team scope (promote/consult via the dashboard), personal skills, and
  per-file state — with inheritance and per-level enable/disable. An MCP client's skills live in
  its own config, per machine, per user, unshared.
- **Reactive skills exist at all.** Observer skills (the watcher, ambient audits, "Fix all")
  require standing presence in the running workspace — there is no MCP shape for "notice
  violations as the user works"; a protocol client only acts when called.
- **A better API surface than MCP.** The tools are typed, validated, and *semantic*: reading-order
  translation, read-back verification, settled-geometry returns, enforcement (token-only-colors)
  at the write path where it cannot be routed around, and composition tools that batch whole
  workflows. `execute_code` over a plugin API can express none of that — and the section-13 record
  shows what generic surfaces cost in practice.
- **Design direction lives with the file.** DESIGN.md/foundations ride plugin-data, load into
  every conversation, and travel with duplicate/export — not with whichever machine had the prompt.
- (Also: caching, spend metering, per-file durable chats, in-canvas forms (`ask_user`), and wasm
  renders — all integration points a protocol client doesn't get.)

**Where native costs:**

- **Context memory management is ours to solve.** Claude Code/Cursor bring their own compaction
  and caching; here every layer — breakpoints, stubbing, compaction, handoff, image windows,
  payload degrade — had to be built and tuned by hand (sections 9 and 14 are that bill).
- **Model + tool support relays on Penpot.** Provider dialects, streaming codecs, vision quirks,
  model catalogs — all maintained in-tree (the OpenAI image path is still live-unverified). An MCP
  user gets new models the day their client ships them.
- **External agents lost their door.** Retiring MCP means Claude Code/Cursor/CI can no longer
  drive Penpot under the same skills + enforcement. If that matters later, the answer is a thin
  MCP facade over the SAME native tool registry — not a second implementation.

---

## 16. Learning from other agents — the OSS adoption program

**What it is.** A deliberate loop: study how other agent products prompt and structure their
loops, rank what transfers, ship the winners as small phases. Two rounds so far.

**Round 1 — Kimi CLI + opencode** (their published system + compaction prompts) produced the
[oss-prompt-learnings plan](docs/plans/202607171018-oss-prompt-learnings/README.md); phases 01–05
shipped 2026-07-18/19:
- **Compaction keeps lessons** — `compact-system` gained a `## Learned` section (tool errors +
  fixes, discovered constraints survive compaction) and an *anchored re-compaction* instruction
  (a second compaction updates the prior summary instead of narrating it).
- **Governance: scope & other people's work** — smallest change that satisfies the ask; never
  touch shapes you didn't create outside task scope; update a contradicted foundation in the same
  turn (`set_foundation`).
- **`ask_user` question policy** — only questions the file/foundations/defaults can't answer,
  non-blocked work first, recommended option leads, no permission-style questions.
- **Visual-direction nudge** — a matched build playbook on a file with no foundations, no
  components and no tokens lib injects an anti-generic direction block into the user message
  (volatile slot; structurally once per conversation).
- **Clickable shape refs** — `[Layer name](shape:<uuid>)` in the transcript renders as a
  ⌖ chip; one delegated click handler selects + zooms the shape (live-verified). Malformed ids
  fall back to plain text; the markdown href allowlist is untouched.

**Round 2 — the eleven-project survey** ([docs/oss-agent-survey.md](docs/oss-agent-survey.md),
2026-07-19): Cline, Roo Code, Aider, OpenHands, Gemini CLI, Codex CLI, Goose, Zed, Onlook,
bolt.diy, Dyad — read from their actual repo sources and mapped onto our measured weaknesses.
Headlines: the **plan tool is a GO** (all four platform CLIs ship one; Codex's
one-item-in-progress invariant is passive progress visibility — the thing the disabled spend
checkpoint failed to be); parallelism doctrine and bolt.diy's ban-list anti-generic language feed
existing phases; OpenHands-style deterministic triggers can extend playbook injection past
message 1. It also confirmed what is **ours alone**: tool-boundary enforcement, the visual
self-review loop, graduated governance, anchored re-compaction.

**Measurement.** [docs/measuring-agent-efficiency.md](docs/measuring-agent-efficiency.md) +
[docs/scripts/measure-agent-efficiency.mjs](docs/scripts/measure-agent-efficiency.mjs) extract
calls/round and cost per conversation from `profile_agent_chat`. Historical baseline across all
stored chats: **~1.68 calls/round** weighted (NYT session was 1.24 pre-composition-tools) — below
the ≥2 bar, so the phrasing A/B (plan phase 06) proceeds; phase 08 (plan-tool go/no-go) waits on
its numbers.

**Future steps** (also the closing slides of the [deck](../deck/penpot-ai-agent-deck.html)):

- **Standing survey loop** — keep learning + adopting from other providers and OSS projects on a
  cadence; two rounds shipped adoptions within days of reading.
- **The designer-session flywheel** — sessions are data: we already store every agent
  conversation per file; add the designer's own post-agent edits (a manual fix of a misaligned or
  mis-nested shape is ground-truth feedback). A daily agentic loop mines transcripts + edit
  deltas for insights — postmortems on autopilot — feeding tools, doctrine and playbooks, and
  surfacing feedback to the user ("a skill/foundation would fix this recurring miss").
- **Vision economy** — we rely heavily on renders, but structure/nesting checks don't need
  full-res colour: grayscale/downscaled renders where they suffice. Every KB matters.
- **Multi-model routing** — cheap models already run the scout/compaction/matching side-work;
  extend to an architect/executor split (frontier model writes the dense spec, cheap model
  executes the tool calls), aligned with subtask delegation.
- **Artifacts generation** — deliverables beyond canvas edits: style guides, handoff specs,
  component docs, review reports from the same file intelligence.
- **Plan generation** — a visible plan artifact, one step in progress at a time: passive
  progress for the user, resumability for the agent (the survey's clearest GO signal).

**Even further out** (deck slide): a **detached, Claude-Code-style agent** — headless sessions
against design files, unlocking standing audit loops (nightly governance/a11y/token sweeps filing
findings as comments/reports), long builds that outlive a browser tab, and CI-for-design; the
honest caveat is that co-creation wants the canvas, so detached fits the audit/review/batch half
(renders already give a headless agent eyes). And **agents as the audience**: developers write and
review less code every year — assume an agent (Claude Code, Cursor, Codex) on the receiving end
of the handoff and deliver machine-consumable artifacts (structured specs, tokens, component
contracts, `generate_code` output) through the thin MCP facade over the same native registry.
Design-to-agent, not just design-to-developer.

**Roadmap to publish** (deck's closing slide; dates are targets, dependencies explicit):
Aug '26 harden (tier-1/2 adoptions, plan tool, dialect/vision verification, key encryption,
evals seed) → Sep–Oct designer pilot (structured user testing; session recording = chats +
post-agent edit deltas; nightly insight loop) → Oct–Nov OSS-model track in parallel
(custom-endpoint field, per-model eval gates, GPU server with vLLM) → Nov–Dec private beta →
Q1 '27 publish (GA behind a flag; enterprise self-hosted endpoint documented). Asks: designers'
time, a dedicated server for the recording/analysis loop, ~$2–4k/mo model budget while piloting,
and ideally a GPU server serving OSS models. The critical path is the flywheel, not the code —
without the server + budget the dates slip one-for-one.

---

## Testing

- **CLJS:** `agent_test` (880 lines), `agent_tools_test` (1678), plus tests for providers,
  design-md, design-doc, skill-gen, skill-state and user-skills — registered in the frontend runner.
- **Backend:** RPC tests for profile skills and skill state.
- Several late plans (watcher, chat history, panel resize, vibes) deliberately waived unit tests
  under demo pressure and used **live verification checklists + merge-as-approval-gate** instead;
  results are recorded in each plan's phase docs.

## Cross-cutting engineering themes

- **Measure, then fix.** The metaprompt, vision and token-efficiency plans each ran experiments
  against the live spend meter, and more than once the right outcome was a refutation (the
  per-skill `load` field was dropped; the vision re-feed loop wasn't built because "the agent runs
  that loop itself").
- **Enforcement is structural, not prompt-based.** Rules that matter are checked at the write
  path — the agent's tool boundary — not left to the model's goodwill.
- **The agent uses Penpot like a user.** Every write rides the normal changes pipeline: undoable,
  synced, collaborative, exportable.
- **The React `ai-skills/` app served as the porting spec** and has now been removed from the
  branch (recoverable from git history; it also housed the aikit import/seed generator scripts).

## Known gaps / deferred (tracked in the plans)

- Semantic watcher tick over-flags valid names.
- Both spend/round brakes disabled by direction — a pathological tool loop now runs until the user
  stops it; revisit before any multi-user rollout (the survey's plan-tool verdict is the intended
  replacement: passive visibility instead of interruption).
- Playbook injection and all side-work (scout, compaction, watcher tick) are Anthropic-only.
- OSS plan phase 06 (parallel-phrasing A/B, live runs on the user's key) in flight; phase 08
  (plan-tool go/no-go) gated on its numbers; the survey's tier-2/3 adoptions await review.
- Direction nudge live-fire and OpenAI vision codec unverified on a real model turn.
- Provider API keys stored plaintext; no per-file conversation quota; evals harness.
- Survey tier-1 prompt upgrades (parallelism language, ban-list nudge additions,
  directive-vs-inquiry) drafted in the survey doc, not yet applied.
