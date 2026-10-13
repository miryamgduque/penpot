;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.bind
  "Binding what was drawn by hand to the file's design system.

  A value written on a shape that is exactly what a role token is worth — the
  surface's white, the medium spacing's 12 — is that token, written the long
  way. Left as written it looks right until the theme changes, and then it is
  the one thing that does not follow. So after a tool draws, each such value
  is bound to the role it equals: nothing looks different, and the shape now
  belongs to the system.

  Only roles (`app.common.agent.recipe/roles`) are bound to, only where the
  value is exactly equal, and only on what the call itself made or changed."
  (:require
   [app.common.agent.recipe :as car]
   [app.common.agent.tokens :as cato]
   [app.common.agent.write :as caw]
   [app.common.data :as d]
   [app.common.files.changes :as cfc]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctc]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [app.common.types.tokens-lib :as ctob]
   [cuerdas.core :as str]))

;; Where several roles are worth the same — white is the surface, the
;; background and what sits on the primary color — the one a property most
;; likely means comes first.

(def ^:private shape-fill-order
  ["color.surface" "color.background" "color.surface-muted" "color.primary" "color.border"
   "color.text" "color.danger" "color.success" "color.warning" "color.info"
   "color.on-primary" "color.text-muted"])

(def ^:private text-fill-order
  ["color.text" "color.text-muted" "color.on-primary" "color.primary"
   "color.danger" "color.success" "color.warning" "color.info"
   "color.background" "color.surface" "color.surface-muted" "color.border"])

(def ^:private stroke-order
  ["color.border" "color.text" "color.primary" "color.text-muted"
   "color.danger" "color.success" "color.warning" "color.info"
   "color.surface-muted" "color.surface" "color.background" "color.on-primary"])

(defn- role-worth
  "Role name → what it is worth in the file now, for the roles the file has a
  plain value for."
  [lib]
  (let [active (some-> lib ctob/get-tokens-in-active-sets)]
    (into {}
          (keep (fn [[token-name token]]
                  (when (contains? car/roles token-name)
                    (let [value (cato/resolved-value active token)]
                      (when (not= ::cato/unresolved value)
                        [token-name value])))))
          active)))

(defn- color-role
  [worth order color opacity]
  (when (and (string? color) (or (nil? opacity) (== 1 opacity)))
    (let [color (str/lower color)]
      (d/seek #(= color (:color (get worth %))) order))))

(defn- number-role
  "The role of `kind` (\"spacing\", \"radius\", \"font-size\") worth `n`."
  [worth kind n]
  (when (and (number? n) (pos? n))
    (d/seek (fn [role]
              (and (= kind (car/role-kind role))
                   (let [value (get worth role)] (and (number? value) (== value n)))))
            (sort (keys worth)))))

(defn- put
  "`m` with `k` → `v`, when there is a `v`."
  [m k v]
  (cond-> m (some? v) (assoc k v)))

(defn- runs
  [shape]
  (txt/node-seq txt/is-text-node? (:content shape)))

(defn- one
  "The value all of `values` share, or nil."
  [values]
  (when (and (seq values) (apply = values))
    (first values)))

(defn- family-role
  [worth family]
  (when (string? family)
    (d/seek (fn [role]
              (and (= "font-family" (car/role-kind role))
                   (= (str/lower family) (some-> (first (get worth role)) str/lower))))
            ["font-family.body" "font-family.heading"])))

(defn- found
  "Token property → role, for the values of `shape` that are exactly what a
  role is worth and are not bound yet."
  [worth shape]
  (let [bound (:applied-tokens shape)
        text? (cfh/text-shape? shape)
        fill  (if text?
                (one (map #(first (:fills %)) (runs shape)))
                (first (:fills shape)))
        stroke (first (:strokes shape))
        radius (one (map #(get shape %) [:r1 :r2 :r3 :r4]))
        size   (when text? (some-> (one (map :font-size (runs shape))) (d/parse-double)))
        family (when text? (one (map :font-family (runs shape))))
        layout? (ctl/any-layout? shape)]
    (cond-> {}
      (and (not (contains? bound :fill)) (nil? (:fill-color-gradient fill)) (nil? (:fill-image fill)))
      (put :fill (color-role worth (if text? text-fill-order shape-fill-order)
                             (:fill-color fill) (:fill-opacity fill)))

      (not (contains? bound :stroke-color))
      (put :stroke-color (color-role worth stroke-order (:stroke-color stroke) (:stroke-opacity stroke)))

      (not-any? #(contains? bound %) [:r1 :r2 :r3 :r4])
      (as-> $ (if-let [role (number-role worth "radius" radius)]
                (merge $ (zipmap [:r1 :r2 :r3 :r4] (repeat role)))
                $))

      (and text? (not (contains? bound :font-size)) (not (contains? bound :typography)))
      (put :font-size (number-role worth "font-size" size))

      (and text? (not (contains? bound :font-family)) (not (contains? bound :typography)))
      (put :font-family (family-role worth family))

      layout?
      (as-> $ (reduce (fn [found [attr value]]
                        (if (contains? bound attr)
                          found
                          (put found attr (number-role worth "spacing" value))))
                      $
                      (merge (:layout-padding shape) (:layout-gap shape)))))))

;; --- Content that keeps its values
;;
;; What a call drew with keepLiterals is content — a mock-up, a picture of
;; the product — and stays so: a later call that moves or edits it must not
;; bind it either. So it is marked, on the shape, and the mark holds for
;; everything inside it.

(def ^:private mark-path [:plugin-data :penpot-agent "keepLiterals"])

(defn- kept?
  "Whether `shape`, or a board it is inside of, was drawn to keep its values."
  [objects shape]
  (loop [shape shape]
    (cond
      (nil? shape)                       false
      (= "true" (get-in shape mark-path)) true
      (= (:id shape) (:parent-id shape)) false
      :else                              (recur (get objects (:parent-id shape))))))

(defn- touched-ids
  "The shapes of `page-id` that `changes` made or changed."
  [changes page-id]
  (into #{}
        (comp (filter #(and (contains? #{:add-obj :mod-obj} (:type %))
                            (= page-id (:page-id %))))
              (map :id))
        changes))

(defn with-roles
  "A drawing tool's `answer`, with what it drew bound to the roles its values
  equal. The answer is as it was when the file has no roles, or nothing
  matches."
  [{:keys [data page-id]} answer]
  (let [worth (role-worth (:tokens-lib data))]
    (if (or (empty? worth) (empty? (:changes answer)))
      answer
      (let [after   (cfc/process-changes data (:changes answer) false)
            objects (get-in after [:pages-index page-id :objects])
            binds   (into {}
                          (keep (fn [id]
                                  (when-let [shape (get objects id)]
                                    ;; a copy takes what its main has; binding
                                    ;; inside it would be an edit of the copy
                                    (when-not (or (ctc/in-component-copy? shape) (kept? objects shape))
                                      (when-let [roles (not-empty (found worth shape))]
                                        [id roles])))))
                          (touched-ids (:changes answer) page-id))]
        (if (empty? binds)
          answer
          (let [extra (-> (caw/page-changes page-id objects)
                          (cb/update-shapes (keys binds)
                                            (fn [shape] (update shape :applied-tokens merge (get binds (:id shape))))
                                            {:ignore-touched true})
                          (caw/redo))]
            (-> answer
                (assoc :changes (caw/then-changes (:changes answer) extra))
                (cond-> (map? (:result answer))
                  (assoc-in [:result :boundToRoles]
                            {:shapes (count binds)
                             :note (str "values you wrote that are exactly what a role token is worth were "
                                        "bound to that role, so they follow the theme. For content that "
                                        "must keep its own values whatever the theme — a mock-up inside "
                                        "a canvas, a brand's fixed color — pass keepLiterals: true.")})))))))))

;; --- A copy placed in a file takes the file's values
;;
;; A copy of a linked library's component arrives with the values the
;; library gave its main. It carries the names of the tokens, though, and
;; when the file has tokens of those names the copy is theirs to style: at
;; once, not at the next change of a token.

(defn- foreign?
  "Whether `shape` is, or is inside, a copy of a component of another file."
  [objects file-id shape]
  (loop [shape shape]
    (cond
      (nil? shape)                       false
      (some? (:component-file shape))    (not= file-id (:component-file shape))
      (= (:id shape) (:parent-id shape)) false
      :else                              (recur (get objects (:parent-id shape))))))

(defn with-file-values
  "A placing tool's `answer`, with the copies of a linked library's
  components it placed given the values this file's tokens have. The answer
  is as it was when it placed none, or the file has no token they name."
  [{:keys [file-id data page-id fonts]} answer engine]
  (let [made (into []
                   (comp (filter #(and (= :add-obj (:type %)) (= page-id (:page-id %))))
                         (map :id))
                   (:changes answer))]
    (if (or (empty? made) (nil? (:tokens-lib data)))
      answer
      (let [after   (cfc/process-changes data (:changes answer) false)
            objects (get-in after [:pages-index page-id :objects])
            ids     (filterv (fn [id]
                               (let [shape (get objects id)]
                                 (and (seq (:applied-tokens shape)) (foreign? objects file-id shape))))
                             made)
            out     (when (seq ids)
                      (cato/rebind-shapes after page-id objects ids (assoc engine :fonts fonts)))]
        (cond
          (contains? out :pending) (select-keys out [:pending])
          (contains? out :changes) (assoc answer :changes (caw/then-changes (:changes answer) (:changes out)))
          ;; a value this host cannot write is left as the library had it
          :else                    answer)))))

(defn as-written
  "A drawing tool's `answer` to a call that said keepLiterals: what it made
  is marked as content that keeps its values."
  [{:keys [data page-id]} answer]
  (let [made (into []
                   (comp (filter #(and (= :add-obj (:type %)) (= page-id (:page-id %))))
                         (map :id))
                   (:changes answer))]
    (if (empty? made)
      answer
      (let [after   (cfc/process-changes data (:changes answer) false)
            objects (get-in after [:pages-index page-id :objects])
            extra   (-> (caw/page-changes page-id objects)
                        (cb/update-shapes made #(assoc-in % mark-path "true") {:ignore-touched true})
                        (caw/redo))]
        (assoc answer :changes (caw/then-changes (:changes answer) extra))))))
