# Postmortem — Kahoot screens session (2026-07-16, New File 1)

Review of the two agent chats on file `0c867a25…cda204` (Opus 4.8, ~232 calls, ~$8.84,
94% cache hit). The result diverged from the reference screenshots and most of the
budget went to fighting the tools, not designing. Full transcripts pulled from
`profile_agent_chat`; this doc ranks the causes and the fixes by leverage.

## What actually happened

**Chat 1 ("let's design this screens…")** went well: read_design → fetched
`penpot-foundations` + `penpot-component-factory` playbooks → interviewed via ask_user →
built a clean 3-tier token system (primitives/semantic/modes-light + theme) → started
components. The one stumble (10 identical `create_token` failures before creating the
set) self-corrected.

**Chat 2 ("continue… let's go sloppy")** is where it broke down. 223 tool calls, zero
`get_design_skills` fetches. The agent took "go sloppy" as license for absolute
x/y positioning, then spent the session paying for it:

- ~15 calls on a wild-goose "render quirk" chase — `nest_shape` calls had silently not
  landed, so half the content was never inside the board.
- ~40 calls fighting "inverted flex": it concluded the engine renders `row`
  right-to-left and adopted `row-reverse`/`column-reverse` everywhere as a workaround —
  which is now baked into the file as semantically-reversed layouts.
- Deleted a container to "rebuild clean" and silently lost the children it still wanted
  (compass icon, title, subtitle); a later `nest_shape` on the deleted id **succeeded**.
- Instance repositioning (the "scroll state") never converged — `y` kept "normalizing
  to 1020"; the agent finally declared success ("both screens identical ✅") while the
  visible gray gap it had been asked about remained.

## Root causes, ranked

### 1. Tool-boundary semantics (the dominant cost — this is code, not prompts)

**Flex order is the reverse of the `:shapes` vector, and the tools speak vector space.**
`nest_shape` passes `index` straight to `relocate-shapes` (index 0 = back of z-stack =
**last** flex item); `create_shape`/`create_text` with `parentId` append at the top of
the z-stack = **first** flex item. So creation order renders reversed, `index: 0`
means "put it last", and the model's mental model (CSS flex, DOM order) is wrong on
every call. It "solved" it with `row-reverse` — a corrupted-semantics file.
→ Fix in `agent_tools.cljs`: when the parent has a layout, translate index/append so
tool order = visual flex order, and say so in the tool descriptions. This single fix
would have saved ~⅓ of the session.

**Silent failures and stale-id success.** Several `nest_shape` calls did not take
(shapes stayed top-level) yet returned ok; nesting an already-deleted shape returned ok.
→ `nest_shape` should read back the parent after emit and error if the reparent didn't
land; all shape tools should error on unknown ids.

**`delete_shape` doesn't disclose descendants.** "deleted 1" for a board that took 3
grandchildren with it. → return "deleted 1 board (+3 nested children: …)".

**Instance position writes don't stick** (the y620→1020 normalization). Whatever the
cause (layout ownership, async settle), the tool returned "modified" while the value
reverted. → modify_shape should return the *settled* geometry, or explain why the write
was rejected.

### 2. The knowledge exists but never loaded (metaprompt answer)

Yes, the ai-kit skills are in the metaprompt — but only as a **routing index** (one
line each); bodies load on `get_design_skills`. In chat 2 the agent **never fetched
any playbook**. The bundled `penpot-build-screen` body explicitly forbids exactly what
it did ("I'll just position these with x/y, it's faster" is literally in its
anti-rationalization table) — that text sat unloaded in `aikit_bodies.cljs` the whole
session.

Two compounding gaps:

- **`inner-knowledge` (the always-on layer) has zero layout/design doctrine.** It
  carries governance, naming, and tool notes. Nothing says "every container is a
  flex/grid board; never absolute x/y for UI; sloppy = flex with rough values, not
  absolute coords". Layout doctrine shapes *every* build response — it meets the
  stated bar for inner-knowledge and is ~15 lines.
- **Nothing nudges a fetch.** "Continue implementing" + "go sloppy" didn't pattern-match
  a skill, so the index line was never followed. Consider: a hard rule in the system
  prompt ("before the first mutating call of a build task, fetch the matching playbook")
  or a cheap deterministic gate at the tool boundary.

### 3. The import only took SKILL.md — the design meat is in `references/`

The kit checkout has per-skill `references/*.md` that were **never imported**:
`build-screen/02-style-profiles.md` (anti-generic style commitment),
`03-layout-composition.md` (the flex doctrine: "No absolute x/y for UI layout", "every
container is a layout Board — recursively", hug/fill intent),
`04-component-recipes.md`, `05-critique-framework.md`,
`component-factory/02-flex-grid-layout.md`, and `shared/visual-self-review.md`.
The bundled bodies still *point at* these files ("see references/02-style-profiles.md")
— dangling pointers the native agent cannot follow.

Also: the kit moved on after our import (~07-05). Commit 2026-07-09 "Optimize and
improve the usage of skills + new DESIGN.md creation skill" adds `penpot-design-md`
and reworks skill usage. The bundled corpus is stale.

→ Regenerate the import including references (either appended to each body, or a
second-level fetch: `get_design_skills({name, reference})` so bodies stay cheap and
references load on demand — same disclosure axis, one level deeper).

### 4. No visual self-review / reference-compare loop

`shared/visual-self-review.md` in the kit is a ready-made doctrine — export → actually
look → judge against a checklist → at most 2 self-fix iterations → never present an
unlooked-at render. It was never imported anywhere. The agent used `render_board` 22
times but ad-hoc, never side-by-side against the attached reference screenshots, and
closed with "matches the reference" claims while the icons sat in reverse order and the
sheet had a giant gray gap. This doctrine belongs in inner-knowledge (condensed) or as
a mandatory section of every build-family body — plus a note that user-attached
reference images are the ground truth to re-render against before claiming a match.

## On the user's specific hypotheses

- *"It knows tools but not design"* — accurate, and the design knowledge exists in the
  kit; it's stranded in never-imported references + never-fetched bodies.
- *"Embed design rules for iOS/Android/web + a dictionary of common elements"* — the
  kit has no such dictionary; worth adding (as a kit reference or a native body):
  a decomposition taxonomy (status bar, nav bar, modal sheet, segmented control, list
  section, keypad, footer caption…) with typical structure per platform. Note the agent
  actually decomposed the Kahoot screens well by itself — the taxonomy's value is
  consistency and naming, not recognition.
- *"Visual analysis should categorize components in the image"* — same as above; make
  it step 0 of build-screen: "name every region of the reference before creating
  anything, and keep those names as layer names".
- *"Start sloppy → refactor, favour flex/grid over absolute"* — the session is the
  proof: sloppy-as-absolute cost more than flex-first would have. The doctrine should
  explicitly redefine sloppy: rough spacing values and placeholder content are fine;
  absolute positioning is not what sloppy means.
- *"Components and variants"* — chat 1 had this right (fetched component-factory,
  planned a sensible variant matrix). Not the gap.

## Fix list by leverage

1. **agent_tools.cljs:** flex-order/index translation; nest_shape read-back
   verification; error on stale ids; delete_shape descendant disclosure; settled
   geometry in modify_shape returns. (Would have prevented ~150 of 223 calls.)
2. **inner-knowledge:** +layout doctrine (~15 lines) incl. the redefinition of
   "sloppy", +condensed visual self-review (render → look → compare to attached refs
   before claiming done).
3. **Re-import the kit** at HEAD with `references/*.md` (bodies + second-level fetch),
   picking up `penpot-design-md` and the 07-09 skill rework.
4. **Fetch nudge:** system-prompt rule or tool-boundary gate so build tasks load their
   playbook before the first mutation.
5. **Component dictionary** (new reference doc): platform element taxonomy for
   decomposing reference images. Good first candidate to contribute back to the kit.
