;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.svg-parse
  "SVG markup → the tree the shared shape builder reads
  (`app.common.files.shapes-builder/create-svg-shapes`): `{:tag :attrs
  :content}` maps with keyword tags and attribute names, and text as plain
  strings. The workspace builds the same tree with its own XML parser."
  (:require
   ["xml-js" :as xml]))

(defn- node->clj
  [^js node]
  (case (unchecked-get node "type")
    "element"
    {:tag     (keyword (unchecked-get node "name"))
     :attrs   (let [attrs (unchecked-get node "attributes")]
                (if (some? attrs)
                  (into {}
                        (map (fn [k] [(keyword k) (unchecked-get attrs k)]))
                        (js/Object.keys attrs))
                  {}))
     :content (let [children (unchecked-get node "elements")]
                (if (some? children)
                  (into [] (keep node->clj) children)
                  []))}

    ("text" "cdata")
    (or (unchecked-get node "text") (unchecked-get node "cdata"))

    nil))

(defn parse
  "The parsed tree of an SVG document, or nil when `text` is not one."
  [text]
  (try
    (let [doc  (xml/xml2js text #js {:compact false})
          root (some->> (unchecked-get doc "elements")
                        (array-seq)
                        (filter #(= "element" (unchecked-get % "type")))
                        (first)
                        (node->clj))]
      (when (= :svg (:tag root))
        root))
    (catch :default _
      nil)))
