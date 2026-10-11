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
   [app.common.agent.layout :as cal]
   [app.common.agent.write :as caw]))

(def tools
  {"create_shape"     caw/create-shape
   "modify_shape"     caw/modify-shape
   "set_layout"       cal/set-layout
   "set_layout_child" cal/set-layout-child})
