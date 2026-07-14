;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools
  "The Agents' native design tools — the CLJS port of the plugin tools in
  `ai-skills/src/ui/agent.ts` + `plugin.ts`. Each tool reads or mutates the
  workspace through Penpot's internal APIs (no plugin runtime, no
  `execute_code`).

  `execute-tool` returns an rx observable of the tool result (a
  JSON-serializable value) or an observable that errors with a
  `{:rule …}`-tagged ex-info (used by the token-only-colors enforcement in a
  later phase). Read tools resolve synchronously; mutating tools (later
  phases) settle asynchronously through the changes pipeline.

  Phase 02 ships the read-only orientation tool `read_design`; structural,
  text/component, token, and audit tools arrive in the following phases."
  (:require
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.store :as st]
   [beicon.v2.core :as rx]))

;; --- Tool declarations (provider-agnostic; encoded per provider in agent.cljs)

(def tool-specs
  [{:name "read_design"
    :description
    (str "One-call orientation: the current file, page, selection and the "
         "page's top-level shapes. Call this FIRST each task to see what is in "
         "the file instead of guessing.")
    :input-schema {:type "object" :properties {}}}])

;; --- read_design

(defn- summarize-shape
  [objects id]
  (let [shape (get objects id)]
    {:id (dm/str id)
     :name (:name shape)
     :type (some-> (:type shape) name)
     :x (:x shape)
     :y (:y shape)
     :width (:width shape)
     :height (:height shape)}))

(defn- read-design
  []
  (let [state    @st/state
        file-id  (:current-file-id state)
        page     (dsh/lookup-page state)
        objects  (dsh/lookup-page-objects state)
        top-ids  (get-in objects [uuid/zero :shapes])
        selected (dsh/get-selected-ids state)]
    {:file (get-in state [:files file-id :name])
     :page (:name page)
     :selection (mapv #(summarize-shape objects %) selected)
     :shapes (mapv #(summarize-shape objects %) top-ids)
     ;; Seams filled by later phases:
     :colorTokens []     ; Phase 05 (token tools)
     :skills []          ; Phase 07 (skills resolution)
     :openViolations 0})) ; Phase 08 (audit_file)

;; --- Dispatch

(defn execute-tool
  [name _input]
  (case name
    "read_design" (rx/of (read-design))
    (rx/throw (ex-info (dm/str "Unknown tool: " name) {}))))
