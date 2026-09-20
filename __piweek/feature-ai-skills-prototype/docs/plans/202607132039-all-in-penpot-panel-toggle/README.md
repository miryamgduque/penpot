<!-- TRANSIENT — part of the __piweek/ team scratch. Delete before the PR is finalized. -->

# All-In Penpot — Open & Close the Panel (US #2)

**Status:** done
**Created:** 2026-07-13
**Apps:** `frontend`
**User story:** [All-In Penpot #2 — Open and close the All-In Penpot panel](https://tree.taiga.io/project/miryam-all-in-penpot/us/2)
**Dependencies:** US #1 (provider/model connection) already landed on this branch — its state must stay intact. US #5 (conversation persistence) owns hard-refresh survival of chat content.

## Context

The `feature/ai-skills-prototype` branch currently exposes the AI feature as **two** plugin-iframe panels (Agent chat + Skills manager), each with its own workspace header button, whose open state lives in `[:workspace-local :skills-dock]`. That bucket is cached per-page and lost when navigating between boards, so the panel does not survive navigation as the story requires.

US #2 replaces that with a **single, native, right-docked "All-In Penpot" panel**, tabbed like the left sidebar's Layers/Assets:

- One toolbar button (Lucide **bot** icon) next to the existing **View mode** button.
- **Alt+B** toggles it (same `Alt+<letter>` pattern as Layers `Alt+L`, Assets `Alt+I`, Palette `Alt+P`).
- The same button/shortcut both opens and closes it (a toggle).
- Open/closed state **and** chat content **survive navigation** — between pages/boards, and leaving to the dashboard and returning — **bound to the file** (one shared chat across all of a file's pages).
- A **hard browser refresh resets the panel to closed** (chat-content survival across refresh is US #5's call, not this story's).
- The provider/model connection from US #1 is untouched by opening/closing.

### Decisions locked in with the user (2026-07-13)

1. **One panel, two tabs** (Chat + Skills) — tabbed like Layers/Assets, not two separate buttons/panels.
2. **File-bound state**, stored in-memory in the root app state keyed by file-id (survives SPA navigation, resets on hard reload — exact fit for the spec). The chat is shared across all pages of a file. The panel should also **surface the current page and current selection** as context to the chat.
3. **Native CLJS panel** — a first-class workspace side panel; the plugin-iframe + `window.postMessage` relay is retired for this surface.

### Scope boundary (important)

This story delivers the **native panel shell**: the docked column, the two-tab structure, the bot toolbar button, the Alt+B toggle, and the file-bound open/close + content-survives-navigation persistence. The **deep contents** of each tab (the full agentic chat loop, the skills-manager CRUD UI) are owned by other stories (US #1, #5, and the skills-management stories). The Chat tab here renders a real, persisted transcript + composer so navigation-persistence is demonstrable and testable; wiring a live LLM round through `:ai-agent-round` is a stretch goal, not a gate. The Skills tab starts as a stub to be filled by its own story.

The existing React `ai-skills` app and the plugin relay in [`data/workspace/skills.cljs`](../../../../../frontend/src/app/main/data/workspace/skills.cljs) are **superseded** by this native panel but are only partially removed here (the two old header buttons + their relay wiring). Full deletion of the React app is deferred to avoid disturbing other in-flight prototype work — see Phase 06.

## Phases

1. [Phase 01 — Native panel column & mount](./done-phase-01-panel-column.md) — a docked right `[:aside]` gated by an `:ai-panel` layout flag; canvas reflows. ✅ **done** (commit `5ab249bbf9`)
2. [Phase 02 — Bot icon, toolbar button & Alt+B](./done-phase-02-button-and-shortcut.md) — register a bot sprite, single button next to View mode, `:toggle-ai-panel` shortcut. ✅ **done** (commit `318f40835d`)
3. [Phase 03 — Two-tab shell (Chat / Skills)](./done-phase-03-tabs.md) — `tab-switcher*` inside the panel, persisted active tab, stub bodies. ✅ **done** (commit `947137a49f`; + "Agents" title & sidebar-aligned tabs)
4. [Phase 04 — File-bound open/close persistence](./done-phase-04-open-state-persistence.md) — per-file in-memory open state that survives page nav + dashboard round-trip and resets on hard refresh. ✅ **done** (commit `7af90ec55b`)
5. [Phase 05 — Chat content persistence & context awareness](./done-phase-05-chat-persistence-context.md) — per-file transcript that survives navigation; surface current page + selection. ✅ **done** (commit `9f8ef35547`)
6. [Phase 06 — Retire the old plugin-panel buttons & relay](./done-phase-06-retire-plugin-relay.md) — remove the two old header buttons and the `window.postMessage` relay wiring. ✅ **done** (commit `24059009b3`)

## Completion Summary

**Completed:** 2026-07-13

### What Shipped
- A native, right-docked **Agents** panel (CLJS, no plugin iframe) with a title band and two tabs (Chat / Skills) aligned like the sidebar's Design/Prototype/Inspect, separated from the options sidebar.
- A single **Lucide bot** toolbar button next to *View mode* (ghost when closed, primary when open) and the **Alt+B** toggle; both drive the panel.
- **File-bound, in-memory open/close state** (`[:ai-panel <file-id> :open?]`) that survives page/board navigation and dashboard round-trips and resets to closed on a hard refresh.
- A **per-file chat transcript** with a working composer (Enter to send) that survives navigation the same way, plus a **context chip** surfacing the current page + selection.
- Full retirement of the old plugin-iframe AI panels: the two header buttons, the `window.postMessage` relay, `data/workspace/skills.cljs`, and the orphaned plugin ids/permission bypass.

### What Changed from Original Plan
- Phase 03 gained a user-requested **"Agents" title band** and sidebar-aligned tabs + panel separator (originally a plainer tab shell).
- The button/panel are labelled **"Agents"** (the user's chosen name); the project/feature is still "All-In Penpot".
- The two old buttons were removed early (Phase 02) rather than in Phase 06.

### Lessons & Follow-ups
- The devenv SCSS watch does **not** pick up newly-created `.scss` files — needs a one-shot `build-app-assets.js` (saved to memory).
- `refs/selected-shapes` returns a **set of ids**, not shape maps — resolve names via `refs/workspace-page-objects`.
- The Chat tab's composer currently only appends the user message; the **live agent turn** is delivered by the sibling plan [`202607132113-port-ai-skills-to-cljs`](../202607132113-port-ai-skills-to-cljs/) (its Phase 01 replaces the composer submit with `run-turn`).
- Durable (hard-refresh) chat persistence is **story #5**, intentionally out of scope here.
- Light-theme appearance wasn't explicitly screenshotted (dark verified); the panel uses theme-aware CSS vars.
- The `ai-skills` React app remains on disk as the porting-plan base — schedule its removal once the port is verified.

## Acceptance Criteria

- A single bot-icon button sits in the workspace toolbar next to the **View mode** button; clicking it opens the All-In Penpot panel docked to the right, and clicking again closes it.
- **Alt+B** toggles the panel open/closed regardless of current state, with no conflict with existing bindings.
- The panel contains two tabs (Chat, Skills), switchable like Layers/Assets.
- With the panel open: navigating between pages/boards, leaving to the dashboard and returning, all preserve the panel's open state **and** its chat content, per file.
- A hard browser refresh resets the panel to **closed**.
- Opening/closing the panel does not disconnect the US #1 provider/model connection.
- `make lint/frontend` and `make typecheck/frontend` pass; the canvas reflows correctly (no overlap, no swallowed clicks) in light and dark themes.
