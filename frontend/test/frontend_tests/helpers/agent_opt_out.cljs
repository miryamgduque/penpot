;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.helpers.agent-opt-out
  "Helpers for the tests that check the agent opt-out (#84)."
  (:require
   [app.main.store :as st]))

(defn with-store-state
  "Run `f` with the global store holding `state`, then put the old state
  back. The guard on model requests reads the store when a request goes
  out, so a test that expects a request must put its state there."
  [state f]
  (let [original @st/state]
    (try
      (reset! st/state state)
      (f)
      (finally
        (reset! st/state original)))))
