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
   [app.config :as cf]))

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
