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
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
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

(def ^:private schema:params
  "max-age: a duration, or integer millis or a duration string when it
  comes through the job pipeline."
  [:map
   [:max-age {:optional true} [:or :int :string ::ct/duration]]])

(defn execute
  "Deletes the turns whose newest commit is older than the max age → how
  many rows went."
  [cfg params]
  (let [max-age (ct/duration (or (:max-age params)
                                 (cf/get :agent-change-max-age)
                                 default-max-age))]
    (db/tx-run! cfg
                (fn [{:keys [::db/conn]}]
                  (let [result (->> [sql:delete-old-agent-changes (db/interval max-age)]
                                    (db/exec-one! conn)
                                    (db/get-update-count))]
                    (l/dbg :hint "agent changes removed" :total result)
                    result)))))

(defmethod ig/assert-key ::job-def
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool"))

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name      :agent-changes-gc
   ::jobs/schema    schema:params
   ::jobs/handler   (fn [_context params] (execute cfg params))
   ::jobs/decoder   (sm/decoder schema:params sm/json-transformer)
   ::jobs/validator (sm/validator schema:params)})
