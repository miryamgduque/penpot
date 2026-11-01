;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.token-status
  "Which themes and sets of a file's token library are on.

  They are kept apart from the library, in the file's tokens status. A file
  saved before that keeps them in the library itself, and is read from
  there, as the workspace reads it."
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]))

(defn status
  "The tokens status of file `data`: what it holds, or what its library
  says when it holds none."
  [data]
  (or (:tokens-status data)
      (some-> (:tokens-lib data) cfo/make-tokens-status-from-lib)
      (ctos/make-tokens-status)))

(defn active-tokens
  "Token name → token, of the sets that are on in `data`'s own library, in
  set order; nil when it has no library."
  [data]
  (when-let [lib (:tokens-lib data)]
    (cfo/get-tokens-in-active-sets (status data) lib)))

(defn active-set-names
  "The names of the sets that are on in `data`'s own library."
  [data]
  (let [ids (ctos/get-active-set-ids (status data))]
    (into #{}
          (comp (filter #(contains? ids (ctob/get-id %)))
                (map ctob/get-name))
          (some-> (:tokens-lib data) ctob/get-sets))))

(defn theme-active?
  [data theme-id]
  (ctos/theme-active? (status data) theme-id))

(defn only-theme
  "A status with the theme `theme-id` of `lib` on, alone, and its sets."
  [lib theme-id]
  (let [theme   (ctob/get-theme lib theme-id)
        set-ids (into #{}
                      (comp (keep #(ctob/get-set-by-name lib %))
                            (map ctob/get-id))
                      (:sets theme))]
    (ctos/make-tokens-status :active-theme-ids #{theme-id} :active-set-ids set-ids)))

(defn only-sets
  "A status with no theme on and the sets `set-ids` on."
  [set-ids]
  (ctos/make-tokens-status :active-theme-ids #{} :active-set-ids (set set-ids)))
