;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.session-actor
  "Who is acting right now: a person, or the agent on a named model.

  Phase 03 of the design-session-recording plan. Deliberately dependency-free so
  the low-level commit pipeline (`app.main.data.changes`) can read it without
  requiring anything from the agent namespaces.

  ## Why an ambient marker rather than tagging each write

  The agent has no mutation funnel. Roughly 22 write sites across the
  `agent_tools/*` families reach the document through at least nine different
  paths (`dch/commit-changes`, `dwsh/update-shapes`, `dwsh/relocate-shapes`,
  `dws/duplicate-shapes`, `dwg/group-shapes`, the `dwsl/*` layout events,
  `dwt/*` transforms, `dwl/add-component`, `dwm/create-svg-shape`). Threading
  provenance through all of them would be invasive and easy to miss. Instead the
  agent's turn loop marks \"an agent action is in flight\" around each tool, and
  `commit-changes` stamps whatever is marked.

  An atom rather than a dynamic `binding`: every one of those write paths is
  async, and a CLJS `binding` does not survive an async boundary.

  ## Two timing properties, both load-bearing

  **The marker outlives the tool.** Writes settle after a tool's observable
  completes — `reflow-parent!` emits a `:layout/update` that `shape-layout.cljs`
  buffers by 100ms, so a `create_shape` into a laid-out board produces its
  reposition commits after the tool has returned. Clearing on completion would
  hand the agent's own reflow to whoever edits next. Hence `grace-ms`.

  **The marker expires by itself.** A turn cancelled mid-tool unsubscribes
  (`rx/take-until`) and never runs its cleanup. A marker stuck on `:agent` would
  then misattribute every human edit for the rest of the session — a far worse
  failure than missing a reflow. So expiry is intrinsic: `current-actor` is a
  function of the clock, not of cleanup having run. `max-action-ms` is the
  backstop ceiling; the turn teardown also clears explicitly, but correctness
  does not depend on it.

  ## What it deliberately does not carry

  No tool name. Tools can overlap (`run-tool` runs under `rx/mapcat`, which
  interleaves), so a single slot cannot honestly say *which* tool produced a
  given write. Provider and model are shared by every tool in a turn, so those
  are safe to report; a tool name would be a guess. Nothing downstream needs it."
  (:require
   [app.common.data :as d]))

(def grace-ms
  "How long an agent action keeps ownership of new commits after its tool
  finishes. Must exceed shape-layout's 100ms `:layout/update` buffer, with room
  for the transform-modifier / WASM tick on top — the same reasoning behind
  `modify_shape`'s 220ms settle wait.

  The residual risk is the mirror image: a person editing within this window of
  the agent's last tool is recorded as the agent. That needs a human to act
  inside 400ms of a tool completing, which in practice means before they can
  have seen the result."
  400)

(def max-action-ms
  "Ceiling on a single agent action, after which the marker releases itself even
  if `end-agent-action!` never ran (a cancelled turn). Long enough not to cut
  short a slow tool — `screenshot_page` and `fetch_page` make network round
  trips — short enough that a leak is a blip rather than a corrupted session."
  30000)

(defn- now-ms
  []
  (inst-ms (js/Date.)))

;; `:gen` increments per action so a finishing tool can only affect its own
;; marker; `:until` is when the current marker stops applying.
(defonce ^:private state*
  (atom {:gen 0 :actor nil :until 0}))

(defn current-actor
  "The agent identity to stamp on a commit right now, or nil when a person is
  editing. Pure in `now` so the timing rules are testable without waiting."
  ([] (current-actor (now-ms)))
  ([now]
   (let [{:keys [actor until]} @state*]
     (when (and (some? actor) (< now until))
       actor))))

(defn begin-agent-action!
  "Mark the agent as acting on `settings`' provider/model. Returns a generation
  token to hand back to `end-agent-action!`."
  ([settings] (begin-agent-action! settings (now-ms)))
  ([{:keys [provider model]} now]
   (-> (swap! state*
              (fn [{:keys [gen]}]
                {:gen   (inc gen)
                 :actor {:who :agent :provider provider :model model}
                 :until (+ now max-action-ms)}))
       (get :gen))))

(defn end-agent-action!
  "The tool finished: start the grace countdown. A no-op when `gen` is not the
  newest action, so an overlapping tool finishing cannot cut short the one still
  running."
  ([gen] (end-agent-action! gen (now-ms)))
  ([gen now]
   (swap! state*
          (fn [state]
            (if (= gen (:gen state))
              (assoc state :until (+ now grace-ms))
              state)))
   nil))

(defn reset-actor!
  "Drop any marker immediately. Called on turn teardown — belt to the expiry
  braces — and between tests."
  []
  (reset! state* {:gen 0 :actor nil :until 0})
  nil)

(defn stamp
  "Merge the acting agent onto a commit params map. `:who` is always present so
  consumers need no nil-punning; provenance keys already on the map
  (`:profile-id`, `:session-id`) are untouched — an agent action is still
  attributable to the person whose session asked for it."
  [params]
  (let [actor (current-actor)]
    (-> params
        (assoc :who (d/nilv (:who actor) :user))
        (assoc :provider (:provider actor))
        (assoc :model (:model actor)))))
