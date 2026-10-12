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
  instance, then account, then file. Only the instance layer exists today."
  (:require
   [app.config :as cf]))

(defn instance-enabled?
  "True when this Penpot instance ships the design agent.

  Use it only where the answer must not depend on the user: route
  registration, and the places where the user turns the agent back on
  (Settings > Integrations)."
  []
  (contains? cf/flags :design-agent))

(defn agent-enabled?
  "Effective agent state for the current user, from the app `state`.

  Without `file-id` it answers at account scope; with it, for that file.
  Today it only reflects the instance flag."
  ([state]
   (agent-enabled? state nil))
  ([_state _file-id]
   (instance-enabled?)))
