# Phase 02 — Markdown rendering

**Status:** todo

## Before Start

- [ ] Verify plan is still valid
- [ ] Confirm `marked` is still in `frontend/package.json` and still has zero importers
- [ ] Read `util/code_highlight.cljs` (the npm-from-CLJS + highlight-rendered-text pattern)
- [ ] Confirm `shadow-cljs.edn` still uses `:js-provider :external` + `:external-index`

## Checklist

- [ ] New `frontend/src/app/util/markdown.cljs` — `lexer` → rumext elements
- [ ] `safe-href` allowlist + `rel="noopener noreferrer"`
- [ ] Render assistant messages only; user messages stay plain text
- [ ] Code blocks highlighted via `code-highlight/highlight!`
- [ ] Libs rebuild verified (`npm run build:app:libs`)
- [ ] SCSS for headings/lists/code inside `.message`
- [ ] Compile + preview review with browser tools
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Note follow-ups below

## Files

- `frontend/src/app/util/markdown.cljs` — **new**; token tree → elements
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — use it for `"assistant"` messages (`:165`)
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — typography inside `.message`

## Notes

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
