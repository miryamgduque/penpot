;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipes.sections
  "The built-in recipes for sections of a page; see
  `app.common.agent.recipe` for the format. A section is a plain recipe: it
  is built as its tree, and places the controls and containers it uses as
  instances.")

(def hero
  {:recipe 1
   :name "hero"
   :title "Hero"
   :purpose "The opening of a page: what this is, why it matters, and the one thing to do next."
   :category "section"
   :keywords ["landing" "header" "banner" "intro" "headline" "above the fold"]
   :params [{:name "heading" :type "text" :default "Say what it is in one line"
             :description "The headline"}
            {:name "subheading" :type "text" :default "Then say why it matters, in a sentence or two."
             :description "The line under it"}
            {:name "actionLabel" :type "text" :default "Get started"
             :description "The words on the main button"}
            {:name "secondAction" :type "boolean" :default true
             :description "Add a second, quieter button"}
            {:name "secondActionLabel" :type "text" :default "Learn more"
             :description "The words on that button"}
            {:name "media" :type "boolean" :default true
             :description "Leave room for a picture under the text"}]
   :tree {:type "board"
          :name "Hero"
          :width 1200
          :layout {:type "flex" :dir "column" :alignItems "center"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.background" :padding "spacing.3xl" :gap "spacing.xl"}
          :children [{:type "text"
                      :name "Heading"
                      :text "{heading}"
                      :align "center"
                      :tokens {:fill "color.text"
                               :fontSize "font-size.2xl"
                               :fontFamily "font-family.heading"
                               :fontWeight "font-weight.bold"}}
                     {:type "text"
                      :name "Subheading"
                      :text "{subheading}"
                      :align "center"
                      :tokens {:fill "color.text-muted" :fontSize "font-size.lg"
                               :fontFamily "font-family.body"}}
                     {:type "board"
                      :name "Actions"
                      :layout {:type "flex" :dir "row" :alignItems "center"}
                      :layoutChild {:horizontalSizing "auto" :verticalSizing "auto"}
                      :tokens {:gap "spacing.md"}
                      :children [{:type "recipe"
                                  :name "Action"
                                  :use "button"
                                  :with {:label {:param "actionLabel"}}
                                  :variant {:Kind "Primary"}}
                                 {:type "recipe"
                                  :name "Second action"
                                  :when {:param "secondAction"}
                                  :use "button"
                                  :with {:label {:param "secondActionLabel"}}
                                  :variant {:Kind "Secondary"}}]}
                     {:type "rect"
                      :name "Media"
                      :placeholder "image"
                      :when {:param "media"}
                      :height 420
                      :layoutChild {:horizontalSizing "fill"}
                      :tokens {:fill "color.surface-muted" :borderRadius "radius.lg"}}]}})

(def feature-grid
  {:recipe 1
   :name "feature-grid"
   :title "Feature grid"
   :purpose "A heading and a set of features side by side, each with an icon, a name and a line about it."
   :category "section"
   :keywords ["features" "benefits" "services" "columns" "highlights"]
   :params [{:name "heading" :type "text" :default "Everything you need"
             :description "The section's heading"}
            {:name "features" :type "number" :default 3 :min 2 :max 6
             :description "How many features; more than three wrap to a second row"}]
   :tree {:type "board"
          :name "Features"
          :width 1200
          :layout {:type "flex" :dir "column" :alignItems "center"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.background" :padding "spacing.3xl" :gap "spacing.2xl"}
          :children [{:type "text"
                      :name "Heading"
                      :text "{heading}"
                      :align "center"
                      :tokens {:fill "color.text"
                               :fontSize "font-size.xl"
                               :fontFamily "font-family.heading"
                               :fontWeight "font-weight.bold"}}
                     {:type "board"
                      :name "Grid"
                      :layout {:type "flex" :dir "row" :alignItems "start" :wrap true}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.xl"}
                      :children [{:type "recipe"
                                  :name "Feature {index}"
                                  :repeat {:param "features"}
                                  :use "feature-item"
                                  :with {:title "Feature {index}"}}]}]}})

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
                               :fontFamily "font-family.heading"
                               :fontWeight "font-weight.bold"}}
                     {:type "board"
                      :name "Plans"
                      :layout {:type "flex" :dir "row" :alignItems "stretch"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.xl"}
                      :children [{:type "recipe"
                                  :name "Plan {index}"
                                  :repeat {:param "plans"}
                                  :use "plan-card"
                                  :with {:name "Plan {index}"
                                         :cta "Choose plan {index}"
                                         :features {:param "features"}}
                                  :vary [{:at 2 :set {:variant {:Emphasis "Featured"}}}]
                                  :layoutChild {:horizontalSizing "fill"}}]}]}})

(def form
  {:recipe 1
   :name "form"
   :title "Form"
   :purpose "A titled set of fields with one button that sends them: sign up, contact, settings."
   :category "section"
   :keywords ["sign up" "login" "contact" "inputs" "fields" "submit" "register"]
   :params [{:name "title" :type "text" :default "Create your account"
             :description "What the form is for"}
            {:name "fields" :type "number" :default 3 :min 1 :max 8
             :description "How many fields"}
            {:name "submitLabel" :type "text" :default "Continue"
             :description "The words on its button"}]
   :tree {:type "board"
          :name "Form"
          :width 420
          :layout {:type "flex" :dir "column"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.surface"
                   :strokeColor "color.border"
                   :strokeWidth "border-width.sm"
                   :borderRadius "radius.lg"
                   :padding "spacing.xl"
                   :gap "spacing.xl"}
          :children [{:type "text"
                      :name "Title"
                      :text "{title}"
                      :layoutChild {:horizontalSizing "fill"}
                      :tokens {:fill "color.text"
                               :fontSize "font-size.lg"
                               :fontFamily "font-family.body"
                               :fontWeight "font-weight.bold"}}
                     {:type "board"
                      :name "Fields"
                      :slot "fields"
                      :layout {:type "flex" :dir "column"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.lg"}
                      :children [{:type "recipe"
                                  :name "Field {index}"
                                  :repeat {:param "fields"}
                                  :use "text-field"
                                  :with {:label "Field {index}"}
                                  :layoutChild {:horizontalSizing "fill"}}]}
                     {:type "recipe"
                      :name "Submit"
                      :use "button"
                      :with {:label {:param "submitLabel"}}
                      :variant {:Kind "Primary"}
                      :layoutChild {:horizontalSizing "fill"}}]}})

(def data-table
  {:recipe 1
   :name "data-table"
   :title "Data table"
   :purpose "Rows of records under named columns, to read across and compare."
   :category "section"
   :keywords ["table" "grid" "list" "records" "rows" "columns" "spreadsheet"]
   :params [{:name "columns" :type "number" :default 4 :min 2 :max 6
             :description "How many columns"}
            {:name "rows" :type "number" :default 5 :min 1 :max 10
             :description "How many rows under the header"}]
   :tree {:type "board"
          :name "Table"
          :width 960
          :layout {:type "flex" :dir "column"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.surface"
                   :strokeColor "color.border"
                   :strokeWidth "border-width.sm"
                   :borderRadius "radius.lg"}
          :children [{:type "recipe"
                      :name "Header"
                      :use "table-row"
                      :with {:columns {:param "columns"}}
                      :variant {:Kind "Header"}
                      :layoutChild {:horizontalSizing "fill"}}
                     {:type "recipe"
                      :name "Row {index}"
                      :repeat {:param "rows"}
                      :use "table-row"
                      :with {:columns {:param "columns"}}
                      :layoutChild {:horizontalSizing "fill"}}]}})

(def stat-cards
  {:recipe 1
   :name "stat-cards"
   :title "Stat cards"
   :purpose "A row of key numbers, each with what it measures and how it moved."
   :category "section"
   :keywords ["metrics" "kpi" "dashboard" "numbers" "summary" "analytics"]
   :params [{:name "stats" :type "number" :default 4 :min 2 :max 6
             :description "How many numbers"}]
   :tree {:type "board"
          :name "Stats"
          :width 1200
          :layout {:type "flex" :dir "row" :alignItems "stretch"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:gap "spacing.xl"}
          :children [{:type "recipe"
                      :name "Stat {index}"
                      :repeat {:param "stats"}
                      :use "stat-card"
                      :with {:label "Metric {index}"}
                      :layoutChild {:horizontalSizing "fill"}}]}})

(def footer
  {:recipe 1
   :name "footer"
   :title "Footer"
   :purpose "The end of a page: the brand, groups of links, and the legal line."
   :category "section"
   :keywords ["site map" "links" "legal" "copyright" "bottom"]
   :params [{:name "brand" :type "text" :default "Brand"
             :description "The name of the product or site"}
            {:name "columns" :type "number" :default 3 :min 1 :max 5
             :description "How many groups of links"}
            {:name "links" :type "number" :default 4 :min 1 :max 6
             :description "How many links in each group"}]
   :tree {:type "board"
          :name "Footer"
          :width 1200
          :layout {:type "flex" :dir "column"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:fill "color.surface-muted" :padding "spacing.3xl" :gap "spacing.2xl"}
          :children [{:type "board"
                      :name "Top"
                      :layout {:type "flex" :dir "row" :alignItems "start"}
                      :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                      :tokens {:gap "spacing.3xl"}
                      :children [{:type "board"
                                  :name "About"
                                  :layout {:type "flex" :dir "column"}
                                  :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                                  :tokens {:gap "spacing.sm"}
                                  :children [{:type "text"
                                              :name "Brand"
                                              :text "{brand}"
                                              :tokens {:fill "color.text"
                                                       :fontSize "font-size.lg"
                                                       :fontFamily "font-family.body"
                                                       :fontWeight "font-weight.bold"}}
                                             {:type "text"
                                              :name "Tagline"
                                              :text "What it is, in a line."
                                              :tokens {:fill "color.text-muted"
                                                       :fontSize "font-size.sm"
                                                       :fontFamily "font-family.body"}}]}
                                 {:type "recipe"
                                  :name "Group {index}"
                                  :repeat {:param "columns"}
                                  :use "footer-group"
                                  :with {:title "Group {index}"
                                         :links {:param "links"}}}]}
                     {:type "text"
                      :name "Legal"
                      :text "© {brand}. All rights reserved."
                      :tokens {:fill "color.text-muted" :fontSize "font-size.xs"
                               :fontFamily "font-family.body"}}]}})

(def empty-state
  {:recipe 1
   :name "empty-state"
   :title "Empty state"
   :purpose "What a place shows when it has nothing yet: why it is empty and how to start."
   :category "section"
   :keywords ["no results" "nothing here" "placeholder" "zero state" "first use"]
   :params [{:name "title" :type "text" :default "Nothing here yet"
             :description "What is missing"}
            {:name "message" :type "text" :default "When you add something, it will show up here."
             :description "Why, and what to do"}
            {:name "actionLabel" :type "text" :default "Add the first one"
             :description "The words on its button"}]
   :tree {:type "board"
          :name "Empty state"
          :width 480
          :layout {:type "flex" :dir "column" :alignItems "center"}
          :layoutChild {:verticalSizing "auto"}
          :tokens {:padding "spacing.3xl" :gap "spacing.lg"}
          :children [{:type "ellipse"
                      :name "Illustration"
                      :placeholder "image"
                      :width 96
                      :height 96
                      :tokens {:fill "color.surface-muted"}}
                     {:type "text"
                      :name "Title"
                      :text "{title}"
                      :align "center"
                      :tokens {:fill "color.text"
                               :fontSize "font-size.lg"
                               :fontFamily "font-family.body"
                               :fontWeight "font-weight.bold"}}
                     {:type "text"
                      :name "Message"
                      :text "{message}"
                      :align "center"
                      :tokens {:fill "color.text-muted" :fontSize "font-size.sm"
                               :fontFamily "font-family.body"}}
                     {:type "recipe"
                      :name "Action"
                      :use "button"
                      :with {:label {:param "actionLabel"}}
                      :variant {:Kind "Primary"}}]}})

(def footer-group
  {:recipe 1
   :name "footer-group"
   :title "Footer group"
   :purpose "One group of links in a footer: its name and the links under it."
   :category "section"
   :keywords ["footer column" "link list"]
   :params [{:name "title" :type "text" :default "Group"
             :description "The group's name"}
            {:name "links" :type "number" :default 4 :min 1 :max 6
             :description "How many links"}]
   :component true
   :tree {:type "board"
          :name "Footer group"
          :layout {:type "flex" :dir "column"}
          :layoutChild {:horizontalSizing "auto" :verticalSizing "auto"}
          :tokens {:gap "spacing.sm"}
          :children [{:type "text"
                      :name "Group name"
                      :text "{title}"
                      :tokens {:fill "color.text"
                               :fontSize "font-size.sm"
                               :fontFamily "font-family.body"
                               :fontWeight "font-weight.medium"}}
                     {:type "text"
                      :name "Link {index}"
                      :repeat {:param "links"}
                      :text "Link {index}"
                      :tokens {:fill "color.text-muted" :fontSize "font-size.sm"
                               :fontFamily "font-family.body"}}]}})

(def all [hero feature-grid pricing-table form data-table stat-cards footer empty-state footer-group])
