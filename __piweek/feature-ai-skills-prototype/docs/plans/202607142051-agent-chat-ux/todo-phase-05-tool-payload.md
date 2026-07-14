# Phase 05 — Tool event payload

**Status:** todo

Invisible on its own — it exists to make Phase 06's expansion worth opening.

## Before Start

- [ ] Verify plan is still valid
- [ ] Read `agent.cljs:293-324` (`run-tool`, `tool-outcome->event`) and `data/workspace/ai_panel.cljs:66-79`
- [ ] Check no other caller depends on `append-tool`'s positional arity

## Checklist

- [ ] Add `:input` + `:result` to `tool-outcome->event`
- [ ] Change `append-tool` to a **map** arity
- [ ] Update the `send-message` call site (`:159-160`)
- [ ] Truncate `:result` for display (~2000 chars)
- [ ] Compile; existing behaviour unchanged (chips still render)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Phase 06 unblocked

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — `tool-outcome->event`
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — `append-tool`

## Notes

**The data already exists and is thrown away.** `tool-outcome->event` (`:297-303`) drops
`(:input (:call o))` and `(:content o)` on the floor — both are already on the outcome map built in
`run-tool` (`:312-324`). Two small edits:

```clojure
;; agent.cljs :297
{:kind :tool :name (:name (:call o)) :status (:status o)
 :rule (:rule o) :detail (:detail o)
 :input  (:input (:call o))
 :result (:content o)}          ; already truncated to 20k by result->content (:286-291)
```

**Take a map, not more positional args.** `append-tool`'s 4-arity is already at the edge; 6
positional args is worse. `(append-tool (dissoc ev :kind))` collapses the call site at `:159-160`
and makes future fields free.

**Truncate for display — this is a real memory concern, not a nit.** `max-tool-result-chars` is
20000 (`agent.cljs:260`) *per call*, and `max-rounds` is 32 (`:259`) → worst case ~640KB of tool
results retained in app state **per conversation, per file**, in `:messages` — which now
*duplicates* what `:history` already holds. Cap the stored display copy at ~2000 chars; the
transcript is a UI artifact, not the wire format. (`:result` is a JSON string from `result->content`,
`:286-291`.)

**Shape for the future, don't build it.** Include `:status` semantics that can carry `:running`
later, but **do not add a `:tool-start` event now**: tools are synchronous (`agent_tools.cljs` has
zero `rp/cmd!`, promises, timers or `rx/delay`; every tool reads `@st/state` and returns `rx/of` —
`execute-tool:503-516`, `create-shape:269`, `read-design:174`). So `rx/mapcat`'s concurrency is
nominal, `rx/reduce conj []` (`:331-333`) delays chips by ~0ms, and a spinner would render for zero
frames. All perceived latency is the `rp/cmd!` round-trip in `step` (`:345`).

It becomes worth it the moment any tool goes async (a backend-resolved skill, a server-side audit) —
at which point remove `rx/reduce conj []` and use an `rx/merge` that interleaves `:tool` events while
still collecting outcomes for the `:tool-results` message.
