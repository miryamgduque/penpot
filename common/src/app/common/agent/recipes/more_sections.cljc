;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipes.more-sections
  "More built-in sections of a page; see `app.common.agent.recipe` for the
  format and `app.common.agent.recipes.kit` for how they are written."
  (:require
   [app.common.agent.recipes.kit :as k]))

(defn- section
  "A full-width section: a centred heading over what it holds."
  [nm opts & children]
  (apply k/board nm (merge {:align "center" :w 1200 :size ["fix" "auto"]
                            :tokens {:fill "color.background" :padding "spacing.3xl" :gap "spacing.2xl"}}
                           opts)
         children))

(def ^:private heading
  (k/text "Heading" "{heading}" "xl" :weight "bold" :align "center"))

(def cta-section
  (k/recipe
   {:name "cta-section" :title "Call to action" :category "section"
    :purpose "A closing band that asks for one thing: a headline, a line and a button."
    :keywords ["cta" "sign up band" "get started" "conversion"]
    :params [(k/ptext "heading" "Ready to get started?" "The headline")
             (k/ptext "body" "It takes a minute, and you can stop any time." "The line under it")
             (k/ptext "actionLabel" "Get started" "The words on the button")]}
   (section "Call to action" {:tokens {:fill "color.surface-muted" :padding "spacing.3xl" :gap "spacing.lg"}}
            heading
            (k/text "Body" "{body}" "md" :fill "color.text-muted" :align "center")
            (k/place "Action" "button" :with {:label {:param "actionLabel"}}))))

(def faq
  (k/recipe
   {:name "faq" :title "FAQ" :category "section"
    :purpose "The questions people ask, each opening to its answer."
    :keywords ["questions" "help" "support" "answers"]
    :params [(k/ptext "heading" "Questions, answered" "The section's heading")
             (k/pnum "questions" 5 2 8 "How many questions")]}
   (section "FAQ" {}
            heading
            (k/place "Questions" "accordion" :with {:items {:param "questions"}}))))

(def testimonials
  (k/recipe
   {:name "testimonials" :title "Testimonials" :category "section"
    :purpose "What customers say, side by side."
    :keywords ["reviews" "social proof" "customer quotes" "love"]
    :params [(k/ptext "heading" "What people say" "The section's heading")
             (k/pnum "quotes" 3 2 6 "How many quotes")]}
   (section "Testimonials" {}
            heading
            (k/board "Quotes" {:dir "row" :align "stretch" :wrap true :size ["fill" "auto"] :tokens {:gap "spacing.xl"}}
                     (k/place "Quote {index}" "testimonial-card" :repeat {:param "quotes"})))))

(def logo-cloud
  (k/recipe
   {:name "logo-cloud" :title "Logo cloud" :category "section"
    :purpose "A row of the marks of who already uses this, under a quiet line."
    :keywords ["trusted by" "customers" "partners" "brands"]
    :params [(k/ptext "heading" "Trusted by teams everywhere" "The line over the marks")
             (k/pnum "logos" 5 3 8 "How many marks")]}
   (section "Logo cloud" {:tokens {:fill "color.background" :padding "spacing.2xl" :gap "spacing.xl"}}
            (k/text "Heading" "{heading}" "sm" :fill "color.text-muted" :weight "medium" :align "center")
            (k/board "Logos" {:dir "row" :align "center" :justify "center" :wrap true :size ["fill" "auto"] :tokens {:gap "spacing.2xl"}}
                     (k/rect "Logo {index}" 120 32 "color.surface-muted" :radius "radius.sm" :placeholder "image"
                             :repeat {:param "logos"})))))

(def team-section
  (k/recipe
   {:name "team-section" :title "Team" :category "section"
    :purpose "The people behind something, each with their name and what they do."
    :keywords ["about us" "people" "members" "staff"]
    :params [(k/ptext "heading" "Meet the team" "The section's heading")
             (k/pnum "people" 4 2 8 "How many people")]}
   (section "Team" {}
            heading
            (k/board "People" {:dir "row" :align "stretch" :justify "center" :wrap true :size ["fill" "auto"] :tokens {:gap "spacing.xl"}}
                     (k/place "Person {index}" "profile-card" :repeat {:param "people"})))))

(def newsletter
  (k/recipe
   {:name "newsletter" :title "Newsletter" :category "section"
    :purpose "A line that asks for an email address, with the box to type it and the button to send it."
    :keywords ["subscribe" "email signup" "mailing list" "updates"]
    :params [(k/ptext "heading" "Stay in the loop" "The headline")
             (k/ptext "body" "One email a month. No noise." "The line under it")
             (k/ptext "actionLabel" "Subscribe" "The words on the button")]}
   (section "Newsletter" {:tokens {:fill "color.surface-muted" :padding "spacing.3xl" :gap "spacing.lg"}}
            heading
            (k/text "Body" "{body}" "md" :fill "color.text-muted" :align "center")
            (k/board "Form" {:dir "row" :align "end" :size ["auto" "auto"] :tokens {:gap "spacing.sm"}}
                     (k/place "Email" "text-field" :with {:label "Email" :placeholder "you@example.com" :helper " "})
                     (k/place "Action" "button" :with {:label {:param "actionLabel"}})))))

(def contact-section
  (k/recipe
   {:name "contact-section" :title "Contact" :category "section"
    :purpose "How to get in touch: a few words on one side, a short form on the other."
    :keywords ["get in touch" "contact form" "support" "sales"]
    :params [(k/ptext "heading" "Talk to us" "The headline")
             (k/ptext "body" "Tell us what you need and we will get back within a day." "The words beside the form")]}
   (k/board "Contact" {:dir "row" :align "start" :w 1200 :size ["fix" "auto"]
                       :tokens {:fill "color.background" :padding "spacing.3xl" :gap "spacing.3xl"}}
            (k/board "About" {:size ["fill" "auto"] :tokens {:gap "spacing.md"}}
                     (k/text "Heading" "{heading}" "xl" :weight "bold" :grow true)
                     (k/text "Body" "{body}" "md" :fill "color.text-muted" :grow true))
            (k/place "Form" "form" :with {:title "Send a message" :submitLabel "Send"}))))

(def blog-list
  (k/recipe
   {:name "blog-list" :title "Blog list" :category "section"
    :purpose "The latest articles as cards, each with its picture, heading and a line."
    :keywords ["articles" "posts" "news" "resources"]
    :params [(k/ptext "heading" "From the blog" "The section's heading")
             (k/pnum "posts" 3 2 6 "How many articles")]}
   (section "Blog list" {}
            heading
            (k/board "Posts" {:dir "row" :align "start" :wrap true :size ["fill" "auto"] :tokens {:gap "spacing.xl"}}
                     (k/place "Post {index}" "media-card" :repeat {:param "posts"})))))

(def article-header
  (k/recipe
   {:name "article-header" :title "Article header" :category "section"
    :purpose "The top of an article: its kind, its headline, who wrote it and when, and its picture."
    :keywords ["post header" "blog post" "story" "byline"]
    :params [(k/ptext "tag" "Guide" "What kind of article it is")
             (k/ptext "heading" "A headline worth reading on for" "The headline")
             (k/ptext "author" "Ada Lovelace" "Who wrote it")
             (k/ptext "meta" "12 October · 5 min read" "When, and how long")]}
   (k/board "Article header" {:w 760 :size ["fix" "auto"] :tokens {:fill "color.background" :padding "spacing.2xl" :gap "spacing.lg"}}
            (k/text "Tag" "{tag}" "sm" :fill "color.primary" :weight "medium")
            (k/text "Heading" "{heading}" "2xl" :weight "bold" :grow true)
            (k/board "Byline" {:dir "row" :align "center" :size ["fill" "auto"] :tokens {:gap "spacing.md"}}
                     (k/place "Avatar" "avatar" :variant {:Size "Small"})
                     (k/text "Author" "{author}" "sm" :weight "medium")
                     (k/text "Meta" "{meta}" "sm" :fill "color.text-muted"))
            (k/media "Picture" 380))))

(def gallery
  (k/recipe
   {:name "gallery" :title "Gallery" :category "section"
    :purpose "A grid of pictures of the same size."
    :keywords ["photos" "image grid" "portfolio" "showcase"]
    :params [(k/ptext "heading" "Gallery" "The section's heading")
             (k/pnum "pictures" 6 2 12 "How many pictures")]}
   (section "Gallery" {}
            heading
            (k/board "Pictures" {:dir "row" :wrap true :size ["fill" "auto"] :tokens {:gap "spacing.lg"}}
                     (k/rect "Picture {index}" 352 240 "color.surface-muted" :radius "radius.md" :placeholder "image"
                             :repeat {:param "pictures"})))))

(def page-header
  (k/recipe
   {:name "page-header" :title "Page header" :category "section"
    :purpose "The top of a page inside an app: where you are, its title, a line about it and its main action."
    :keywords ["title bar" "screen header" "heading with actions"]
    :params [(k/ptext "title" "Page title" "The page's title")
             (k/ptext "description" "A line that says what this page is for." "The line under it")
             (k/ptext "actionLabel" "New" "The words on its button")]}
   (k/board "Page header" {:dir "row" :align "end" :w 960 :size ["fix" "auto"] :tokens {:gap "spacing.lg"}}
            (k/board "Text" {:size ["fill" "auto"] :tokens {:gap "spacing.sm"}}
                     (k/place "Trail" "breadcrumb" :with {:levels 2})
                     (k/text "Title" "{title}" "xl" :weight "bold" :grow true)
                     (k/text "Description" "{description}" "sm" :fill "color.text-muted" :grow true))
            (k/board "Actions" {:dir "row" :align "center" :size ["auto" "auto"] :tokens {:gap "spacing.sm"} :slot "actions"}
                     (k/place "Action" "button" :with {:label {:param "actionLabel"}})))))

(def settings-section
  (k/recipe
   {:name "settings-section" :title "Settings section" :category "section"
    :purpose "A titled group of settings, each a line with its switch."
    :keywords ["preferences" "options" "account settings" "toggles"]
    :params [(k/ptext "title" "Notifications" "The group's title")
             (k/ptext "description" "Choose what you hear about." "The line under it")
             (k/pnum "settings" 4 1 8 "How many settings; the first is on")]}
   (k/board "Settings section" {:w 640 :size ["fix" "auto"] :tokens (assoc k/surface :padding "spacing.xl" :gap "spacing.md")}
            (k/text "Title" "{title}" "lg" :weight "bold" :grow true)
            (k/text "Description" "{description}" "sm" :fill "color.text-muted" :grow true)
            (k/board "Rows" {:size ["fill" "auto"] :slot "rows"}
                     (k/place "Setting {index}" "settings-row" :repeat {:param "settings"} :with {:title "Setting {index}"}
                              :size ["fill" nil] :vary [(k/at 1 {:variant {:State "On"}})])))))

(def activity-feed
  (k/recipe
   {:name "activity-feed" :title "Activity feed" :category "section"
    :purpose "What has happened lately, newest first, as a line of marks down the side."
    :keywords ["timeline" "history" "recent activity" "log"]
    :params [(k/ptext "title" "Activity" "The feed's title")
             (k/pnum "events" 5 2 10 "How many events; the first is the current one")]}
   (k/board "Activity feed" {:w 420 :size ["fix" "auto"] :tokens (assoc k/surface :padding "spacing.xl" :gap "spacing.lg")}
            (k/text "Title" "{title}" "lg" :weight "bold" :grow true)
            (k/board "Events" {:size ["fill" "auto"] :tokens {:gap "spacing.md"}}
                     (k/place "Event {index}" "timeline-item" :repeat {:param "events"} :with {:title "Event {index}"}
                              :size ["fill" nil] :vary [(k/at 1 {:variant {:State "Current"}})])))))

(def notification-list
  (k/recipe
   {:name "notification-list" :title "Notification list" :category "section"
    :purpose "The things that need a look, unread ones first, with a way to clear them."
    :keywords ["notifications panel" "inbox" "updates" "alerts"]
    :params [(k/ptext "title" "Notifications" "The panel's title")
             (k/pnum "items" 4 1 8 "How many notifications")]}
   (k/board "Notification list" {:w 392 :size ["fix" "auto"] :tokens (assoc k/surface :padding "spacing.md" :gap "spacing.sm")}
            (k/board "Heading" {:dir "row" :align "center" :justify "space-between" :size ["fill" "auto"] :tokens {:padding "spacing.sm"}}
                     (k/text "Title" "{title}" "md" :weight "bold")
                     (k/place "Clear" "link" :with {:label "Mark all read"}))
            (k/place "Item {index}" "notification-item" :repeat {:param "items"} :with {:title "Notification {index}"} :size ["fill" nil]))))

(def kanban-board
  (k/recipe
   {:name "kanban-board" :title "Kanban board" :category "section"
    :purpose "Work as cards moving through stages, a column for each stage."
    :keywords ["task board" "project board" "trello" "workflow"]
    :params [(k/pnum "columns" 3 2 5 "How many stages")
             (k/pnum "cards" 3 0 6 "How many cards in each")]}
   (k/board "Kanban board" {:dir "row" :align "start" :size ["auto" "auto"] :tokens {:gap "spacing.lg"}}
            (k/place "Column {index}" "kanban-column" :repeat {:param "columns"}
                     :with {:title "Stage {index}" :cards {:param "cards"}}))))

(def chat-thread
  (k/recipe
   {:name "chat-thread" :title "Chat thread" :category "section"
    :purpose "A conversation on a phone: who it is with, the messages on each side, and the bar to write in."
    :keywords ["messages" "conversation" "direct message" "chat screen"]
    :params [(k/ptext "title" "Ada Lovelace" "Who the conversation is with")
             (k/pnum "messages" 2 1 4 "How many messages from each side")]}
   (k/board "Chat thread" {:w 393 :h 852 :size ["fix" "fix"] :tokens {:fill "color.background"}}
            (k/place "Header" "mobile-header" :with {:title {:param "title"} :actionLabel "Info"} :size ["fill" nil])
            (k/board "Messages" {:size ["fill" "fill"] :tokens {:padding "spacing.lg" :gap "spacing.md"} :slot "messages"}
                     (k/board "Turn {index}" {:size ["fill" "auto"] :tokens {:gap "spacing.md"} :repeat {:param "messages"}}
                              (k/place "Theirs" "chat-bubble")
                              (assoc (k/place "Mine" "chat-bubble" :variant {:Side "Mine"})
                                     :layoutChild {:alignSelf "end"})))
            (k/place "Input" "message-input" :size ["fill" nil]))))

(def auth-panel
  (k/recipe
   {:name "auth-panel" :title "Sign-in panel" :category "section"
    :purpose "The panel to sign in with: email, password, the button, and the ways around it."
    :keywords ["login" "sign in" "log in" "authentication"]
    :params [(k/ptext "title" "Welcome back" "The heading")
             (k/ptext "submitLabel" "Sign in" "The words on the button")]}
   (k/board "Sign-in panel" {:w 400 :size ["fix" "auto"] :tokens (assoc k/surface :padding "spacing.xl" :gap "spacing.lg")}
            (k/text "Title" "{title}" "lg" :weight "bold" :grow true)
            (k/place "Email" "text-field" :with {:label "Email" :placeholder "you@example.com" :helper " "} :size ["fill" nil])
            (k/place "Password" "text-field" :with {:label "Password" :placeholder "••••••••" :helper " "} :size ["fill" nil])
            (k/place "Submit" "button" :with {:label {:param "submitLabel"}} :size ["fill" nil])
            (k/place "Or" "divider" :with {:labelled true} :size ["fill" nil])
            (k/board "Links" {:dir "row" :justify "space-between" :size ["fill" "auto"]}
                     (k/place "Forgot" "link" :with {:label "Forgot password?"} :variant {:Tone "Muted"})
                     (k/place "Sign up" "link" :with {:label "Create an account"})))))

(def order-summary
  (k/recipe
   {:name "order-summary" :title "Order summary" :category "section"
    :purpose "What is being bought and what it comes to, with the button that pays."
    :keywords ["checkout" "cart total" "receipt" "invoice"]
    :params [(k/ptext "title" "Order summary" "The heading")
             (k/pnum "lines" 3 1 6 "How many lines before the total")
             (k/ptext "actionLabel" "Pay now" "The words on the button")]}
   (k/board "Order summary" {:w 400 :size ["fix" "auto"] :tokens (assoc k/surface :padding "spacing.xl" :gap "spacing.sm")}
            (k/text "Title" "{title}" "lg" :weight "bold" :grow true)
            (k/place "Line {index}" "detail-row" :repeat {:param "lines"} :with {:label "Item {index}" :value "$0"} :size ["fill" nil])
            (k/place "Rule" "divider" :size ["fill" nil])
            (k/place "Total" "detail-row" :with {:label "Total" :value "$0"} :variant {:Emphasis "Total"} :size ["fill" nil])
            (k/place "Action" "button" :with {:label {:param "actionLabel"}} :size ["fill" nil]))))

(def file-list
  (k/recipe
   {:name "file-list" :title "File list" :category "section"
    :purpose "Files as rows under a title, with a place to drop more."
    :keywords ["documents" "attachments" "uploads" "downloads"]
    :params [(k/ptext "title" "Files" "The list's title")
             (k/pnum "files" 3 1 8 "How many files")
             (k/pbool "upload" true "Put a drop area on top")]}
   (k/board "File list" {:w 460 :size ["fix" "auto"] :tokens {:gap "spacing.md"}}
            (k/text "Title" "{title}" "lg" :weight "bold" :grow true)
            (k/place "Upload" "file-upload" :when {:param "upload"} :size ["fill" nil])
            (k/place "File {index}" "file-row" :repeat {:param "files"} :with {:name "File {index}"} :size ["fill" nil]))))

(def profile-header
  (k/recipe
   {:name "profile-header" :title "Profile header" :category "section"
    :purpose "The top of a person's or an account's page: a cover, who they are and what you can do."
    :keywords ["user profile" "account page" "bio" "cover"]
    :params [(k/ptext "name" "Ada Lovelace" "Who they are")
             (k/ptext "bio" "A line about them." "The line under the name")
             (k/ptext "actionLabel" "Follow" "The words on the button")]}
   (k/board "Profile header" {:w 960 :size ["fix" "auto"] :tokens {:fill "color.surface" :gap "spacing.lg"}}
            (k/media "Cover" 180)
            (k/board "About" {:dir "row" :align "center" :size ["fill" "auto"] :tokens {:paddingX "spacing.xl" :paddingY "spacing.lg" :gap "spacing.lg"}}
                     (k/place "Avatar" "avatar" :variant {:Size "Large"})
                     (k/board "Text" {:size ["fill" "auto"] :tokens {:gap "spacing.xs"}}
                              (k/text "Name" "{name}" "lg" :weight "bold")
                              (k/text "Bio" "{bio}" "sm" :fill "color.text-muted"))
                     (k/place "Action" "button" :with {:label {:param "actionLabel"}})))))

(def not-found
  (k/recipe
   {:name "not-found" :title "Not found" :category "section"
    :purpose "What a page shows when it is not there: the number, a kind word and the way home."
    :keywords ["404" "error page" "missing page" "lost"]
    :params [(k/ptext "title" "This page is not here" "The message")
             (k/ptext "body" "It may have moved, or the link may be wrong." "The line under it")
             (k/ptext "actionLabel" "Back to home" "The words on the button")]}
   (section "Not found" {:tokens {:fill "color.background" :padding "spacing.3xl" :gap "spacing.lg"}}
            (k/text "Code" "404" "2xl" :fill "color.primary" :weight "bold" :align "center")
            (k/text "Title" "{title}" "xl" :weight "bold" :align "center")
            (k/text "Body" "{body}" "md" :fill "color.text-muted" :align "center")
            (k/place "Action" "button" :with {:label {:param "actionLabel"}}))))

(def chart-card
  (k/recipe
   {:name "chart-card" :title "Chart card" :category "section"
    :purpose "A card for a chart: its title, the figure it comes to, and the place where the chart goes."
    :keywords ["graph" "analytics card" "trend" "report"]
    :params [(k/ptext "title" "Revenue" "What the chart shows")
             (k/ptext "value" "$0" "The figure it comes to")]}
   (k/board "Chart card" {:w 560 :size ["fix" "auto"] :tokens (assoc k/surface :padding "spacing.xl" :gap "spacing.md")}
            (k/board "Heading" {:dir "row" :align "center" :justify "space-between" :size ["fill" "auto"]}
                     (k/text "Title" "{title}" "sm" :fill "color.text-muted" :weight "medium")
                     (k/place "Range" "segmented-control" :with {:segments 3}))
            (k/text "Value" "{value}" "xl" :weight "bold")
            (k/media "Chart" 220))))

(def all
  [cta-section faq testimonials logo-cloud team-section newsletter contact-section blog-list article-header
   gallery page-header settings-section activity-feed notification-list kanban-board chat-thread auth-panel
   order-summary file-list profile-header not-found chart-card])
