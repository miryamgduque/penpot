;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipes.more-navigation
  "More built-in navigation and the parts it repeats; see
  `app.common.agent.recipe` for the format and
  `app.common.agent.recipes.kit` for how they are written."
  (:require
   [app.common.agent.recipes.kit :as k]))

(def breadcrumb-item
  (k/recipe
   {:name "breadcrumb-item" :title "Breadcrumb item" :category "navigation"
    :purpose "One step of a breadcrumb trail, marked when it is the page you are on."
    :keywords ["crumb" "path step"]
    :params [(k/ptext "label" "Page" "The name of the place")]
    :variants [(k/axis "State" "Default" "Current")]}
   (k/board "Breadcrumb item" {:dir "row" :align "center" :size ["auto" "auto"] :tokens {:gap "spacing.sm"}}
            (k/text "Label" "{label}" "sm" :fill "color.text-muted"
                    :vary [(k/on {:State "Current"} {:tokens {:fill "color.text" :fontWeight "font-weight.medium"}})])
            (k/text "Separator" "/" "sm" :fill "color.text-muted" :when {:variant "State" :is "Default"}))))

(def breadcrumb
  (k/recipe
   {:name "breadcrumb" :title "Breadcrumb" :category "navigation"
    :purpose "The trail from the top of a site down to the page you are on."
    :keywords ["path" "trail" "hierarchy" "where am i"]
    :params [(k/pnum "levels" 3 2 6 "How many steps; the last is the current page")]}
   (k/board "Breadcrumb" {:dir "row" :align "center" :size ["auto" "auto"] :tokens {:gap "spacing.sm"}}
            (k/place "Level {index}" "breadcrumb-item" :repeat {:param "levels"} :with {:label "Level {index}"})
            (k/place "Current" "breadcrumb-item" :with {:label "This page"} :variant {:State "Current"}))))

(def page-button
  (k/recipe
   {:name "page-button" :title "Page button" :category "navigation"
    :purpose "One page number of a pagination, marked when it is the page shown."
    :keywords ["page number"]
    :params [(k/ptext "label" "1" "The number")]
    :variants [(k/axis "State" "Default" "Current")]}
   (k/board "Page button" {:dir "row" :align "center" :justify "center" :w 32 :h 32 :size ["fix" "fix"]
                           :tokens {:fill "color.surface" :borderRadius "radius.md"}
                           :vary [(k/on {:State "Current"} {:tokens {:fill "color.primary"}})]}
            (k/text "Label" "{label}" "sm" :weight "medium"
                    :vary [(k/on {:State "Current"} {:tokens {:fill "color.on-primary"}})]))))

(def pagination
  (k/recipe
   {:name "pagination" :title "Pagination" :category "navigation"
    :purpose "The way through a long list, a page at a time: back, the page numbers, forward."
    :keywords ["pages" "pager" "next previous" "paging"]
    :params [(k/pnum "pages" 5 2 7 "How many page numbers; the first is the page shown")]}
   (k/board "Pagination" {:dir "row" :align "center" :size ["auto" "auto"] :tokens {:gap "spacing.xs"}}
            (k/place "Previous" "icon-button")
            (k/place "Page {index}" "page-button" :repeat {:param "pages"} :with {:label "{index}"}
                     :vary [(k/at 1 {:variant {:State "Current"}})])
            (k/place "Next" "icon-button"))))

(def step
  (k/recipe
   {:name "step" :title "Step" :category "navigation"
    :purpose "One step of a process with several: its number, its name and how far it is."
    :keywords ["wizard step" "progress step" "stage"]
    :params [(k/ptext "number" "1" "Its place in the order")
             (k/ptext "label" "Step" "Its name")]
    :variants [(k/axis "State" "Next" "Current" "Done")]}
   (k/board "Step" {:dir "row" :align "center" :size ["auto" "auto"] :tokens {:gap "spacing.sm"}}
            (k/board "Mark" {:dir "row" :align "center" :justify "center" :w 28 :h 28 :size ["fix" "fix"]
                             :tokens {:fill "color.surface-muted" :borderRadius "radius.full"}
                             :vary [(k/on {:State "Current"} {:tokens {:fill "color.primary"}})
                                    (k/on {:State "Done"} {:tokens {:fill "color.success"}})]}
                     (k/text "Number" "{number}" "xs" :fill "color.text-muted" :weight "bold"
                             :vary [(k/on {:State "Current"} {:tokens {:fill "color.on-primary"}})
                                    (k/on {:State "Done"} {:tokens {:fill "color.on-primary"}})]))
            (k/text "Label" "{label}" "sm" :fill "color.text-muted" :weight "medium"
                    :vary [(k/on {:State "Current"} {:tokens {:fill "color.text"}})]))))

(def stepper
  (k/recipe
   {:name "stepper" :title "Stepper" :category "navigation"
    :purpose "The steps of a process in a row, showing which is done, which is now and which come next."
    :keywords ["wizard" "progress steps" "checkout steps" "onboarding steps"]
    :params [(k/pnum "steps" 4 2 6 "How many steps; the first is done, the second is the current one")]}
   (k/board "Stepper" {:dir "row" :align "center" :size ["auto" "auto"] :tokens {:gap "spacing.xl"}}
            (k/place "Step {index}" "step" :repeat {:param "steps"} :with {:number "{index}" :label "Step {index}"}
                     :vary [(k/at 1 {:variant {:State "Done"}})
                            (k/at 2 {:variant {:State "Current"}})]))))

(def mobile-header
  (k/recipe
   {:name "mobile-header" :title "Mobile header" :category "navigation"
    :purpose "The bar at the top of a phone screen: a way back, the screen's title and one action."
    :keywords ["navigation bar" "app bar" "phone header" "back"]
    :params [(k/ptext "title" "Title" "The screen's title")
             (k/ptext "actionLabel" "Done" "The words of its action")]}
   (k/board "Mobile header" {:dir "row" :align "center" :justify "space-between" :w 393 :size ["fix" "auto"]
                             :tokens {:fill "color.surface" :paddingX "spacing.md" :paddingY "spacing.sm" :gap "spacing.md"}}
            (k/place "Back" "icon-button")
            (k/text "Title" "{title}" "md" :weight "bold")
            (k/place "Action" "link" :with {:label {:param "actionLabel"}}))))

(def toolbar
  (k/recipe
   {:name "toolbar" :title "Toolbar" :category "navigation"
    :purpose "A row of tools as icon buttons, one of them in use: what an editor or a viewer keeps at hand."
    :keywords ["tool rail" "action bar" "editor tools" "icon bar"]
    :params [(k/pnum "tools" 6 2 10 "How many tools; the first is the one in use")]}
   (k/board "Toolbar" {:dir "row" :align "center" :size ["auto" "auto"]
                       :tokens (assoc k/surface :borderRadius "radius.md" :padding "spacing.xs" :gap "spacing.xs")}
            (k/place "Tool {index}" "icon-button" :repeat {:param "tools"}
                     :vary [(k/at 1 {:variant {:State "Active"}})]))))

(def user-menu
  (k/recipe
   {:name "user-menu" :title "User menu" :category "navigation"
    :purpose "Who is signed in, as an avatar with their name and a mark that it opens."
    :keywords ["account menu" "profile button" "signed in"]
    :params [(k/ptext "name" "Ada Lovelace" "Who is signed in")
             (k/ptext "initials" "AL" "The letters in the avatar")]
    :component true}
   (k/board "User menu" {:dir "row" :align "center" :size ["auto" "auto"]
                         :tokens {:borderRadius "radius.md" :gap "spacing.sm" :padding "spacing.xs"}}
            (k/place "Avatar" "avatar" :with {:initials {:param "initials"}} :variant {:Size "Small"})
            (k/text "Name" "{name}" "sm" :weight "medium")
            (k/icon "Chevron" "Small" "Muted"))))

(def filter-bar
  (k/recipe
   {:name "filter-bar" :title "Filter bar" :category "navigation"
    :purpose "What narrows a list: a box to search in, chips to filter by and a button to add."
    :keywords ["filters" "search and filter" "list controls" "facets"]
    :params [(k/pnum "filters" 3 1 6 "How many filter chips; the first is on")
             (k/ptext "actionLabel" "New" "The words on its button")]}
   (k/board "Filter bar" {:dir "row" :align "center" :w 960 :size ["fix" "auto"] :tokens {:gap "spacing.md"}}
            (k/place "Search" "search-field")
            (k/board "Filters" {:dir "row" :align "center" :size ["fill" "auto"] :tokens {:gap "spacing.sm"}}
                     (k/place "Filter {index}" "chip" :repeat {:param "filters"} :with {:label "Filter {index}"}
                              :vary [(k/at 1 {:variant {:State "Selected"}})]))
            (k/place "Action" "button" :with {:label {:param "actionLabel"}}))))

(def command-palette
  (k/recipe
   {:name "command-palette" :title "Command palette" :category "navigation"
    :purpose "A box to type in over the app, with the commands that match under it."
    :keywords ["quick actions" "spotlight" "search everywhere" "launcher"]
    :params [(k/pnum "results" 5 2 8 "How many commands; the first is the one picked")]}
   (k/board "Command palette" {:w 560 :size ["fix" "auto"] :tokens (assoc k/surface :padding "spacing.sm" :gap "spacing.sm")}
            (k/place "Search" "search-field" :with {:placeholder "Type a command"} :size ["fill" nil])
            (k/place "Result {index}" "menu-item" :repeat {:param "results"} :with {:label "Command {index}"}
                     :size ["fill" nil] :vary [(k/at 1 {:variant {:State "Current"}})]))))

(def all
  [breadcrumb-item breadcrumb page-button pagination step stepper mobile-header toolbar user-menu
   filter-bar command-palette])
