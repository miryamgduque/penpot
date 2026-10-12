;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipes.navigation
  "The built-in recipes for navigation; see `app.common.agent.recipe` for
  the format. They say nothing of a platform: a platform profile adjusts
  them.")

(def top-bar
  {:recipe 1
   :name "top-bar"
   :title "Top bar"
   :purpose "The bar across the top of a page: the brand, the main places to go, and one action."
   :category "navigation"
   :keywords ["navbar" "header" "app bar" "menu" "nav"]
   :params [{:name "brand" :type "text" :default "Brand"
             :description "The name of the product or site"}
            {:name "links" :type "number" :default 4 :min 0 :max 6
             :description "How many places to go; the first is the current one"}
            {:name "actionLabel" :type "text" :default "Sign in"
             :description "The words on its button"}]
   :tree {:type "board"
          :name "Top bar"
          :width 1200
          :layout {:type "flex" :dir "row" :alignItems "center" :justifyContent "space-between"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.surface"
                   :paddingX "spacing.xl"
                   :paddingY "spacing.md"
                   :gap "spacing.xl"}
          :children [{:type "text"
                      :name "Brand"
                      :text "{brand}"
                      :tokens {:fill "color.text"
                               :fontSize "font-size.lg"
                               :fontWeight "font-weight.bold"}}
                     {:type "board"
                      :name "Links"
                      :layout {:type "flex" :dir "row" :alignItems "center"}
                      :layoutChild {:horizontalSizing "auto" :verticalSizing "auto"}
                      :tokens {:gap "spacing.xl"}
                      :children [{:type "text"
                                  :name "Link {index}"
                                  :repeat {:param "links"}
                                  :text "Link {index}"
                                  :tokens {:fill "color.text-muted"
                                           :fontSize "font-size.sm"
                                           :fontWeight "font-weight.medium"}
                                  :vary [{:at 1 :set {:tokens {:fill "color.text"}}}]}]}
                     {:type "board"
                      :name "Actions"
                      :slot "actions"
                      :layout {:type "flex" :dir "row" :alignItems "center"}
                      :layoutChild {:horizontalSizing "auto" :verticalSizing "auto"}
                      :tokens {:gap "spacing.sm"}
                      :children [{:type "recipe"
                                  :name "Action"
                                  :use "button"
                                  :with {:label {:param "actionLabel"}}
                                  :variant {:Kind "Primary" :Size "Small"}}]}]}})

(def sidebar
  {:recipe 1
   :name "sidebar"
   :title "Sidebar"
   :purpose "The column down the side of an app: the brand and the places to go, one of them current."
   :category "navigation"
   :keywords ["side nav" "drawer" "menu" "navigation rail" "nav"]
   :params [{:name "brand" :type "text" :default "Brand"
             :description "The name of the product"}
            {:name "items" :type "number" :default 5 :min 1 :max 10
             :description "How many places to go; the first is the current one"}]
   :tree {:type "board"
          :name "Sidebar"
          :width 240
          :height 800
          :layout {:type "flex" :dir "column"}
          :layoutChild {:horizontalSizing "fix" :verticalSizing "fix"}
          :tokens {:fill "color.surface" :padding "spacing.lg" :gap "spacing.xl"}
          :children [{:type "text"
                      :name "Brand"
                      :text "{brand}"
                      :tokens {:fill "color.text"
                               :fontSize "font-size.lg"
                               :fontWeight "font-weight.bold"}}
                     {:type "board"
                      :name "Items"
                      :layout {:type "flex" :dir "column"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.xs"}
                      :children [{:type "board"
                                  :name "Item {index}"
                                  :repeat {:param "items"}
                                  :layout {:type "flex" :dir "row" :alignItems "center"}
                                  :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                                  :tokens {:fill "color.surface"
                                           :borderRadius "radius.md"
                                           :gap "spacing.md"
                                           :paddingX "spacing.md"
                                           :paddingY "spacing.sm"}
                                  :vary [{:at 1 :set {:tokens {:fill "color.surface-muted"}}}]
                                  :children [{:type "rect"
                                              :name "Icon"
                                              :width 16
                                              :height 16
                                              :tokens {:fill "color.text-muted"}
                                              :vary [{:at 1 :set {:tokens {:fill "color.primary"}}}]}
                                             {:type "text"
                                              :name "Label"
                                              :text "Item {index}"
                                              :layoutChild {:horizontalSizing "fill"}
                                              :tokens {:fill "color.text-muted"
                                                       :fontSize "font-size.sm"
                                                       :fontWeight "font-weight.medium"}
                                              :vary [{:at 1 :set {:tokens {:fill "color.text"}}}]}]}]}
                     {:type "board"
                      :name "Footer"
                      :slot "footer"
                      :layout {:type "flex" :dir "column"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.sm"}
                      :children [{:type "text"
                                  :name "Footer text"
                                  :text "Account"
                                  :tokens {:fill "color.text-muted" :fontSize "font-size.xs"}}]}]}})

(def bottom-tab-bar
  {:recipe 1
   :name "bottom-tab-bar"
   :title "Bottom tab bar"
   :purpose "The bar at the bottom of a phone screen: an icon and a word for each main place, one current."
   :category "navigation"
   :keywords ["tab bar" "bottom navigation" "mobile nav" "phone" "app"]
   :params [{:name "tabs" :type "number" :default 4 :min 2 :max 5
             :description "How many places; the first is the current one"}]
   :tree {:type "board"
          :name "Bottom tab bar"
          :width 390
          :layout {:type "flex" :dir "row" :alignItems "center"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.surface" :paddingX "spacing.sm" :paddingY "spacing.sm"}
          :children [{:type "board"
                      :name "Tab {index}"
                      :repeat {:param "tabs"}
                      :layout {:type "flex" :dir "column" :alignItems "center"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.xs" :paddingY "spacing.xs"}
                      :children [{:type "rect"
                                  :name "Icon"
                                  :width 24
                                  :height 24
                                  :tokens {:fill "color.text-muted" :borderRadius "radius.sm"}
                                  :vary [{:at 1 :set {:tokens {:fill "color.primary"}}}]}
                                 {:type "text"
                                  :name "Label"
                                  :text "Tab {index}"
                                  :tokens {:fill "color.text-muted"
                                           :fontSize "font-size.xs"
                                           :fontWeight "font-weight.medium"}
                                  :vary [{:at 1 :set {:tokens {:fill "color.primary"}}}]}]}]}})

(def all [top-bar sidebar bottom-tab-bar])
