---
name: adding-an-agent-tool
description: Expose a new tool or parameter to the in-app Penpot design agent — schema, dispatch, handler, and the design doctrine (honest tools, composition over chatter) that governs what a good agent tool looks like on this branch. Triggers: "add a new agent tool", "add a parameter to an agent tool", "expose this to the design agent", "how do I add a tool to agent_tools.cljs".
---

<!-- TRANSIENT dev tooling — committed only so collaborators on this branch can
     see it. DELETE the whole `.claude/skills/` entry before the PR merges; it is
     not part of the Penpot product. -->

# Exposing a new tool or parameter to the in-app agent

The design agent's tools are declared and dispatched in
`frontend/src/app/main/data/workspace/agent_tools.cljs`, and implemented in the
`frontend/src/app/main/data/workspace/agent_tools/` module namespaces
(`structure`, `common`, `media`, `layout`, `read`, `agentic`, …).

## A. Add a PARAMETER to an existing tool

Example: adding stroke caps / dash to `modify_shape`.

1. **Schema** — in `agent_tools.cljs`, add the property to that tool's `:input-schema :properties` (with an `:enum` if it's a fixed set). `update_shapes` inherits `modify_shape`'s fields automatically.
2. **Handler** — in the tool's namespace (e.g. `structure.cljs`), destructure the new key and apply it. Stroke attributes live on the shape's `:strokes [{…}]` vector:
   - caps: `:stroke-cap-start` / `:stroke-cap-end` (values in `common.types.stroke/stroke-caps`: `:round :square :line-arrow :triangle-arrow :square-marker :circle-marker :diamond-marker`)
   - dash: `:stroke-dash` / `:stroke-gap` (numbers)
   - style: `:stroke-style` (`:solid :dotted :dashed :mixed`)
   Apply in **both** stroke branches (setting a new stroke, and patching the existing one), and add the key to the patch-branch trigger condition.
3. **Validate** like the siblings do — `atc/enum-problem` for enums.

## B. Add a whole NEW tool

Example: the `create_line` tool.

1. **Schema** — add a `{:name "create_line" :description … :input-schema …}` map to the tool list in `agent_tools.cljs`.
2. **Dispatch** — add a case in the `execute-tool` `(case name …)`: `"create_line" (ats/create-line input)`.
3. **Handler** — write `create-line` in the right namespace. Mirror an existing creator (`create-text` is the cleanest template): resolve parent/frame-id, build the shape with `cts/setup-shape`, commit via `cb/empty-changes → cb/add-object → dch/commit-changes`, return `{:id …}`.
   - For a line/path use `:type :path` with geometry from the native Line tool: `path/points->content [p1 p2]`, `path/calc-selrect`, `grc/rect->points` (assoc `:selrect`/`:points` **after** `setup-shape`).
4. **Requires** — add any new ns to the file header (e.g. `app.common.types.path`, `app.common.geom.rect`).

## Gotchas
- **Def order:** a handler must appear **after** the helper defs it references (single-pass compile). clj-kondo's "Unresolved symbol" = you placed it too early; move it down.
- **Color governance:** `create_shape` enforces token-only-colors; `create_from_svg` and `create_line` intentionally allow raw stroke colors (audit_file still flags them). Choose deliberately.
- **Vector shapes:** `create_shape` only makes rect/ellipse/board. A line/path comes from `create_line` (added) or `create_from_svg` with a **single `<path>`** element (a one-element SVG imports as one path; multiple elements or a wrapper import as a group/board).

## Verify
```bash
# build must be 0 warnings, lint must be clean
sg docker -c 'docker exec -u penpot penpot-devenv-ws0-main bash -lc \
  "tmux capture-pane -t penpot:0 -p -S -20 | grep -iE \"Build completed|error\" | tail -2"'
sg docker -c 'docker exec -u penpot -w /home/penpot/penpot/frontend penpot-devenv-ws0-main bash -lc \
  "clj-kondo --lint src/app/main/data/workspace/agent_tools.cljs src/app/main/data/workspace/agent_tools/structure.cljs"'
```
The client must **reload the workspace** to pick up new tool schemas (see [[editing-agent-skills]] — same propagation caveat). Then — critically — **live-verify in a running agent chat**, not just a clean build; a tool can compile cleanly and still never have been exercised end to end (this branch has shipped tools that sat unverified for weeks, e.g. the numbered-pin heuristic model plus `create_line`/stroke-cap/stroke-dash — see the project memory `heuristic-skill-pin-model-unverified`).

## Design doctrine — what makes a tool good here (not just a tool that compiles)

Distilled from two branch postmortems (Kahoot: ~150 of 223 tool calls in a live session were spent fighting the tools, not designing; NYT: 133 rounds / ~$3.91 on one screen). The standing rule: **never ship a tool that emulates** — if something can't be done properly, the agent saying "I can't" is the correct behavior, not a tool that fakes it.

**Honest tools — tell the truth about Penpot's semantics instead of letting the model discover them the hard way:**
- **Tools speak reading order, not vector order.** Penpot lays flex children out in *reverse* `:shapes`-vector order — a tool whose index/creation-order talks in vector space forces the model to author backwards, semantically-wrong layouts to compensate. Translate at the tool boundary (see `nest_shape`'s index = flow position, `nest-vector-index`).
- **No silent failures.** Several Penpot events filter silently (`relocate-shapes`, grid cell pinning) — this reads as success to an agent and produces wild-goose diagnosis loops. A tool should validate up front (stale ids, cycles, copy-owned parents) and read the result back after the pipeline settles; on a write that didn't take, name the owner and the fix in the return value, not just "ok".
- **Capability honesty.** If a board already has its own layout (a "Flex board" hug), say so rather than silently no-op'ing a conflicting instruction. Teach prerequisites in the response (e.g. "sets before tokens") rather than failing opaquely.

**Composition over chatter — collapse rounds, don't just add tools:**
- A single-purpose tool that requires N calls to do one conceptual thing (build a card grid one shape at a time, clone N variants one at a time) is the expensive pattern this branch is moving away from. Prefer a **batch/composition tool**: one call that builds a nested, laid-out, token-bound subtree with partial-failure ids returned for targeted repair (`build_tree`), or clones N copies with per-clone overrides matched by layer name (`clone_shape`) — collapsing what was a 25-round manual dance into one call.
- Prefix size (tool-spec token cost) is cheap at cached rates; the money is in **rounds**, so when in doubt, design the tool to do more per call rather than adding a smaller sibling tool.
- Validate before emitting, and name the fix in the error message — this is what makes the difference between a 3-round fix and a diagnosis loop.
