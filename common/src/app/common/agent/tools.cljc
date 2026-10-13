;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.tools
  "The design agent's tools that run on the server with the render engine.

  Tool name → `(fn [view input options])`. `view` is what the tool works on:
  `:file-id`, the file `:data`, its `:libraries` (file id → file), and the
  `:page-id` and `:objects` of the page in view. `options` carries the `:guard`
  (the token-only-colors state) and the `:propagate` engine. A tool answers as
  described in `app.common.agent.write`."
  (:require
   [app.common.agent.bind :as cab]
   [app.common.agent.components :as cac]
   [app.common.agent.compose :as cap]
   [app.common.agent.document :as cad]
   [app.common.agent.icons :as cai]
   [app.common.agent.layout :as cal]
   [app.common.agent.media :as cam]
   [app.common.agent.pack-build :as cakb]
   [app.common.agent.recipe-build :as carb]
   [app.common.agent.structure :as cas]
   [app.common.agent.text :as catx]
   [app.common.agent.tokens :as cato]
   [app.common.agent.write :as caw]))

(def tools
  {"create_shape"     caw/create-shape
   "modify_shape"     caw/modify-shape
   "set_layout"       cal/set-layout
   "set_layout_child" cal/set-layout-child
   "update_shapes"    cas/update-shapes
   "nest_shape"       cas/nest-shape
   "create_line"      cas/create-line
   "delete_shape"     cas/delete-shape
   "duplicate_shape"  cas/duplicate-shape
   "group_shapes"     cas/group-shapes
   "ungroup_shapes"   cas/ungroup-shapes
   "mask_shapes"      cas/mask-shapes
   "unmask_shapes"    cas/unmask-shapes
   "create_boolean"   cas/create-boolean
   "create_from_svg"  cas/create-from-svg
   "create_component" cas/create-component
   "create_text"      catx/create-text
   "set_text"         catx/set-text
   "set_texts"        catx/set-texts
   "create_token"       cato/create-token
   "create_tokens"      cato/create-tokens
   "create_token_set"   cato/create-token-set
   "create_token_theme" cato/create-token-theme
   "apply_tokens"       cato/apply-tokens
   "activate_theme"     cato/activate-theme
   "create_variant"       cac/create-variant
   "create_instance"      cac/create-instance
   "detach_instance"      cac/detach-instance
   "reset_overrides"      cac/reset-overrides
   "swap_component"       cac/swap-component
   "switch_variant"       cac/switch-variant
   "add_variant"          cac/add-variant
   "set_variant_property" cac/set-variant-property
   "insert_image"  cam/insert-image
   "search_icons"  cam/search-icons
   "insert_icon"   cam/insert-icon
   "set_icons"     cai/set-icons
   "search_fonts"  cam/search-fonts
   "set_font"      cam/set-font
   "create_page"   cad/create-page
   "set_foundation" cad/set-foundation
   "clone_shape"   cap/clone-shape
   "build_tree"    (fn [view input options]
                     (cap/build-tree view input (assoc options :apply-tokens cato/apply-tokens)))
   "build_recipe"  carb/build-recipe
   "find_recipes"  carb/find-recipes
   "list_token_packs"    cakb/list-packs
   "generate_token_pack" cakb/generate-pack
   "apply_token_pack"    cakb/apply-pack})

(def needs
  "Tool name → `(fn [input])` → what the tool needs fetched from outside the
  file before it runs; see `app.common.agent.media`."
  {"insert_image" cam/insert-image-needs
   "search_icons" cam/search-icons-needs
   "insert_icon"  cam/insert-icon-needs
   "set_icons"    cai/set-icons-needs
   "clone_shape"  cap/clone-shape-needs
   "build_tree"   cap/build-tree-needs
   "build_recipe" carb/build-recipe-needs})


;; --- Design rules
;;
;; The one place a tool call is held against the file's design rules, before
;; the tool runs. A rule is about what the model asks for: under
;; token-only-colors it may not ask for a color that is not a token. Which
;; inputs of which tool carry a color is said here, tool by tool, so a tool
;; cannot forget to ask.

(def ^:private colors-asked
  {"create_shape"  caw/input-colors
   "modify_shape"  caw/input-colors
   "update_shapes" (fn [{:keys [updates]}]
                     (when (sequential? updates)
                       (mapcat #(when (map? %) (caw/input-colors %)) updates)))
   "build_tree"    (fn [{:keys [tree]}]
                     (when (map? tree)
                       (->> (tree-seq map? #(filter map? (:children %)) tree)
                            ;; a text's own color is not held to the rule, as
                            ;; with create_text
                            (remove #(= "text" (:type %)))
                            (mapcat caw/input-colors))))})

(defn rule-problem
  "Why the design rules refuse this call → `{:problem :rule}`, or nil. `guard`
  is the token-only-colors state: whether it is enforced, and what it allows."
  [tool input guard]
  (when-let [colors (get colors-asked tool)]
    (when-let [message (some #(caw/color-problem guard %) (colors input))]
      {:problem message :rule caw/color-rule})))

(def ^:private drawing-tools
  "The tools that write values on shapes by hand."
  #{"create_shape" "modify_shape" "update_shapes" "build_tree" "create_text" "set_layout"})

(defn run
  "Runs `tool`, unless a design rule refuses the call. What a drawing tool
  wrote that is exactly a role token's value is bound to that role, unless
  the call says to keep its values as written."
  [tool view input options]
  (or (rule-problem tool input (:guard options))
      (let [answer ((get tools tool) view input options)]
        (cond
          (not (contains? drawing-tools tool)) answer
          (true? (:keepLiterals input))        (cab/as-written view answer)
          :else                                (cab/with-roles view answer)))))
