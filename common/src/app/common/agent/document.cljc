;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.document
  "The design agent's document tools that change the file, as pure functions;
  see `app.common.agent.write` for what a tool takes and answers. Comments and
  versions are not file changes: the backend runs those tools itself."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

(defn create-page
  "Adds an empty page, named as asked or `Page N`. The page in view stays the
  same."
  [{:keys [data]} {:keys [name]} _]
  (let [id     (uuid/next)
        wanted (when (and (string? name) (not (str/blank? name)))
                 (str/trim name))
        named  (or wanted
                   (cfh/generate-unique-name "Page"
                                             (cfh/get-used-names (:pages-index data))
                                             :immediate-suffix? true))]
    {:changes [{:type :add-page :id id :name named}]
     :result {:pageId (dm/str id)
              :note (str "page created" (when wanted (str " named \"" wanted "\""))
                         " — you are NOT on it; the other tools still act on the current "
                         "page. Use switch_page to move there.")}}))
