;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipes
  "The built-in recipes; see `app.common.agent.recipe` for the format."
  (:require
   [app.common.agent.recipes.containers :as containers]
   [app.common.agent.recipes.controls :as controls]
   [app.common.agent.recipes.more-containers :as more-containers]
   [app.common.agent.recipes.more-controls :as more-controls]
   [app.common.agent.recipes.more-navigation :as more-navigation]
   [app.common.agent.recipes.more-sections :as more-sections]
   [app.common.agent.recipes.navigation :as navigation]
   [app.common.agent.recipes.screens :as screens]
   [app.common.agent.recipes.sections :as sections]))

(def button controls/button)

(def card containers/card)

(def pricing-table sections/pricing-table)

(def all
  "The catalog, in the order it is shown: controls, containers, navigation,
  sections."
  (into [] cat [controls/all containers/all navigation/all sections/all
                more-controls/all more-containers/all more-navigation/all more-sections/all
                screens/all]))

(def builtin
  "Name → recipe."
  (into {} (map (juxt :name identity)) all))
