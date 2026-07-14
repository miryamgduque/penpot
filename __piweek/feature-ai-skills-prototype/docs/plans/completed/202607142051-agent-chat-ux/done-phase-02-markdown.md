# Phase 02 — Markdown rendering

**Status:** done

## Verified live (devenv :3450, 2026-07-14)

Injected a message exercising every token type (no API spend):

| Behaviour | Result |
|---|---|
| user text keeps literal `**asterisks**` | ✅ |
| heading (`##` → `h4`, clamped) | ✅ |
| `strong` / `em` / inline `code` | ✅ |
| `ul` 2 items / `ol` 2 items, **with markers** | ✅ `disc` / `decimal` |
| fenced code block | ✅ highlighted (hljs classes present), scrolls horizontally |
| blockquote | ✅ |
| safe link → `href` + `rel="noopener noreferrer"` | ✅ |
| **`javascript:` link → no anchor, text preserved** | ✅ only 1 `<a>` rendered; no `javascript:` href anywhere |
| `.message-md` overrides `pre-wrap` | ✅ `white-space: normal` |

Console clean after the fix.

## Before Start

- [ ] Verify plan is still valid
- [ ] Confirm `marked` is still in `frontend/package.json` and still has zero importers
- [ ] Read `util/code_highlight.cljs` (the npm-from-CLJS + highlight-rendered-text pattern)
- [ ] Confirm `shadow-cljs.edn` still uses `:js-provider :external` + `:external-index`

## Checklist

- [x] New `ui/components/markdown.cljs` — `lexer` → rumext elements
- [x] `safe-href` allowlist + `rel="noopener noreferrer"`
- [x] Render assistant messages only; user messages stay plain text
- [x] Code blocks highlighted — via the existing `code-block*`, lazily (deviation 2)
- [x] Libs rebuild verified (`npm run build:app:libs`) — `marked` resolves; ESM fine
- [x] SCSS for headings/lists/code inside `.message-md`
- [x] Compile (0 warnings) + live preview review
- [x] Human approval received
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [x] Rename `todo-` → `done-`; update README links
- [x] Note follow-ups below

### Follow-ups

1. **`marked` is no longer vestigial** — it now has a real consumer, so it must not be swept up by a
   dead-dependency cleanup.
2. **Tables are not handled** — they fall through to the default branch and render as raw text. Fine
   for chat today; revisit if the agent starts emitting them.
3. **Streaming (Phase 08) will re-lex on every delta.** `markdown*` memoizes on `text`, so a
   streaming bubble re-parses per batched update. At 10 updates/sec on a short message that is
   cheap, but worth measuring — if it bites, render the streaming bubble as plain text and only
   swap to markdown on `:assistant-end`.

## Files

- `frontend/src/app/main/ui/components/markdown.cljs` — **new**; token tree → elements
  (planned as `util/markdown.cljs` — see deviation 1)
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — use it for `"assistant"` messages
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — `.message-md` block styles

## Notes

### Four plan deviations, all forced by reality

**1. Lives at `ui/components/markdown.cljs`, not `util/markdown.cljs`.** It renders rumext elements
and depends on a UI component, so `app/util/` → `app/main/ui/` would have been backwards layering.

**2. Reuses the existing `code-block*` (`ui/components/code_block.cljs`) — do not require
`code-highlight` directly.** The plan's "free win" was not free: requiring it eagerly made shadow
report *"Module Entry app.util.code-highlight was moved out of module :util-highlight … moved to
:shared"*, i.e. it dragged **highlight.js into the shared bundle for every page**. `code_block.cljs:17`
already lazy-loads it via `(modules/load-fn 'app.util.code-highlight/highlight!)`. Reusing it fixed
the regression *and* deleted my hand-rolled component. Its props are `:code`/`:type`, not
`:text`/`:lang`.

**3. Every render branch must build its element with `mf/html`.** Returning bare hiccup vectors from
runtime functions **crashed the whole panel** — React error #31, "objects are not valid as a React
child", with the keys of a CLJS keyword. `mf/html` only compiles *literal* hiccup at macro time, so a
vector built at runtime reaches React as a vector-whose-first-element-is-a-keyword. This is why the
dynamic tags (`h3…h6`, `ol`/`ul`) are `case` branches over literal hiccup rather than
`(keyword (str "h" depth))`.

**4. `--font-weight-bold` does not exist in the frontend.** It is defined only inside the plugin's
shadow-DOM host (visible in `libs.js`), so the rule silently did nothing. `ai_panel.scss` already uses
literal `font-weight: 600` in three places — matched that.

**Plus one CSS reset trap:** `reset.scss:137` sets `list-style: none` on every `ol`/`ul`, so rendered
markdown lists came out as indented text with no markers. Restored `disc`/`decimal` inside
`.message-md` only.

**No new dependency.** `marked@18.0.5` is already in `package.json:88` — added Apr 2021 by Andrey
Antukh for dashboard custom-fonts management, with **zero importers** since. It's vestigial, not
groundwork; don't read its presence as an endorsement of `marked.parse()`. (It's also a plausible
dead-dep-removal target — this phase gives it a real user.)

**Use `lexer`, never `parse()`, never `dangerouslySetInnerHTML`.** Verified against the installed
copy:
```
lexer type: function
top-level tokens: ["paragraph","space","list","space","code","space","paragraph"]
inline tokens:    ["text","strong","text","codespan"]
```
Rendering the token tree to React elements ourselves means **no HTML string is ever produced**, so
injection is structurally impossible and **no DOMPurify is needed** (there is no sanitizer anywhere
in the repo today). This beats hand-rolling a parser (wrong on nested lists/tables/escapes) and
beats marked+DOMPurify (new dep; `marked` dropped `sanitize` in v5+, so we'd own the config).

**The hole `lexer` does NOT close — tested:**
```
link token: [{"type":"link","href":"javascript:alert(1)","text":"l"}]
```
`lexer` passes `javascript:` straight through, and **React does not block it in `href`** (dev warning
only; renders in prod). Assistant text is untrusted model output, so:
```clojure
(defn- safe-href [href]
  (when (and (string? href)
             (re-matches #"(?i)^(https?:|mailto:).*" href))
    href))
```
Render as plain text when nil. Add `:target "_blank" :rel "noopener noreferrer"` — the panel lives
inside the workspace, so `window.opener` access is a real concern.

**Free win.** For `code` tokens render `[:pre [:code {:ref …} text]]` and call
`code-highlight/highlight!` in an effect — the exact `highlightElement`-on-React-rendered-text
pattern from `util/code_highlight.cljs`. Syntax highlighting for ~4 lines, no new dep, house
pattern.

**Keep user messages plain.** Users didn't write markdown and shouldn't have their asterisks eaten.
Only `"assistant"` role goes through the renderer.

**Build friction — flag it before it eats an afternoon.** `marked@18` is **pure ESM**
(`"type":"module"`, no `lib/marked.cjs`). The build is `:target :esm` with `:js-provider :external`
and `:external-index "target/index.js"` (`shadow-cljs.edn:63-68`), bundled by
`scripts/build-libs.js` into `resources/public/js/libs.js`. Adding a new npm import **regenerates
`target/index.js` and requires a libs rebuild** (`npm run build:app:libs`, or `watch:app:libs` in
dev). A stale `libs.js` produces a confusing runtime "module not found". `:export-conditions`
includes `"module" "import"` (`:65`) so ESM resolves; `highlight.js` proves the pipeline — but it's
dual CJS/ESM, so `marked` being ESM-only is **not** covered by that precedent. Not verified by an
actual build.

**`.message` has `white-space: pre-wrap`** (`ai_panel.scss` ~`:97-106`) — rendered markdown must
override it or block spacing doubles up.
