;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipes.screens
  "The built-in screens: a whole page or screen as the sections, navigation
  and containers it is made of. A screen holds almost nothing of its own: it
  places other recipes and says how they share the space. See
  `app.common.agent.recipe` for the format and `app.common.agent.recipes.kit`
  for how they are written."
  (:require
   [app.common.agent.recipes.kit :as k]))

(def ^:private wide ["fill" nil])

(defn- page
  "A page that grows down: sections one under the other, each as wide as it."
  [nm & children]
  (apply k/board nm {:w 1440 :size ["fix" "auto"] :tokens {:fill "color.background"}} children))

(defn- app-screen
  "A screen of an app: a sidebar and, next to it, the place for the rest."
  [nm & main]
  (k/board nm {:dir "row" :w 1440 :h 900 :size ["fix" "fix"] :tokens {:fill "color.surface-muted"}}
           (k/place "Sidebar" "sidebar" :size [nil "fill"])
           (apply k/board "Main" {:size ["fill" "fill"] :slot "main"
                                  :tokens {:padding "spacing.2xl" :gap "spacing.xl"}}
                  main)))

(defn- phone
  "A phone screen: a header, the place for the rest, and what sits at the
  bottom."
  [nm header content footer]
  (k/board nm {:w 393 :h 852 :size ["fix" "fix"] :tokens {:fill "color.background"}}
           header content footer))

(def landing-page
  (k/recipe
   {:name "landing-page" :title "Landing page" :category "screen"
    :purpose "The whole first page of a product's site: top bar, hero, who uses it, features, what people say, pricing, a closing call and the footer."
    :keywords ["home page" "marketing site" "website" "product page" "startup"]
    :params [(k/ptext "brand" "Brand" "The name of the product")
             (k/pbool "pricing" true "Include the pricing section")
             (k/pbool "testimonials" true "Include what people say")]}
   (page "Landing page"
         (k/place "Top bar" "top-bar" :with {:brand {:param "brand"}} :size wide)
         (k/place "Hero" "hero" :size wide)
         (k/place "Logos" "logo-cloud" :size wide)
         (k/place "Features" "feature-grid" :size wide)
         (k/place "Testimonials" "testimonials" :when {:param "testimonials"} :size wide)
         (k/place "Pricing" "pricing-table" :when {:param "pricing"} :size wide)
         (k/place "Call to action" "cta-section" :size wide)
         (k/place "Footer" "footer" :with {:brand {:param "brand"}} :size wide))))

(def pricing-page
  (k/recipe
   {:name "pricing-page" :title "Pricing page" :category "screen"
    :purpose "A page about what it costs: top bar, the plans, the questions people ask about them, and the footer."
    :keywords ["plans page" "subscription page" "billing"]
    :params [(k/ptext "brand" "Brand" "The name of the product")]}
   (page "Pricing page"
         (k/place "Top bar" "top-bar" :with {:brand {:param "brand"}} :size wide)
         (k/place "Pricing" "pricing-table" :size wide)
         (k/place "Questions" "faq" :size wide)
         (k/place "Call to action" "cta-section" :size wide)
         (k/place "Footer" "footer" :with {:brand {:param "brand"}} :size wide))))

(def article-page
  (k/recipe
   {:name "article-page" :title "Article page" :category "screen"
    :purpose "A page to read: top bar, the article's header, room for its text, more to read after, and the footer."
    :keywords ["blog post" "story page" "news article" "documentation page"]
    :params [(k/ptext "brand" "Brand" "The name of the site")]}
   (k/board "Article page" {:align "center" :w 1440 :size ["fix" "auto"] :tokens {:fill "color.background"}}
            (k/place "Top bar" "top-bar" :with {:brand {:param "brand"}} :size wide)
            (k/place "Header" "article-header")
            (k/board "Body" {:w 760 :size ["fix" "auto"] :slot "body"
                             :tokens {:paddingX "spacing.2xl" :paddingY "spacing.xl" :gap "spacing.lg"}}
                     (k/text "Paragraph" "The article's text goes here, a paragraph at a time." "md"
                             :fill "color.text-muted" :grow true))
            (k/place "More" "blog-list" :with {:heading "Keep reading"} :size wide)
            (k/place "Footer" "footer" :with {:brand {:param "brand"}} :size wide))))

(def not-found-page
  (k/recipe
   {:name "not-found-page" :title "Not found page" :category "screen"
    :purpose "The whole page for an address that leads nowhere: top bar, the message and the way home, the footer."
    :keywords ["404 page" "error page"]
    :params [(k/ptext "brand" "Brand" "The name of the site")]}
   (page "Not found page"
         (k/place "Top bar" "top-bar" :with {:brand {:param "brand"}} :size wide)
         (k/place "Message" "not-found" :size wide)
         (k/place "Footer" "footer" :with {:brand {:param "brand"}} :size wide))))

(def sign-in-screen
  (k/recipe
   {:name "sign-in-screen" :title "Sign-in screen" :category "screen"
    :purpose "The screen to sign in on: the brand over a panel with email, password and the ways around."
    :keywords ["login page" "log in screen" "authentication screen"]
    :params [(k/ptext "brand" "Brand" "The name of the product")]}
   (k/board "Sign-in screen" {:align "center" :justify "center" :w 1440 :h 900 :size ["fix" "fix"]
                              :tokens {:fill "color.surface-muted" :gap "spacing.xl"}}
            (k/text "Brand" "{brand}" "xl" :weight "bold" :align "center")
            (k/place "Panel" "auth-panel"))))

(def sign-up-screen
  (k/recipe
   {:name "sign-up-screen" :title "Sign-up screen" :category "screen"
    :purpose "The screen to open an account on: the brand over a form with its fields, the terms to accept and the button."
    :keywords ["register page" "create account" "join" "onboarding"]
    :params [(k/ptext "brand" "Brand" "The name of the product")
             (k/pnum "fields" 3 1 6 "How many fields")]}
   (k/board "Sign-up screen" {:align "center" :justify "center" :w 1440 :h 900 :size ["fix" "fix"]
                              :tokens {:fill "color.surface-muted" :gap "spacing.xl"}}
            (k/text "Brand" "{brand}" "xl" :weight "bold" :align "center")
            (k/place "Form" "form" :with {:fields {:param "fields"} :submitLabel "Create account"})
            (k/place "Sign in" "link" :with {:label "Already have an account? Sign in"} :variant {:Tone "Muted"}))))

(def dashboard-screen
  (k/recipe
   {:name "dashboard-screen" :title "Dashboard screen" :category "screen"
    :purpose "The first screen of an app: sidebar, a heading, the key numbers, a chart and a table of what matters."
    :keywords ["overview" "home screen" "admin" "analytics" "control panel"]
    :params [(k/ptext "title" "Dashboard" "The screen's title")
             (k/pbool "chart" false "Include a chart under the numbers")]}
   (app-screen "Dashboard screen"
               (k/place "Header" "page-header" :with {:title {:param "title"}} :size wide)
               (k/place "Numbers" "stat-cards" :size wide)
               (k/place "Chart" "chart-card" :when {:param "chart"} :size wide)
               (k/place "Table" "data-table" :size wide))))

(def list-screen
  (k/recipe
   {:name "list-screen" :title "List screen" :category "screen"
    :purpose "A screen for many records of one kind: sidebar, a heading with its action, what narrows the list, the table and its pages."
    :keywords ["table screen" "index" "records" "crud" "management"]
    :params [(k/ptext "title" "Projects" "What the records are")]}
   (app-screen "List screen"
               (k/place "Header" "page-header" :with {:title {:param "title"}} :size wide)
               (k/place "Filters" "filter-bar" :size wide)
               (k/place "Table" "data-table" :with {:rows 8} :size wide)
               (k/place "Pages" "pagination"))))

(def settings-screen
  (k/recipe
   {:name "settings-screen" :title "Settings screen" :category "screen"
    :purpose "The screen where an account is set up: sidebar, a heading, and groups of settings one under the other."
    :keywords ["preferences" "account settings" "configuration" "options"]
    :params [(k/pnum "groups" 2 1 4 "How many groups of settings")]}
   (app-screen "Settings screen"
               (k/place "Header" "page-header" :with {:title "Settings" :actionLabel "Save"} :size wide)
               (k/place "Group {index}" "settings-section" :repeat {:param "groups"} :with {:title "Group {index}"}))))

(def board-screen
  (k/recipe
   {:name "board-screen" :title "Board screen" :category "screen"
    :purpose "A screen for work in stages: sidebar, a heading with its action, and a kanban board."
    :keywords ["kanban screen" "task board" "project board" "workflow"]
    :params [(k/ptext "title" "Board" "The board's name")]}
   (app-screen "Board screen"
               (k/place "Header" "page-header" :with {:title {:param "title"} :actionLabel "New task"} :size wide)
               (k/place "Board" "kanban-board"))))

(def mobile-list-screen
  (k/recipe
   {:name "mobile-list-screen" :title "Mobile list screen" :category "screen"
    :purpose "A phone screen with a list: the header, rows of people or things, and the tab bar."
    :keywords ["inbox" "contacts" "phone list" "messages screen" "feed"]
    :params [(k/ptext "title" "Inbox" "The screen's title")
             (k/pnum "rows" 5 1 9 "How many rows")
             (k/pnum "tabs" 4 2 5 "How many tabs at the bottom")]}
   (phone "Mobile list screen"
          (k/place "Header" "mobile-header" :with {:title {:param "title"} :actionLabel "Edit"} :size wide)
          (k/board "List" {:size ["fill" "fill"] :slot "list"}
                   (k/place "Row {index}" "list-item" :repeat {:param "rows"} :size wide))
          (k/place "Tabs" "bottom-tab-bar" :with {:tabs {:param "tabs"}} :size wide))))

(def mobile-settings-screen
  (k/recipe
   {:name "mobile-settings-screen" :title "Mobile settings screen" :category "screen"
    :purpose "A phone screen of settings: the header, each setting as a line with its switch, and the tab bar."
    :keywords ["phone settings" "preferences screen" "app settings"]
    :params [(k/pnum "settings" 5 1 8 "How many settings")]}
   (phone "Mobile settings screen"
          (k/place "Header" "mobile-header" :with {:title "Settings" :actionLabel "Done"} :size wide)
          (k/board "Settings" {:size ["fill" "fill"] :slot "settings" :tokens {:paddingX "spacing.lg"}}
                   (k/place "Setting {index}" "settings-row" :repeat {:param "settings"}
                            :with {:title "Setting {index}"} :size wide))
          (k/place "Tabs" "bottom-tab-bar" :size wide))))

(def mobile-chat-screen
  (k/recipe
   {:name "mobile-chat-screen" :title "Mobile chat screen" :category "screen"
    :purpose "A phone screen of one conversation: who it is with, the messages, and the bar to write in."
    :keywords ["messaging screen" "direct message" "conversation screen"]
    :params [(k/ptext "title" "Ada Lovelace" "Who the conversation is with")]}
   (k/board "Mobile chat screen" {:w 393 :h 852 :size ["fix" "fix"] :tokens {:fill "color.background"}}
            (k/place "Thread" "chat-thread" :with {:title {:param "title"}} :size ["fill" "fill"]))))

(def all
  [landing-page pricing-page article-page not-found-page sign-in-screen sign-up-screen dashboard-screen
   list-screen settings-screen board-screen mobile-list-screen mobile-settings-screen mobile-chat-screen])
