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
   [app.common.agent.components :as cac]
   [app.common.agent.layout :as cal]
   [app.common.agent.media :as cam]
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
   "create_token"       cato/create-token
   "create_tokens"      cato/create-tokens
   "create_token_set"   cato/create-token-set
   "create_token_theme" cato/create-token-theme
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
   "search_fonts"  cam/search-fonts
   "set_font"      cam/set-font})

(def needs
  "Tool name → `(fn [input])` → what the tool needs fetched from outside the
  file before it runs; see `app.common.agent.media`."
  {"insert_image" cam/insert-image-needs
   "search_icons" cam/search-icons-needs
   "insert_icon"  cam/insert-icon-needs})
