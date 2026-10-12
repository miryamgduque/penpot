;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipes.profiles
  "The built-in platform profiles; see `app.common.agent.recipe` for what a
  profile is. Each says what a platform asks of the navigation recipes, and
  which variant of a control it places.")

(def ios
  {:profile 1
   :name "ios"
   :title "iOS"
   :purpose "An iPhone app: a 393pt wide screen, a navigation bar with a title and one action, a tab bar with larger icons, rows tall enough to tap."
   :recipes {"top-bar"        {:params {:links 0}
                               :adjust [{:layer "Top bar"
                                         :set {:width 393
                                               :tokens {:paddingX "spacing.lg" :paddingY "spacing.sm"}}}
                                        {:layer "Brand"
                                         :set {:tokens {:fontSize "font-size.md"}}}
                                        {:layer "Action"
                                         :set {:variant {:Kind "Ghost" :Size "Small"}}}]}
             "bottom-tab-bar" {:params {:tabs 5}
                               :adjust [{:layer "Bottom tab bar"
                                         :set {:width 393
                                               :tokens {:paddingX "spacing.xs" :paddingY "spacing.xs"}}}
                                        {:layer "Icon"
                                         :set {:width 28 :height 28}}]}
             "sidebar"        {:adjust [{:layer "Sidebar"
                                         :set {:width 320 :height 852}}
                                        {:layer "Item {index}"
                                         :set {:tokens {:paddingY "spacing.md"}}}
                                        {:layer "Icon"
                                         :set {:width 24 :height 24}}]}
             "button"         {:variant {:Size "Medium"}}}})

(def web
  {:profile 1
   :name "web"
   :title "Web"
   :purpose "A desktop web page: a 1440px wide screen, a roomy top bar with its links, a narrow sidebar with dense rows, smaller buttons."
   :recipes {"top-bar"        {:adjust [{:layer "Top bar"
                                         :set {:width 1440
                                               :tokens {:paddingX "spacing.2xl" :paddingY "spacing.lg"}}}
                                        {:layer "Links"
                                         :set {:tokens {:gap "spacing.2xl"}}}]}
             "bottom-tab-bar" {:params {:tabs 4}
                               :adjust [{:layer "Bottom tab bar"
                                         :set {:width 480}}]}
             "sidebar"        {:params {:items 6}
                               :adjust [{:layer "Sidebar"
                                         :set {:width 256 :height 900}}
                                        {:layer "Item {index}"
                                         :set {:tokens {:paddingY "spacing.xs"}}}]}
             "button"         {:variant {:Size "Small"}}}})

(def all [ios web])

(def builtin
  "Name → profile."
  (into {} (map (juxt :name identity)) all))
