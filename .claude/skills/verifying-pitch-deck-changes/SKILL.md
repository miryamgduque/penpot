---
name: verifying-pitch-deck-changes
description: Confirm a pitch/deck HTML edit actually rendered and behaves (via local preview + DOM assertions, since screenshots time out and the files have no build/tests), and fact-check a claim against the branch source before it goes in the deck. Triggers: "verify this deck/pitch change", "check the deck renders correctly", "did my edit actually show up", "fact-check this claim before it goes in the deck".
---

<!-- TRANSIENT dev tooling — committed only so collaborators on this branch can
     see it. DELETE the whole `.claude/skills/` entry before the PR merges; it is
     not part of the Penpot product. -->

# Verifying pitch/deck changes and claims

The deck and pitch are static files with no build and no tests, so DOM assertions and
source-grounded fact-checking are the only real signal. Screenshots of the preview pane
time out (~30s) — drive the DOM instead.

## Serve it
The `.claude/launch.json` `piweek` config serves `__piweek/feature-ai-skills-prototype/` on `:8847`.
- Start / restart: `preview_start {name:"piweek"}`.
- If port 8847 is already held by another session, it's already serving — just open a tab: `preview_start {url:"http://localhost:8847/deck.html"}`.
- Server dies between sessions; restart when a page won't load.
- **`chrome-error://chromewebdata/`** = the server is down, not a code bug. Restart the preview.

## The cache trap (bit us more than once)
Browsers cache these files hard. **An edit can be on disk and invisible in the
browser**, which reads as "my change didn't work" and sends you editing the wrong
thing. If a change doesn't appear, before touching the file again:
```
http://localhost:8847/deck.html?v=2     # bump the number
```
If the cache-buster shows the new content, the file was always fine.

## Assert via the DOM (not screenshots)
Navigate, then run `javascript_tool` checks. Useful assertions:
- **Presence / text**: `document.getElementById(...)`, `.textContent`.
- **Computed style / colour**: `getComputedStyle(el).color` etc. (compare to the `--part-N` / role rgb).
- **Overflow.** Slides never scroll; overflowing content is silently cut off.
  ```js
  const s = document.querySelectorAll('.slide')[i];
  s.scrollHeight > innerHeight   // must be false
  ```
  Test the **longest** open popover/accordion body, not just closed, at two viewport heights (e.g. 720 and something taller) — the bottom-most popover is the one that overflows, and it only shows at the shorter height.
- **Viewport fit** for any floating element:
  ```js
  const r = el.getBoundingClientRect();
  r.right <= innerWidth && r.bottom <= innerHeight && r.top >= 0 && r.left >= 0
  ```
- **Behaviour ran**: click a dot (`#dot-row button`), confirm the target `.slide` got `.active` and `#nav-act` updated — proves `show()` and handlers ran with no runtime error.
- Toggle popovers/accordion by dispatching a `click` and checking the `.open` class + in-viewport bounds.

## What to check after a structural change
- `document.querySelectorAll('.slide').length` — matches what you expect.
- Dot count matches slide count.
- The act line on the slides **either side** of the change, not just the new one.
- Every `data-goto` still points where it should.

## Measuring instead of eyeballing
Reading computed values is more reliable than looking, and catches things the eye
misses (a colour that didn't apply, a link that's still underlined, a width that
didn't change):
```js
getComputedStyle(el).color            // did the part colour apply?
getComputedStyle(el).textDecorationLine
Math.round(el.getBoundingClientRect().width)
```
**One trap:** CSS transitions. Reading a value immediately after a click can
catch it mid-animation and report the *old* value. Either disable transitions for
the measurement, or measure after they settle:
```js
const kill = document.createElement('style');
kill.textContent = '.dots button { transition: none !important; }';
document.head.appendChild(kill);
/* …measure… */
kill.remove();
```

## Don't trust a single grep
`grep -c 'class="slide'` counts matches, not necessarily sections, and a crude
`grep -c '^    "'` will happily count array entries that no longer exist. When a
count looks surprising, look at the actual block (`awk '/start/,/end/'`) before
concluding anything.

## Syntax-check after a big JS edit
Extract the inline script and check it before trusting the page:
```bash
python3 -c "import re;open('/tmp/d.js','w').write(re.search(r'<script>(.*)</script>',open('deck.html').read(),re.S).group(1))"
node --check /tmp/d.js && echo OK
```

## Resize checks
`resize_window` to 1280×720 / 1440×800 (presentation) and ~1680–2200 wide (to check right-overlap popovers and "no drift").

---

## Fact-checking a claim before it goes in the deck

The deck's whole credibility rests on "grounded in real measurement", which makes an
unverified claim more expensive here than in an ordinary pitch. Several claims were
wrong until checked; this is the routine that caught them.

### The routine
1. **Find the claim's source in the branch**, not in another doc. Docs repeat each
   other; source doesn't.
   ```
   grep -rniE "pattern" backend/src frontend/src --include=*.clj --include=*.cljs
   ```
2. **Distinguish shipped from branch-only.** The most damaging error in this deck
   came from missing that distinction.
   ```
   git diff origin/develop HEAD -- <path>     # empty ⇒ same as develop
   git log --oneline -- <path>                # added and removed on this branch?
   ```
3. **Check the vendor's own docs** for anything about the wider product, rather
   than inferring from the repo.
4. **State the confidence you actually have.** If it can't be verified, write
   *"to confirm"* on the slide rather than asserting it.

### What this caught (examples, for calibration)
- **"External agents lost their door when MCP retired."** False twice: the
  skills/enforcement MCP door was branch-only and **never shipped** (so nothing
  was lost), and Penpot's *own* MCP server is real and shipped (so nothing
  retired). Two different MCPs were being conflated. Became "The MCP question",
  framed as an open decision.
- **"An MCP client's skills are files on one laptop."** Overclaim — they can live
  in a shared repo. The real gap is no inheritance, scoping or enforcement.
- **"Ours alone" (11-project survey).** Technically true, materially misleading — most
  of the surveyed set are coding agents, so several "they don't do this" wins were
  apples-to-oranges. The card was dropped.
- **A budget figure with no derivation.** Traced to a bare assertion with no backing
  math, in a deck that otherwise prides itself on measurement. Reframed as a ceiling
  rather than left as a fake-precise figure.

### The general lesson
Two failure modes to watch for:
- **Conflating two similarly-named things.** If a name is doing a lot of work in an
  argument, check it refers to one thing.
- **A true-but-misleading comparison.** "Nobody else does X" is only meaningful if
  the others would plausibly *want* to do X. Otherwise say what's genuinely
  comparable and leave the rest.
