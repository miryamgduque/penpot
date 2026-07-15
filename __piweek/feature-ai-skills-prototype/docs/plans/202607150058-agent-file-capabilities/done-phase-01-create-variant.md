# Phase 01 — Create variant

**Status:** done

The headline capability: `create_variant` combines 2+ main components into a real Penpot
variant set. Everything else in this plan builds on it.

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions)
- [x] Re-read `dwv/combine-as-variants` — signature and silent-filter lines unchanged
- [x] Re-read `api.cljs:705` `createVariantFromComponents` — the reference caller to mirror
- [x] Confirm `variants/v1` is still in `default-features` — yes, `features.cljc:72`

## Checklist

- [x] Write tests for the validation branches (see Tests below) — they define the contract
- [x] Add the `create_variant` spec to `tool-specs`
- [x] Implement `create-variant` in `agent_tools.cljs`
- [x] Wire it into the `execute-tool` dispatch `case`
- [x] Lint pass — clj-kondo 0/0 and cljfmt clean (**not** `make lint/frontend`, see Notes)
- [x] Preview review: real variant set confirmed live in the assets panel and design tab
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Implementation

Mirror the plugin API, with validation added. The key trick to copy from `api.cljs:733`:
**pre-generate `variant-id` and pass it in.** Since the container's id *is* the variant-id,
the tool can return the id immediately without awaiting the async event chain.

```clojure
(defn- create-variant
  [{:keys [shapeIds]}]
  (let [state    @st/state
        objects  (dsh/lookup-page-objects state)
        ;; vector, not set: input order determines variant order
        ids      (if (seq shapeIds)
                   (into [] (keep parse-uuid) shapeIds)
                   (vec (dsh/get-selected-ids state)))]
    (if-let [problem (validate-variant-members objects ids)]
      (rx/throw (ex-info problem {}))
      (let [variant-id (uuid/next)]
        (interrupt!)
        (st/emit! (dwv/combine-as-variants
                   ids {:trigger "agent:create_variant" :variant-id variant-id}))
        (rx/of {:variantId (dm/str variant-id)
                :members (count ids)
                :note (str "variant set created — properties default to Property 1 / "
                           "Value 1; name them with set_variant_property. Verify with "
                           "read_design.")})))))
```

`validate-variant-members` is the heart of this phase. It must reject **before** emitting,
and each message must name the corrective action rather than merely stating the fault:

| Condition | Message |
|---|---|
| `< 2` ids | `create_variant: needs at least 2 main components (got N) — a variant set is a comparison between members` |
| shape not found | `create_variant: no shape with id X on this page` |
| not `ctc/main-instance?` | `create_variant: shape "Card" (X) is a <type>, not a main component — call create_component on it first, then pass the component's main instance` |
| `ctk/is-variant?` | `create_variant: shape "Card" (X) is already part of a variant set — use add_variant to extend that set` |
| ids on different pages | `create_variant: all components must be on the current page` |

That table is the phase. It is the difference between the agent self-correcting and the
agent inventing `Property=Value` frame names again.

Requires new `:require` entries: `[app.main.data.workspace.variants :as dwv]`,
`[app.common.types.component :as ctc]` and/or `[app.common.types.component :as ctk]` for
`main-instance?` / `is-variant?` — check which ns actually holds each predicate before
adding both.

**Tool description** (the agent's only spec of the contract — it must carry the precondition,
because a description that omits it invites exactly the failure the validation then rejects):

> Combines two or more **main components** into a Penpot variant set — the real thing, a
> variant container whose members switch by property. The shapes must already be main
> components (use `create_component` first) and must not already belong to a variant set.
> Input order determines variant order. Never emulate variants by naming layers
> `Prop=Value` — that is not a variant set. Asynchronous: verify with `read_design`.

## Tests

`frontend/test/frontend_tests/data/agent_tools_test.cljs` covers the pure helpers; put the
validation logic in a pure fn so it is testable without a store:

- [ ] rejects a single id, message mentions "at least 2"
- [ ] rejects a plain frame, message mentions `create_component`
- [ ] rejects an already-variant, message mentions `add_variant`
- [ ] accepts 2 valid main instances (returns nil / no problem)
- [ ] preserves input order (vector in → same order out)

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — new spec, `create-variant`, `validate-variant-members`, dispatch entry, requires
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — validation tests

## Notes — what execution found

**The plan's central claim about defaults was wrong, and it matters for Phase 03.**
Both the plan and the first draft of the tool said properties start as `Property 1` /
`Value 1`. Only half true: the axis *name* defaults to `Property 1`, but each member's
*value* is taken from its own component name. Live, two components named `Tag` and
`Tag Solid` produced `Property 1: Tag, Tag Solid` — already meaningful. The tool's
description and result note both claimed `Value 1` and were corrected; an agent told to
fix values that are already right would have made the set worse.

Phase 03 should reconsider its emphasis accordingly: the axis name is what's actually
missing (`Property 1` → `Size`), and the values often only need renaming when the source
component names were poor. `update-property-value` matters less than assumed.

**Verified live** (console-driven `execute-tool`, devenv :3450 — no LLM needed to exercise
a tool): container `is-variant-container: true`, its id equal to the pre-generated
`variant-id`, both members carrying that `variant-id`, the `#bb97d8` container stroke, one
badged entry in the assets panel, and `Property 1 | Tag, Tag Solid` in the design tab. The
full self-correcting loop works: two plain frames → rejected naming both → componentize one
→ rejected naming only the remaining one → componentize it → set created.

**Discoveries worth carrying forward:**

- `dm/str` is a **macro** — `(map dm/str xs)` fails to compile ("Can't take value of
  macro"). Use plain `str` when mapping. Every later phase builds messages this way.
- `variant-members-problem` must `distinct` its ids itself. `[a a]` reaches
  `combine-as-variants` as one id and no-ops silently; a test caught the helper counting
  the raw pair as two members.
- **`make lint/frontend` does not exist** — there is no Makefile. The real targets are
  `pnpm run lint:clj` (clj-kondo) and `check-fmt:clj` (cljfmt). Neither binary is on the
  host: run them in the devenv, cljfmt at `/opt/utils/bin/cljfmt`. Later phases should
  stop citing `make`.
- Tests went in a **new** `test/frontend_tests/data/agent_tools_test.cljs`, not
  `agent_test.cljs` as the plan said — that file's docstring scopes it to `agent.cljs`
  history invariants. Registering a test ns needs edits in **two** places in `runner.cljs`
  (the `:require` *and* the `test-namespaces` vector); require-only compiles but silently
  never runs.
- `create_component` renames the source board to "Component", so the assets panel lists the
  sets as "Component" rather than "Card". Pre-existing `dwl/add-component` behaviour, not
  introduced here, but it makes a library of agent-built components unreadable. Candidate
  follow-up; `dwv/rename-variant` (`variants.cljs:554`) is the lever, and Phase 03 is the
  natural home.

## Notes — from planning

`combine-as-variants` is event-composed, not changes-composed (there is an explicit TODO at
[variants.cljs:459](../../../../../frontend/src/app/main/data/workspace/variants.cljs)), so
this tool cannot be a single changes transaction — same constraint the plugin API lives with.
That is fine here: it is emitted inside the event's own undo transaction, so it stays one
undoable step for the user.

Do not attempt the `:add-component` change's variant fields
([changes.cljc:328-330](../../../../../common/src/app/common/files/changes.cljc), "Only used
by external processes (like Penpot SDK)"). That is the backend/SDK path; the panel is a
frontend caller and should go through the same event the UI does.
