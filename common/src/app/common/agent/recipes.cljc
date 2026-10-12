;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipes
  "The built-in recipes; see `app.common.agent.recipe` for the format.")

(def button
  {:recipe 1
   :name "button"
   :title "Button"
   :purpose "A pressable control that starts an action: submit, confirm, open, go on."
   :category "control"
   :keywords ["cta" "action" "submit" "link"]
   :params [{:name "label" :type "text" :default "Button"
             :description "The words on the button"}]
   :variants [{:name "Kind" :values ["Primary" "Secondary" "Ghost"]}
              {:name "Size" :values ["Medium" "Small"]}]
   :tree {:type "board"
          :name "Button"
          :layout {:type "flex" :dir "row" :alignItems "center" :justifyContent "center"}
          :layoutChild {:horizontalSizing "auto" :verticalSizing "auto"}
          :tokens {:fill "color.primary"
                   :borderRadius "radius.md"
                   :gap "spacing.sm"
                   :paddingX "spacing.lg"
                   :paddingY "spacing.md"}
          :vary [{:on {:Kind "Secondary"}
                  :set {:tokens {:fill "color.surface"
                                 :strokeColor "color.border"
                                 :strokeWidth "border-width.sm"}}}
                 {:on {:Kind "Ghost"}
                  :set {:tokens {:fill "color.background"}}}
                 {:on {:Size "Small"}
                  :set {:tokens {:paddingX "spacing.md" :paddingY "spacing.sm"}}}]
          :children [{:type "text"
                      :name "Label"
                      :text "{label}"
                      :tokens {:fill "color.on-primary"
                               :fontSize "font-size.sm"
                               :fontWeight "font-weight.medium"}
                      :vary [{:on {:Kind "Secondary"} :set {:tokens {:fill "color.text"}}}
                             {:on {:Kind "Ghost"} :set {:tokens {:fill "color.primary"}}}
                             {:on {:Size "Small"} :set {:tokens {:fontSize "font-size.xs"}}}]}]}})

(def card
  {:recipe 1
   :name "card"
   :title "Card"
   :purpose "A bordered surface that groups one subject: a title, a line about it, and room for more."
   :category "container"
   :keywords ["panel" "tile" "box" "surface"]
   :params [{:name "title" :type "text" :default "Card title"
             :description "The heading"}
            {:name "description" :type "text" :default "A line or two about this card."
             :description "The text under the heading"}
            {:name "media" :type "boolean" :default false
             :description "Leave room for a picture on top"}
            {:name "action" :type "boolean" :default false
             :description "End with a button"}
            {:name "actionLabel" :type "text" :default "Open"
             :description "The words on that button"}]
   :tree {:type "board"
          :name "Card"
          :width 320
          :layout {:type "flex" :dir "column"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.surface"
                   :strokeColor "color.border"
                   :strokeWidth "border-width.sm"
                   :borderRadius "radius.lg"}
          :children [{:type "rect"
                      :name "Media"
                      :when {:param "media"}
                      :height 160
                      :layoutChild {:horizontalSizing "fill"}
                      :tokens {:fill "color.surface-muted"}}
                     {:type "board"
                      :name "Body"
                      :slot "content"
                      :layout {:type "flex" :dir "column"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:padding "spacing.lg" :gap "spacing.sm"}
                      :children [{:type "text"
                                  :name "Title"
                                  :text "{title}"
                                  :layoutChild {:horizontalSizing "fill"}
                                  :tokens {:fill "color.text"
                                           :fontSize "font-size.lg"
                                           :fontWeight "font-weight.bold"}}
                                 {:type "text"
                                  :name "Description"
                                  :text "{description}"
                                  :layoutChild {:horizontalSizing "fill"}
                                  :tokens {:fill "color.text-muted"
                                           :fontSize "font-size.sm"}}
                                 {:type "recipe"
                                  :name "Action"
                                  :when {:param "action"}
                                  :use "button"
                                  :with {:label {:param "actionLabel"}}
                                  :variant {:Kind "Secondary"}}]}]}})

(def pricing-table
  {:recipe 1
   :name "pricing-table"
   :title "Pricing table"
   :purpose "Plans side by side, each with a name, a price, what it includes and a button to choose it."
   :category "section"
   :keywords ["plans" "tiers" "subscription" "billing"]
   :params [{:name "heading" :type "text" :default "Simple pricing"
             :description "The section's heading"}
            {:name "plans" :type "number" :default 3 :min 1 :max 4
             :description "How many plans"}
            {:name "features" :type "number" :default 4 :min 1 :max 8
             :description "How many lines each plan lists"}]
   :tree {:type "board"
          :name "Pricing"
          :width 1200
          :layout {:type "flex" :dir "column" :alignItems "center"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.background" :padding "spacing.3xl" :gap "spacing.2xl"}
          :children [{:type "text"
                      :name "Heading"
                      :text "{heading}"
                      :align "center"
                      :tokens {:fill "color.text"
                               :fontSize "font-size.2xl"
                               :fontWeight "font-weight.bold"}}
                     {:type "board"
                      :name "Plans"
                      :layout {:type "flex" :dir "row" :alignItems "stretch"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.xl"}
                      :children [{:type "board"
                                  :name "Plan {index}"
                                  :repeat {:param "plans"}
                                  :layout {:type "flex" :dir "column"}
                                  :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                                  :tokens {:fill "color.surface"
                                           :strokeColor "color.border"
                                           :strokeWidth "border-width.sm"
                                           :borderRadius "radius.lg"
                                           :padding "spacing.xl"
                                           :gap "spacing.lg"}
                                  :children [{:type "text"
                                              :name "Plan name"
                                              :text "Plan {index}"
                                              :tokens {:fill "color.text"
                                                       :fontSize "font-size.lg"
                                                       :fontWeight "font-weight.bold"}}
                                             {:type "text"
                                              :name "Price"
                                              :text "$0"
                                              :tokens {:fill "color.text"
                                                       :fontSize "font-size.2xl"
                                                       :fontWeight "font-weight.bold"}}
                                             {:type "board"
                                              :name "Features"
                                              :layout {:type "flex" :dir "column"}
                                              :layoutChild {:horizontalSizing "fill"
                                                            :verticalSizing "auto"}
                                              :tokens {:gap "spacing.sm"}
                                              :children [{:type "text"
                                                          :name "Feature {index}"
                                                          :repeat {:param "features"}
                                                          :text "Feature {index}"
                                                          :layoutChild {:horizontalSizing "fill"}
                                                          :tokens {:fill "color.text-muted"
                                                                   :fontSize "font-size.sm"}}]}
                                             {:type "recipe"
                                              :name "Choose"
                                              :use "button"
                                              :with {:label "Choose plan {index}"}
                                              :variant {:Kind "Primary"}
                                              :layoutChild {:horizontalSizing "fill"}}]}]}]}})

(def builtin
  "Name → recipe."
  (into {} (map (juxt :name identity)) [button card pricing-table]))
