;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.tasks.agent-changes-gc
  "Removes the record of what the design agent changed in a file once it
  is old. Each commit of an agent tool is kept with its changes and the
  changes that undo it, so a turn can be listed and reverted; past a while
  nobody reverts a turn, and the record would only grow."
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [integrant.core :as ig]))

;; By turn, not by row: a turn whose last commit is old goes whole. Cutting
;; it by row could keep half a turn, and reverting half a turn is not
;; taking it back.
(def ^:private sql:delete-old-agent-changes
  "DELETE FROM file_agent_change
    WHERE turn_id IN (SELECT turn_id
                        FROM file_agent_change
                       GROUP BY turn_id
                      HAVING max(created_at) < now() - ?::interval)")

(def default-max-age
  "How long a commit of the agent is kept, unless the instance says."
  (ct/duration {:days 30}))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool"))

(defmethod ig/expand-key ::handler
  [k v]
  {k (assoc v ::max-age (or (cf/get :agent-change-max-age) default-max-age))})

(defmethod ig/init-key ::handler
  [_ {:keys [::max-age] :as cfg}]
  (fn [{:keys [props]}]
    (let [max-age (or (:max-age props) max-age)]
      (-> cfg
          (assoc ::db/rollback (:rollback? props))
          (db/tx-run! (fn [{:keys [::db/conn]}]
                        (let [result (->> [sql:delete-old-agent-changes (db/interval max-age)]
                                          (db/exec-one! conn)
                                          (db/get-update-count))]
                          (l/debug :hint "agent changes removed" :total result)
                          result)))))))
