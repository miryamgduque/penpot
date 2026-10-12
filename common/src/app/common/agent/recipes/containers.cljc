;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipes.containers
  "The built-in recipes for containers; see `app.common.agent.recipe` for
  the format. A container that is to be filled is a plain recipe with a slot;
  one that is always the same inside is a component.")

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
                   :borderRadius "radius.lg"
                   :shadow "shadow.sm"}
          :children [{:type "rect"
                      :name "Media"
                      :placeholder "image"
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
                                           :fontFamily "font-family.body"
                                           :fontWeight "font-weight.bold"}}
                                 {:type "text"
                                  :name "Description"
                                  :text "{description}"
                                  :layoutChild {:horizontalSizing "fill"}
                                  :tokens {:fill "color.text-muted"
                                           :fontSize "font-size.sm"
                                           :fontFamily "font-family.body"}}
                                 {:type "recipe"
                                  :name "Action"
                                  :when {:param "action"}
                                  :use "button"
                                  :with {:label {:param "actionLabel"}}
                                  :variant {:Kind "Secondary"}}]}]}})

(def list-item
  {:recipe 1
   :name "list-item"
   :title "List item"
   :purpose "One row of a list: who or what it is, a line about it, and a way in."
   :category "container"
   :keywords ["row" "cell" "entry" "list" "contact" "setting"]
   :params [{:name "title" :type "text" :default "List item"
             :description "The main line"}
            {:name "subtitle" :type "text" :default "Supporting text"
             :description "The line under it"}
            {:name "initials" :type "text" :default "AB"
             :description "The letters in its avatar"}]
   :variants [{:name "State" :values ["Default" "Selected"]}]
   :tree {:type "board"
          :name "List item"
          :width 360
          :layout {:type "flex" :dir "row" :alignItems "center"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.surface"
                   :gap "spacing.md"
                   :paddingX "spacing.lg"
                   :paddingY "spacing.md"}
          :vary [{:on {:State "Selected"} :set {:tokens {:fill "color.surface-muted"}}}]
          :children [{:type "recipe"
                      :name "Avatar"
                      :use "avatar"
                      :with {:initials {:param "initials"}}}
                     {:type "board"
                      :name "Text"
                      :layout {:type "flex" :dir "column"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.xs"}
                      :children [{:type "text"
                                  :name "Title"
                                  :text "{title}"
                                  :layoutChild {:horizontalSizing "fill"}
                                  :tokens {:fill "color.text"
                                           :fontSize "font-size.sm"
                                           :fontFamily "font-family.body"
                                           :fontWeight "font-weight.medium"}}
                                 {:type "text"
                                  :name "Subtitle"
                                  :text "{subtitle}"
                                  :layoutChild {:horizontalSizing "fill"}
                                  :tokens {:fill "color.text-muted" :fontSize "font-size.xs"
                                           :fontFamily "font-family.body"}}]}
                     {:type "recipe"
                      :name "Chevron"
                      :placeholder "icon"
                      :use "icon"
                      :variant {:Size "Small"}}]}})

(def tabs
  {:recipe 1
   :name "tabs"
   :title "Tabs"
   :purpose "A row of labels that switch between views of the same place; the first one is open."
   :category "container"
   :keywords ["tab bar" "segmented" "views" "switcher" "sections"]
   :params [{:name "tabs" :type "number" :default 3 :min 2 :max 6
             :description "How many tabs"}]
   :tree {:type "board"
          :name "Tabs"
          :width 480
          :layout {:type "flex" :dir "column"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:gap "spacing.lg"}
          :children [{:type "board"
                      :name "Tab list"
                      :layout {:type "flex" :dir "row" :alignItems "end"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.xl"}
                      :children [{:type "recipe"
                                  :name "Tab {index}"
                                  :repeat {:param "tabs"}
                                  :use "tab"
                                  :with {:label "Tab {index}"}
                                  :vary [{:at 1 :set {:variant {:State "Current"}}}]}]}
                     {:type "board"
                      :name "Panel"
                      :slot "panel"
                      :layout {:type "flex" :dir "column"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.md"}
                      :children [{:type "text"
                                  :name "Panel text"
                                  :text "What the open tab shows."
                                  :layoutChild {:horizontalSizing "fill"}
                                  :tokens {:fill "color.text-muted" :fontSize "font-size.sm"
                                           :fontFamily "font-family.body"}}]}]}})

(def alert
  {:recipe 1
   :name "alert"
   :title "Alert"
   :purpose "A message that needs to be noticed: something happened, went wrong or is about to."
   :category "container"
   :keywords ["banner" "notice" "toast" "warning" "error" "success" "message"]
   :params [{:name "title" :type "text" :default "Heads up"
             :description "What happened, in a few words"}
            {:name "message" :type "text" :default "Something you should know about."
             :description "What it means or what to do"}]
   :variants [{:name "Tone" :values ["Info" "Success" "Warning" "Danger"]}]
   :tree {:type "board"
          :name "Alert"
          :width 480
          :layout {:type "flex" :dir "row" :alignItems "start"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.surface"
                   :strokeColor "color.info"
                   :strokeWidth "border-width.sm"
                   :borderRadius "radius.md"
                   :gap "spacing.md"
                   :padding "spacing.lg"}
          :vary [{:on {:Tone "Success"} :set {:tokens {:strokeColor "color.success"}}}
                 {:on {:Tone "Warning"} :set {:tokens {:strokeColor "color.warning"}}}
                 {:on {:Tone "Danger"} :set {:tokens {:strokeColor "color.danger"}}}]
          :children [{:type "ellipse"
                      :name "Icon"
                      :placeholder "icon"
                      :width 20
                      :height 20
                      :tokens {:fill "color.info"}
                      :vary [{:on {:Tone "Success"} :set {:tokens {:fill "color.success"}}}
                             {:on {:Tone "Warning"} :set {:tokens {:fill "color.warning"}}}
                             {:on {:Tone "Danger"} :set {:tokens {:fill "color.danger"}}}]}
                     {:type "board"
                      :name "Text"
                      :layout {:type "flex" :dir "column"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.xs"}
                      :children [{:type "text"
                                  :name "Title"
                                  :text "{title}"
                                  :layoutChild {:horizontalSizing "fill"}
                                  :tokens {:fill "color.text"
                                           :fontSize "font-size.sm"
                                           :fontFamily "font-family.body"
                                           :fontWeight "font-weight.bold"}}
                                 {:type "text"
                                  :name "Message"
                                  :text "{message}"
                                  :layoutChild {:horizontalSizing "fill"}
                                  :tokens {:fill "color.text-muted" :fontSize "font-size.sm"
                                           :fontFamily "font-family.body"}}]}]}})

(def modal
  {:recipe 1
   :name "modal"
   :title "Modal"
   :purpose "A dialog over the page that asks for a decision or a few inputs before going on."
   :category "container"
   :keywords ["dialog" "popup" "confirm" "overlay" "sheet"]
   :params [{:name "title" :type "text" :default "Are you sure?"
             :description "The question or the task"}
            {:name "description" :type "text" :default "This is what will happen if you go on."
             :description "What the decision means"}
            {:name "confirmLabel" :type "text" :default "Confirm"
             :description "The words on the button that goes on"}
            {:name "cancelLabel" :type "text" :default "Cancel"
             :description "The words on the button that backs out"}]
   :tree {:type "board"
          :name "Modal"
          :width 480
          :layout {:type "flex" :dir "column"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.surface"
                   :strokeColor "color.border"
                   :strokeWidth "border-width.sm"
                   :borderRadius "radius.lg"
                   :padding "spacing.xl"
                   :gap "spacing.lg"
                   :shadow "shadow.lg"}
          :children [{:type "text"
                      :name "Title"
                      :text "{title}"
                      :layoutChild {:horizontalSizing "fill"}
                      :tokens {:fill "color.text"
                               :fontSize "font-size.lg"
                               :fontFamily "font-family.body"
                               :fontWeight "font-weight.bold"}}
                     {:type "board"
                      :name "Body"
                      :slot "body"
                      :layout {:type "flex" :dir "column"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.md"}
                      :children [{:type "text"
                                  :name "Description"
                                  :text "{description}"
                                  :layoutChild {:horizontalSizing "fill"}
                                  :tokens {:fill "color.text-muted" :fontSize "font-size.sm"
                                           :fontFamily "font-family.body"}}]}
                     {:type "board"
                      :name "Actions"
                      :layout {:type "flex" :dir "row" :alignItems "center" :justifyContent "end"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.sm"}
                      :children [{:type "recipe"
                                  :name "Cancel"
                                  :use "button"
                                  :with {:label {:param "cancelLabel"}}
                                  :variant {:Kind "Secondary"}}
                                 {:type "recipe"
                                  :name "Confirm"
                                  :use "button"
                                  :with {:label {:param "confirmLabel"}}
                                  :variant {:Kind "Primary"}}]}]}})

(def tab
  {:recipe 1
   :name "tab"
   :title "Tab"
   :purpose "One tab of a row of tabs: its label and the mark that shows it is the open one."
   :category "container"
   :keywords ["tab item" "segment"]
   :params [{:name "label" :type "text" :default "Tab"
             :description "The words on the tab"}]
   :variants [{:name "State" :values ["Default" "Current"]}]
   :tree {:type "board"
          :name "Tab"
          :layout {:type "flex" :dir "column" :alignItems "center"}
          :layoutChild {:horizontalSizing "auto" :verticalSizing "auto"}
          :tokens {:gap "spacing.sm"}
          :children [{:type "text"
                      :name "Label"
                      :text "{label}"
                      :tokens {:fill "color.text-muted"
                               :fontSize "font-size.sm"
                               :fontFamily "font-family.body"
                               :fontWeight "font-weight.medium"}
                      :vary [{:on {:State "Current"} :set {:tokens {:fill "color.primary"}}}]}
                     {:type "rect"
                      :name "Indicator"
                      :height 2
                      :layoutChild {:horizontalSizing "fill"}
                      :tokens {:fill "color.background"}
                      :vary [{:on {:State "Current"} :set {:tokens {:fill "color.primary"}}}]}]}})

(def plan-card
  {:recipe 1
   :name "plan-card"
   :title "Plan card"
   :purpose "One plan of a pricing section: its name, its price, what it includes and the button that chooses it."
   :category "container"
   :keywords ["tier" "price card"]
   :params [{:name "name" :type "text" :default "Plan"
             :description "The plan's name"}
            {:name "price" :type "text" :default "$0"
             :description "What it costs"}
            {:name "cta" :type "text" :default "Choose plan"
             :description "The words on its button"}
            {:name "features" :type "number" :default 4 :min 1 :max 8
             :description "How many lines it lists"}]
   :variants [{:name "Emphasis" :values ["Default" "Featured"]}]
   :tree {:type "board"
          :name "Plan card"
          :width 340
          :layout {:type "flex" :dir "column"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.surface"
                   :strokeColor "color.border"
                   :strokeWidth "border-width.sm"
                   :borderRadius "radius.lg"
                   :padding "spacing.xl"
                   :gap "spacing.lg"}
          :vary [{:on {:Emphasis "Featured"}
                  :set {:tokens {:strokeColor "color.primary" :strokeWidth "border-width.md"}}}]
          :children [{:type "text"
                      :name "Plan name"
                      :text "{name}"
                      :tokens {:fill "color.text"
                               :fontSize "font-size.lg"
                               :fontFamily "font-family.body"
                               :fontWeight "font-weight.bold"}}
                     {:type "text"
                      :name "Price"
                      :text "{price}"
                      :tokens {:fill "color.text"
                               :fontSize "font-size.2xl"
                               :fontFamily "font-family.heading"
                               :fontWeight "font-weight.bold"}}
                     {:type "board"
                      :name "Features"
                      :layout {:type "flex" :dir "column"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.sm"}
                      :children [{:type "text"
                                  :name "Feature {index}"
                                  :repeat {:param "features"}
                                  :text "Feature {index}"
                                  :layoutChild {:horizontalSizing "fill"}
                                  :tokens {:fill "color.text-muted" :fontSize "font-size.sm"
                                           :fontFamily "font-family.body"}}]}
                     {:type "recipe"
                      :name "Choose"
                      :use "button"
                      :with {:label {:param "cta"}}
                      :variant {:Kind "Secondary"}
                      :vary [{:on {:Emphasis "Featured"} :set {:variant {:Kind "Primary"}}}]
                      :layoutChild {:horizontalSizing "fill"}}]}})

(def feature-item
  {:recipe 1
   :name "feature-item"
   :title "Feature item"
   :purpose "One feature of a product: an icon, its name and a line about it."
   :category "container"
   :keywords ["benefit" "highlight" "feature"]
   :params [{:name "title" :type "text" :default "Feature"
             :description "The feature's name"}
            {:name "description" :type "text" :default "A line or two about what this does for you."
             :description "The line about it"}]
   :component true
   :tree {:type "board"
          :name "Feature item"
          :width 352
          :layout {:type "flex" :dir "column"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:gap "spacing.sm"}
          :children [{:type "recipe"
                      :name "Icon"
                      :placeholder "icon"
                      :use "icon"
                      :variant {:Size "Large" :Tone "Primary"}}
                     {:type "text"
                      :name "Name"
                      :text "{title}"
                      :layoutChild {:horizontalSizing "fill"}
                      :tokens {:fill "color.text"
                               :fontSize "font-size.lg"
                               :fontFamily "font-family.body"
                               :fontWeight "font-weight.bold"}}
                     {:type "text"
                      :name "Description"
                      :text "{description}"
                      :layoutChild {:horizontalSizing "fill"}
                      :tokens {:fill "color.text-muted" :fontSize "font-size.sm"
                               :fontFamily "font-family.body"}}]}})

(def stat-card
  {:recipe 1
   :name "stat-card"
   :title "Stat card"
   :purpose "One key number: what it measures, the number, and how it moved."
   :category "container"
   :keywords ["metric" "kpi" "number" "figure"]
   :params [{:name "label" :type "text" :default "Metric"
             :description "What the number measures"}
            {:name "value" :type "text" :default "0"
             :description "The number"}
            {:name "change" :type "text" :default "+0%"
             :description "How it moved"}]
   :component true
   :tree {:type "board"
          :name "Stat card"
          :width 280
          :layout {:type "flex" :dir "column" :alignItems "start"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.surface"
                   :strokeColor "color.border"
                   :strokeWidth "border-width.sm"
                   :borderRadius "radius.lg"
                   :padding "spacing.xl"
                   :gap "spacing.sm"}
          :children [{:type "text"
                      :name "Label"
                      :text "{label}"
                      :tokens {:fill "color.text-muted" :fontSize "font-size.sm"
                               :fontFamily "font-family.body"}}
                     {:type "text"
                      :name "Value"
                      :text "{value}"
                      :tokens {:fill "color.text"
                               :fontSize "font-size.xl"
                               :fontFamily "font-family.heading"
                               :fontWeight "font-weight.bold"}}
                     {:type "recipe"
                      :name "Change"
                      :use "badge"
                      :with {:label {:param "change"}}
                      :variant {:Tone "Success"}}]}})

(def table-row
  {:recipe 1
   :name "table-row"
   :title "Table row"
   :purpose "One row of a table, or its header: a cell for each column."
   :category "container"
   :keywords ["row" "record" "table header" "cells"]
   :params [{:name "columns" :type "number" :default 4 :min 2 :max 6
             :description "How many cells"}]
   :variants [{:name "Kind" :values ["Body" "Header"]}]
   :tree {:type "board"
          :name "Table row"
          :width 960
          :layout {:type "flex" :dir "row" :alignItems "center"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.surface"
                   :gap "spacing.lg"
                   :paddingX "spacing.lg"
                   :paddingY "spacing.md"}
          :vary [{:on {:Kind "Header"} :set {:tokens {:fill "color.surface-muted"}}}]
          :children [{:type "text"
                      :name "Cell {index}"
                      :repeat {:param "columns"}
                      :text "Cell"
                      :layoutChild {:horizontalSizing "fill"}
                      :tokens {:fill "color.text-muted" :fontSize "font-size.sm"
                               :fontFamily "font-family.body"}
                      :vary [{:on {:Kind "Header"}
                              :set {:text "Column {index}"
                                    :tokens {:fill "color.text" :fontWeight "font-weight.medium"}}}]}]}})

(def all [card list-item tabs alert modal tab plan-card feature-item stat-card table-row])
