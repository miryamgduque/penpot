# Chat-default panel + Skills icon (US #35)

**Status:** todo
**Created:** 2026-07-15
**Apps:** `frontend`
**Source:** [Taiga US #35 — Replace Chat/Skills tabs with chat-default panel and a Skills icon](https://tree.taiga.io/project/miryam-all-in-penpot/us/35)
**Dependencies:** Reworks the panel shell from **US #7** (the Chat/Skills `tab-switcher*`) and
reconciles **US #2** ("panel always reopens on Chat"). The Skills view body — `skills-tab*` (list,
categories, detail, ⋯ menu from US #30, filter) — is **reused unchanged**.

## Context

> As a Penpot user, I want the Agent panel to treat chat as its default home and Skills as a place
> I visit, so that the two don't read as equal-weight tabs when chat is the primary surface.

Chat is where the user works; Skills is an occasional destination. The current Chat/Skills tab pair
puts them at equal hierarchy. This story makes **chat the default home** (no tabs, no "Chat" label)
and moves Skills behind a **single muted icon** in the header.

**Interaction (verbatim intent):**

- **Default state.** The panel opens directly into chat — no tabs, no "Chat" label; the chat
  (message list + composer) is the entire body. A single **muted `list-checks` icon** sits in the
  header **next to the close (×)**, always visible but low-emphasis.
- **Opening Skills.** Tapping the icon opens the Skills view **over the chat, as a full-panel view**
  (not a tab, not a split). The Skills view header shows a **back arrow + "Skills"**. Back returns
  to chat.
- **Consistency.** Same "go one level deeper, come back" motion as the skill detail view (US #7).

**Reconciles / touches:**
- **US #2** — "always reopens on Chat" no longer needs tabs: the view state is in-memory and
  defaults to chat, so closing + reopening the panel always lands on chat.
- **US #7** — the Chat/Skills tab pair is gone; the Skills tab's *contents* are unchanged, now
  reached via the icon instead of a tab.

**Out of scope (verbatim):** the ⋯ row controls + All/Enabled filter inside Skills (US #30 — already
shipped); final icon styling / exact muted treatment (visual design).

### Decisions (from discovery)

1. **Adaptive single header.** One header row that swaps by view — **Chat:** "Agent" title +
   muted `list-checks` + ×; **Skills:** `←` back + "Skills" title + × (close stays in both). Not a
   second stacked sub-header.
2. **Keep `skills-tab*`'s own back.** The outer header back goes **Skills → chat**; when you drill
   into a skill, the detail view keeps its existing **"← All skills"** body button (two independent
   levels). `skills-tab*` is **not modified** — the story says its contents don't change.

### Current state (grounded in code)

- `ai-panel*` ([ai_panel.cljs](../../../../frontend/src/app/main/ui/workspace/ai_panel.cljs)):
  header = "Agent" title + close `icon-button*`; body = `connect-empty*` when no provider, else a
  `tab-switcher*` over `[{Chat} {Skills}]` rendering `chat-tab*` / `skills-tab*`.
- Tab state: `tab*` = `hooks/use-persisted-state ::ai-panel-tab "chat"` — **persisted**, which US #2
  says shouldn't be (it should always reopen on chat). This story removes it in favour of an
  in-memory view state.
- `chat-tab*` (composer + transcript) and `skills-tab*` (list ↔ detail, own back) are the two bodies
  — both reused as-is; only the shell around them changes.
- **Icons:** `i/arrow-left` exists (back arrow). `list-checks` does **not** — add it (Lucide, scaled
  to the repo's 16-viewBox convention, like `forward`/`unplug`). Note the sprite-regen gotcha: a new
  icon file needs a full `scripts/build-app-assets.js` run, not just a watch nudge (the nudge trips
  a sprite-wipe race).

## Phases

1. [Phase 01 — `list-checks` icon asset](./todo-phase-01-list-checks-icon.md) — add the Lucide
   icon (SVG + `icon.cljs` def) and regenerate the sprite; verify it renders.
2. [Phase 02 — Chat-default panel + adaptive header + Skills view](./todo-phase-02-chat-default-panel.md)
   — remove the tabs, chat as the default body, muted `list-checks` in the header, in-memory view
   state (default chat), full-panel Skills view with a `← Skills` header, back to chat.

## Acceptance Criteria

- The panel **opens into chat** with no tabs and no "Chat" label; chat is the whole body.
- A single **muted `list-checks` icon** sits in the header next to ×, always visible.
- Tapping it opens **Skills as a full-panel view** with a **`← Skills`** header; back returns to
  chat. Drilling into a skill still works via `skills-tab*`'s own back (unchanged).
- Closing + reopening the panel **always lands on chat** (in-memory view state; the persisted
  `::ai-panel-tab` is removed).
- `make lint` / typecheck pass; `:main` + SCSS build clean; verified live in the devenv.
