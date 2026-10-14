;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.agent-access
  "The one place that decides whether the design agent is on.

  Every agent surface asks `agent-enabled?` instead of reading the
  `:design-agent` flag, so that switching the agent off applies to all of
  them at once. Settings are layered and the most specific one wins:
  instance, then account, then file.

  Every path that calls a model must also ask it, with file scope, and do
  nothing when the answer is false.

  The account and file settings only hide the agent in this browser. It is not a
  security check: the server does not read it, and nothing here may stand
  in for a server-side check."
  (:require
   [app.config :as cf]
   [app.main.store :as st]
   [beicon.v2.core :as rx]))

(def agent-routes
  "Routes that only make sense while the agent is on. The route set is
  built once, before any profile is known, so these are guarded on
  navigation instead (see `route-allowed?`)."
  #{:dashboard-skills :dashboard-agent-skills})

(defn instance-enabled?
  "True when this Penpot instance ships the design agent.

  Use it only where the answer must not depend on the user: route
  registration, and the places where the user turns the agent back on
  (Settings > Integrations)."
  []
  (contains? cf/flags :design-agent))

(defn account-enabled?
  "True unless the user switched the agent off for their account.

  The profile prop is `:agent-disabled`, so a profile that never touched
  the switch (no key) reads as on."
  [state]
  (not (true? (get-in state [:profile :props :agent-disabled]))))

(defn file-override
  "The user's own setting for `file-id`, as loaded when the workspace opens
  (see `app.main.data.agent-switch`): true or false when they turned the
  agent on or off in that file, `:account` when the file follows the
  account, nil while it is not known."
  [state file-id]
  (when (some? file-id)
    (get-in state [:agent-file-state file-id])))

(defn agent-enabled?
  "Effective agent state for the current user, from the app `state`.

  Without `file-id` it answers at account scope; with it, for that file,
  where the user's file setting wins over the account one. While the file
  setting is not known (still loading, or it failed to load) the agent is
  off in that file: a file the user turned off must never call a model
  because its setting was late."
  ([state]
   (agent-enabled? state nil))
  ([state file-id]
   (and (instance-enabled?)
        (if (nil? file-id)
          (account-enabled? state)
          (let [override (file-override state file-id)]
            (cond
              (boolean? override)   override
              (= :account override) (account-enabled? state)
              :else                 false))))))

(defn can-write?
  "True when the current user can change the file open in the workspace.

  A different question from `agent-enabled?`, and independent of it: a
  person who may only view a file still has the agent. Every agent surface
  that offers a change asks this.

  It reads the edit capability the backend grants (`:can-edit` in the
  permissions loaded with the team), never a role name, so a later way in
  that only lets someone view answers false here without a change. A
  preview of an older version does not change it: that person can still
  write to the file, and a tool that cannot commit there says so itself."
  [state]
  (true? (get-in state [:permissions :can-edit])))

(defn route-allowed?
  "False for an agent-only route while the agent is off for the user."
  [state route-name]
  (or (not (contains? agent-routes route-name))
      (agent-enabled? state)))

(defn panel-open?
  "Whether the agent panel shows for the current file: open in state and
  the agent on for the user."
  [state]
  (when-let [file-id (:current-file-id state)]
    (and (true? (get-in state [:ai-panel file-id :open?]))
         (agent-enabled? state file-id))))

(def turned-off
  "Type of the event emitted when the agent goes off for the user in the
  file they are in (or, outside a file, for their account). The panel
  watcher and any running agent turn stop on it. Emitted by
  `app.main.data.agent-switch`."
  ::turned-off)

(defn guard-model-call
  "Wrap the request to a model provider: `make-call` (a function that
  returns the request's observable) runs only if the agent is on right
  now, where the user is (the current file, or the account outside a
  file). Otherwise nothing is sent and the stream fails with an
  `:agent-off` error.

  The event-level checks stop a call from starting. This one checks again
  when each request goes out, so a chain that began while the agent was on
  (a playbook match, a compaction, the next round of a turn) cannot send
  its next request after the user switched the agent off. Every transport
  that reaches a provider goes through it."
  [make-call]
  (rx/mapcat (fn [_]
               (let [state @st/state]
                 (if (agent-enabled? state (:current-file-id state))
                   (make-call)
                   (rx/throw (ex-info "The agent is off."
                                      {:type :restriction
                                       :code :agent-off
                                       :hint "the agent is off"})))))
             (rx/of nil)))
