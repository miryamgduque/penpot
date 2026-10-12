;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipes.more-containers
  "More built-in containers and the parts they repeat; see
  `app.common.agent.recipe` for the format and
  `app.common.agent.recipes.kit` for how they are written."
  (:require
   [app.common.agent.recipes.kit :as k]))

(def ^:private padded
  (assoc k/surface :padding "spacing.lg" :gap "spacing.md"))

(def accordion-item
  (k/recipe
   {:name "accordion-item" :title "Accordion item" :category "container"
    :purpose "A question or a heading that opens to show what is under it."
    :keywords ["collapsible" "disclosure" "expand" "faq item"]
    :params [(k/ptext "title" "A question someone asks" "The line that is always shown")
             (k/ptext "body" "The answer, in a sentence or two." "What opening it shows")]
    :variants [(k/axis "State" "Closed" "Open")]}
   (k/board "Accordion item" {:w 560 :size ["fix" "auto"]
                              :tokens {:fill "color.surface" :paddingY "spacing.md" :gap "spacing.sm"}}
            (k/board "Heading" {:dir "row" :align "center" :size ["fill" "auto"] :tokens {:gap "spacing.md"}}
                     (k/text "Title" "{title}" "md" :weight "medium" :grow true)
                     (k/icon "Chevron" "Small" "Muted"))
            (k/text "Body" "{body}" "sm" :fill "color.text-muted" :grow true
                    :when {:variant "State" :is "Open"}))))

(def accordion
  (k/recipe
   {:name "accordion" :title "Accordion" :category "container"
    :purpose "A list of headings that each open to show more; the first one is open."
    :keywords ["faq" "collapsible list" "disclosure group"]
    :params [(k/pnum "items" 4 2 8 "How many headings")]}
   (k/board "Accordion" {:w 560 :size ["fix" "auto"] :tokens {:gap "spacing.xs"}}
            (k/place "Item {index}" "accordion-item" :repeat {:param "items"} :size ["fill" nil]
                     :vary [(k/at 1 {:variant {:State "Open"}})]))))

(def menu-item
  (k/recipe
   {:name "menu-item" :title "Menu item" :category "container"
    :purpose "One line of a menu: an icon, what it does and its shortcut."
    :keywords ["dropdown item" "option" "command" "context menu item"]
    :params [(k/ptext "label" "Menu item" "What it does")
             (k/ptext "hint" "⌘K" "Its shortcut")]
    :variants [(k/axis "State" "Default" "Current" "Danger")]}
   (k/board "Menu item" {:dir "row" :align "center" :w 220 :size ["fix" "auto"]
                         :tokens {:fill "color.surface" :borderRadius "radius.sm" :gap "spacing.sm"
                                  :paddingX "spacing.sm" :paddingY "spacing.sm"}
                         :vary [(k/on {:State "Current"} {:tokens {:fill "color.surface-muted"}})]}
            (k/icon)
            (k/text "Label" "{label}" "sm" :grow true
                    :vary [(k/on {:State "Danger"} {:tokens {:fill "color.danger"}})])
            (k/text "Hint" "{hint}" "xs" :fill "color.text-muted"))))

(def menu
  (k/recipe
   {:name "menu" :title "Menu" :category "container"
    :purpose "A floating list of things to do: what a button, a right click or an account opens."
    :keywords ["dropdown" "context menu" "popover menu" "actions"]
    :params [(k/pnum "items" 4 2 8 "How many lines; the first is the one pointed at")]}
   (k/board "Menu" {:w 236 :size ["fix" "auto"]
                    :tokens (assoc k/surface :borderRadius "radius.md" :padding "spacing.xs" :gap "spacing.xs"
                                   :shadow "shadow.md")}
            (k/place "Item {index}" "menu-item" :repeat {:param "items"} :with {:label "Item {index}"}
                     :size ["fill" nil] :vary [(k/at 1 {:variant {:State "Current"}})]))))

(def popover
  (k/recipe
   {:name "popover" :title "Popover" :category "container"
    :purpose "A small floating panel with a title, a few words and room for controls."
    :keywords ["flyout" "bubble" "floating panel" "hover card"]
    :params [(k/ptext "title" "Popover title" "The heading")
             (k/ptext "body" "A few words that explain or ask." "The text under it")]}
   (k/board "Popover" {:w 280 :size ["fix" "auto"] :tokens (assoc padded :borderRadius "radius.md" :shadow "shadow.md") :slot "content"}
            (k/text "Title" "{title}" "sm" :weight "bold" :grow true)
            (k/text "Body" "{body}" "sm" :fill "color.text-muted" :grow true))))

(def toast
  (k/recipe
   {:name "toast" :title "Toast" :category "container"
    :purpose "A short message that appears for a moment to say what just happened, with one way to act."
    :keywords ["snackbar" "notification" "flash message" "undo"]
    :params [(k/ptext "message" "Changes saved" "What happened")
             (k/ptext "actionLabel" "Undo" "The words of its action")]
    :variants [(k/axis "Tone" "Neutral" "Success" "Danger")]}
   (k/board "Toast" {:dir "row" :align "center" :w 360 :size ["fix" "auto"]
                     :tokens {:fill "color.text" :borderRadius "radius.md" :gap "spacing.md"
                              :paddingX "spacing.lg" :paddingY "spacing.md" :shadow "shadow.md"}
                     :vary [(k/on {:Tone "Success"} {:tokens {:fill "color.success"}})
                            (k/on {:Tone "Danger"} {:tokens {:fill "color.danger"}})]}
            (k/text "Message" "{message}" "sm" :fill "color.background" :grow true)
            (k/text "Action" "{actionLabel}" "sm" :fill "color.background" :weight "bold"))))

(def banner
  (k/recipe
   {:name "banner" :title "Banner" :category "container"
    :purpose "A strip across the top of a page with news for everyone and one thing to do about it."
    :keywords ["announcement" "notice bar" "promo" "system message"]
    :params [(k/ptext "message" "Something everyone should know." "The news")
             (k/ptext "actionLabel" "Learn more" "The words on its button")]
    :variants [(k/axis "Tone" "Info" "Warning")]}
   (k/board "Banner" {:dir "row" :align "center" :justify "center" :w 1200 :size ["fix" "auto"]
                      :tokens {:fill "color.info" :gap "spacing.lg" :paddingX "spacing.xl" :paddingY "spacing.sm"}
                      :vary [(k/on {:Tone "Warning"} {:tokens {:fill "color.warning"}})]}
            (k/text "Message" "{message}" "sm" :fill "color.on-primary" :weight "medium")
            (k/place "Action" "button" :with {:label {:param "actionLabel"}} :variant {:Kind "Secondary" :Size "Small"}))))

(def drawer
  (k/recipe
   {:name "drawer" :title "Drawer" :category "container"
    :purpose "A panel that slides in from the side with a title, room for content and its actions at the bottom."
    :keywords ["side panel" "sheet" "slide over" "details panel"]
    :params [(k/ptext "title" "Details" "The heading")
             (k/ptext "confirmLabel" "Save" "The words on its main button")]}
   (k/board "Drawer" {:w 400 :h 800 :size ["fix" "fix"]
                      :tokens {:fill "color.surface" :strokeColor "color.border" :strokeWidth "border-width.sm"
                               :shadow "shadow.lg"}}
            (k/board "Header" {:dir "row" :align "center" :size ["fill" "auto"] :tokens {:padding "spacing.lg" :gap "spacing.md"}}
                     (k/text "Title" "{title}" "lg" :weight "bold" :grow true)
                     (k/place "Close" "icon-button"))
            (k/board "Body" {:size ["fill" "fill"] :tokens {:padding "spacing.lg" :gap "spacing.md"} :slot "body"}
                     (k/text "Body text" "What the drawer is about." "sm" :fill "color.text-muted" :grow true))
            (k/board "Footer" {:dir "row" :justify "end" :size ["fill" "auto"] :tokens {:padding "spacing.lg" :gap "spacing.sm"}}
                     (k/place "Cancel" "button" :with {:label "Cancel"} :variant {:Kind "Secondary"})
                     (k/place "Confirm" "button" :with {:label {:param "confirmLabel"}})))))

(def action-sheet
  (k/recipe
   {:name "action-sheet" :title "Action sheet" :category "container"
    :purpose "A sheet that rises from the bottom of a phone with a few things to do and a way out."
    :keywords ["bottom sheet" "mobile menu" "share sheet"]
    :params [(k/ptext "title" "What do you want to do?" "The question")
             (k/pnum "actions" 3 1 6 "How many things to do")]}
   (k/board "Action sheet" {:w 393 :size ["fix" "auto"]
                            :tokens {:fill "color.surface" :borderRadius "radius.lg" :padding "spacing.lg" :gap "spacing.sm"
                                     :shadow "shadow.lg"}}
            (k/rect "Grabber" 36 4 "color.border" :radius "radius.full")
            (k/text "Title" "{title}" "sm" :fill "color.text-muted" :align "center" :grow true)
            (k/place "Action {index}" "menu-item" :repeat {:param "actions"} :with {:label "Action {index}"} :size ["fill" nil])
            (k/place "Cancel" "button" :with {:label "Cancel"} :variant {:Kind "Secondary"} :size ["fill" nil]))))

(def comment-item
  (k/recipe
   {:name "comment" :title "Comment" :category "container"
    :purpose "What someone said: who, when and their words."
    :keywords ["reply" "review" "note" "thread item"]
    :params [(k/ptext "author" "Ada Lovelace" "Who said it")
             (k/ptext "time" "2 hours ago" "When")
             (k/ptext "body" "What they had to say, in a sentence or two." "Their words")
             (k/ptext "initials" "AL" "The letters in the avatar")]
    :component true}
   (k/board "Comment" {:dir "row" :align "start" :w 480 :size ["fix" "auto"] :tokens {:gap "spacing.md"}}
            (k/place "Avatar" "avatar" :with {:initials {:param "initials"}} :variant {:Size "Small"})
            (k/board "Text" {:size ["fill" "auto"] :tokens {:gap "spacing.xs"}}
                     (k/board "Heading" {:dir "row" :align "center" :size ["fill" "auto"] :tokens {:gap "spacing.sm"}}
                              (k/text "Author" "{author}" "sm" :weight "medium")
                              (k/text "Time" "{time}" "xs" :fill "color.text-muted"))
                     (k/text "Body" "{body}" "sm" :fill "color.text-muted" :grow true)))))

(def chat-bubble
  (k/recipe
   {:name "chat-bubble" :title "Chat bubble" :category "container"
    :purpose "One message of a conversation, on the side of whoever sent it."
    :keywords ["message" "speech bubble" "chat message" "dm"]
    :params [(k/ptext "message" "A message, as someone would write it." "The words")
             (k/ptext "time" "9:41" "When it was sent")]
    :variants [(k/axis "Side" "Theirs" "Mine")]}
   (k/board "Chat bubble" {:w 280 :size ["fix" "auto"]
                           :tokens {:fill "color.surface-muted" :borderRadius "radius.lg"
                                    :paddingX "spacing.md" :paddingY "spacing.sm" :gap "spacing.xs"}
                           :vary [(k/on {:Side "Mine"} {:tokens {:fill "color.primary"}})]}
            (k/text "Message" "{message}" "sm" :grow true
                    :vary [(k/on {:Side "Mine"} {:tokens {:fill "color.on-primary"}})])
            (k/text "Time" "{time}" "xs" :fill "color.text-muted"
                    :vary [(k/on {:Side "Mine"} {:tokens {:fill "color.on-primary"}})]))))

(def message-input
  (k/recipe
   {:name "message-input" :title "Message input" :category "container"
    :purpose "The bar at the bottom of a conversation: a box to write in, something to attach and a button to send."
    :keywords ["composer" "chat input" "reply box"]
    :params [(k/ptext "placeholder" "Write a message" "The words shown while it is empty")]
    :component true}
   (k/board "Message input" {:dir "row" :align "center" :w 393 :size ["fix" "auto"]
                             :tokens {:fill "color.surface" :gap "spacing.sm" :padding "spacing.sm"}}
            (k/place "Attach" "icon-button")
            (k/board "Field" {:dir "row" :align "center" :size ["fill" "auto"]
                              :tokens {:fill "color.surface-muted" :borderRadius "radius.full"
                                       :paddingX "spacing.md" :paddingY "spacing.sm"}}
                     (k/text "Value" "{placeholder}" "sm" :fill "color.text-muted" :grow true))
            (k/place "Send" "icon-button" :variant {:Kind "Filled" :State "Active"}))))

(def product-card
  (k/recipe
   {:name "product-card" :title "Product card" :category "container"
    :purpose "Something for sale: its picture, its name, its price and a button to take it."
    :keywords ["shop item" "ecommerce" "catalog item" "listing"]
    :params [(k/ptext "name" "Product name" "What it is")
             (k/ptext "price" "$0" "What it costs")
             (k/ptext "actionLabel" "Add to cart" "The words on its button")]
    :component true}
   (k/board "Product card" {:w 260 :size ["fix" "auto"] :tokens (assoc k/surface :gap "spacing.md" :padding "spacing.md")}
            (k/media "Picture" 200)
            (k/text "Name" "{name}" "md" :weight "medium" :grow true)
            (k/text "Price" "{price}" "lg" :weight "bold")
            (k/place "Action" "button" :with {:label {:param "actionLabel"}} :size ["fill" nil]))))

(def media-card
  (k/recipe
   {:name "media-card" :title "Media card" :category "container"
    :purpose "A picture with a heading and a line under it: an article, a video, a place."
    :keywords ["article card" "post" "thumbnail" "blog card"]
    :params [(k/ptext "tag" "Category" "What kind of thing it is")
             (k/ptext "title" "A heading that says what this is" "The heading")
             (k/ptext "meta" "5 min read" "The line under it")]
    :component true}
   (k/board "Media card" {:w 340 :size ["fix" "auto"] :tokens {:gap "spacing.md"}}
            (k/media "Picture" 200)
            (k/text "Tag" "{tag}" "xs" :fill "color.primary" :weight "medium")
            (k/text "Title" "{title}" "lg" :weight "bold" :grow true)
            (k/text "Meta" "{meta}" "sm" :fill "color.text-muted"))))

(def profile-card
  (k/recipe
   {:name "profile-card" :title "Profile card" :category "container"
    :purpose "A person: their picture, their name, what they do and a way to reach them."
    :keywords ["user card" "member" "contact card" "author"]
    :params [(k/ptext "name" "Ada Lovelace" "Who they are")
             (k/ptext "role" "Engineer" "What they do")
             (k/ptext "initials" "AL" "The letters in the avatar")
             (k/ptext "actionLabel" "Message" "The words on the button")]
    :component true}
   (k/board "Profile card" {:align "center" :w 260 :size ["fix" "auto"] :tokens (assoc padded :padding "spacing.xl")}
            (k/place "Avatar" "avatar" :with {:initials {:param "initials"}} :variant {:Size "Large"})
            (k/text "Name" "{name}" "md" :weight "bold" :align "center")
            (k/text "Role" "{role}" "sm" :fill "color.text-muted" :align "center")
            (k/place "Action" "button" :with {:label {:param "actionLabel"}} :variant {:Kind "Secondary"} :size ["fill" nil]))))

(def testimonial-card
  (k/recipe
   {:name "testimonial-card" :title "Testimonial card" :category "container"
    :purpose "What a customer said, with who they are."
    :keywords ["quote" "review" "customer story" "social proof"]
    :params [(k/ptext "quote" "This changed how our team works." "Their words")
             (k/ptext "name" "Ada Lovelace" "Who said it")
             (k/ptext "role" "Engineer at Analytical" "What they do")
             (k/ptext "initials" "AL" "The letters in the avatar")]
    :component true}
   (k/board "Testimonial card" {:w 360 :size ["fix" "auto"] :tokens (assoc padded :padding "spacing.xl" :gap "spacing.lg")}
            (k/text "Quote" "{quote}" "md" :grow true)
            (k/board "Author" {:dir "row" :align "center" :size ["fill" "auto"] :tokens {:gap "spacing.md"}}
                     (k/place "Avatar" "avatar" :with {:initials {:param "initials"}})
                     (k/board "Who" {:size ["fill" "auto"] :tokens {:gap "spacing.xs"}}
                              (k/text "Name" "{name}" "sm" :weight "medium")
                              (k/text "Role" "{role}" "xs" :fill "color.text-muted"))))))

(def notification-item
  (k/recipe
   {:name "notification-item" :title "Notification item" :category "container"
    :purpose "One thing that happened and needs a look: what, when, and whether it has been read."
    :keywords ["alert row" "inbox item" "update" "activity"]
    :params [(k/ptext "title" "Something happened" "What happened")
             (k/ptext "time" "5 min ago" "When")]
    :variants [(k/axis "State" "Unread" "Read")]}
   (k/board "Notification item" {:dir "row" :align "center" :w 360 :size ["fix" "auto"]
                                 :tokens {:fill "color.surface" :gap "spacing.md" :padding "spacing.md"}}
            (k/ellipse "Dot" 8 8 "color.primary" :vary [(k/on {:State "Read"} {:tokens {:fill "color.surface"}})])
            (k/board "Text" {:size ["fill" "auto"] :tokens {:gap "spacing.xs"}}
                     (k/text "Title" "{title}" "sm" :weight "medium" :grow true)
                     (k/text "Time" "{time}" "xs" :fill "color.text-muted")))))

(def timeline-item
  (k/recipe
   {:name "timeline-item" :title "Timeline item" :category "container"
    :purpose "One step of a history: a mark on a line, what happened and when."
    :keywords ["history" "event" "milestone" "log entry"]
    :params [(k/ptext "title" "Something happened" "What happened")
             (k/ptext "time" "Today, 9:41" "When")]
    :variants [(k/axis "State" "Done" "Current" "Next")]}
   (k/board "Timeline item" {:dir "row" :align "start" :w 360 :size ["fix" "auto"] :tokens {:gap "spacing.md"}}
            (k/ellipse "Mark" 12 12 "color.success"
                       :vary [(k/on {:State "Current"} {:tokens {:fill "color.primary"}})
                              (k/on {:State "Next"} {:tokens {:fill "color.border"}})])
            (k/board "Text" {:size ["fill" "auto"] :tokens {:gap "spacing.xs" :paddingY "spacing.xs"}}
                     (k/text "Title" "{title}" "sm" :weight "medium" :grow true)
                     (k/text "Time" "{time}" "xs" :fill "color.text-muted")))))

(def file-row
  (k/recipe
   {:name "file-row" :title "File row" :category "container"
    :purpose "One file in a list: its kind, its name, its size and what can be done with it."
    :keywords ["document row" "attachment" "download" "upload item"]
    :params [(k/ptext "name" "document.pdf" "The file's name")
             (k/ptext "meta" "2.4 MB" "Its size or date")]
    :component true}
   (k/board "File row" {:dir "row" :align "center" :w 420 :size ["fix" "auto"]
                        :tokens (assoc k/surface :borderRadius "radius.md" :gap "spacing.md" :padding "spacing.md")}
            (k/icon "Kind" "Large" "Muted")
            (k/board "Text" {:size ["fill" "auto"] :tokens {:gap "spacing.xs"}}
                     (k/text "Name" "{name}" "sm" :weight "medium" :grow true)
                     (k/text "Meta" "{meta}" "xs" :fill "color.text-muted"))
            (k/place "More" "icon-button"))))

(def kanban-card
  (k/recipe
   {:name "kanban-card" :title "Kanban card" :category "container"
    :purpose "One task on a board: what it is, a label and who has it."
    :keywords ["task card" "ticket" "issue" "todo"]
    :params [(k/ptext "title" "A task to be done" "What the task is")
             (k/ptext "tag" "Design" "Its label")
             (k/ptext "initials" "AL" "The letters in the avatar")]
    :component true}
   (k/board "Kanban card" {:w 264 :size ["fix" "auto"] :tokens (assoc k/surface :borderRadius "radius.md" :padding "spacing.md" :gap "spacing.md")}
            (k/text "Title" "{title}" "sm" :weight "medium" :grow true)
            (k/board "Meta" {:dir "row" :align "center" :justify "space-between" :size ["fill" "auto"]}
                     (k/place "Tag" "badge" :with {:label {:param "tag"}} :variant {:Tone "Neutral"})
                     (k/place "Owner" "avatar" :with {:initials {:param "initials"}} :variant {:Size "Small"})))))

(def kanban-column
  (k/recipe
   {:name "kanban-column" :title "Kanban column" :category "container"
    :purpose "One stage of a board: its name, how many tasks it has, and its cards."
    :keywords ["board column" "lane" "stage" "list of tasks"]
    :params [(k/ptext "title" "To do" "The stage's name")
             (k/pnum "cards" 3 0 8 "How many cards")]}
   (k/board "Kanban column" {:w 296 :size ["fix" "auto"]
                             :tokens {:fill "color.surface-muted" :borderRadius "radius.lg" :padding "spacing.lg" :gap "spacing.md"}}
            (k/text "Title" "{title}" "sm" :weight "bold" :grow true)
            (k/board "Cards" {:size ["fill" "auto"] :tokens {:gap "spacing.sm"} :slot "cards"}
                     (k/place "Card {index}" "kanban-card" :repeat {:param "cards"} :with {:title "Task {index}"} :size ["fill" nil])))))

(def settings-row
  (k/recipe
   {:name "settings-row" :title "Settings row" :category "container"
    :purpose "One setting: what it is, a line about it, and the switch that turns it on."
    :keywords ["preference" "option row" "toggle row"]
    :params [(k/ptext "title" "A setting" "What the setting is")
             (k/ptext "description" "What turning it on does." "The line about it")]
    :variants [(k/axis "State" "Off" "On")]}
   ;; as narrow as a phone lets it be: placed in something wider it grows
   (k/board "Settings row" {:dir "row" :align "center" :w 320 :size ["fix" "auto"]
                            :tokens {:fill "color.surface" :gap "spacing.lg" :paddingY "spacing.md"}}
            (k/board "Text" {:size ["fill" "auto"] :tokens {:gap "spacing.xs"}}
                     (k/text "Title" "{title}" "sm" :weight "medium" :grow true)
                     (k/text "Description" "{description}" "xs" :fill "color.text-muted" :grow true))
            (k/place "Switch" "choice-control" :with {:label ""} :variant {:Type "Switch" :State "Off"}
                     :vary [(k/on {:State "On"} {:variant {:State "On"}})]))))

(def detail-row
  (k/recipe
   {:name "detail-row" :title "Detail row" :category "container"
    :purpose "A fact as a name and its value: one line of a summary or a receipt."
    :keywords ["key value" "description list" "summary line" "property"]
    :params [(k/ptext "label" "Label" "The name of the fact")
             (k/ptext "value" "Value" "Its value")]
    :variants [(k/axis "Emphasis" "Default" "Total")]}
   (k/board "Detail row" {:dir "row" :align "center" :justify "space-between" :w 360 :size ["fix" "auto"]
                          :tokens {:paddingY "spacing.sm" :gap "spacing.md"}}
            (k/text "Label" "{label}" "sm" :fill "color.text-muted"
                    :vary [(k/on {:Emphasis "Total"} {:tokens {:fill "color.text" :fontWeight "font-weight.bold"}})])
            (k/text "Value" "{value}" "sm"
                    :vary [(k/on {:Emphasis "Total"} {:tokens {:fontWeight "font-weight.bold"}})]))))

(def callout
  (k/recipe
   {:name "callout" :title "Callout" :category "container"
    :purpose "A tinted box inside a text that sets a tip, a note or a warning apart."
    :keywords ["tip" "note" "aside" "info box"]
    :params [(k/ptext "body" "A tip worth setting apart from the rest." "The words")]
    :variants [(k/axis "Tone" "Note" "Warning")]}
   (k/board "Callout" {:dir "row" :align "start" :w 480 :size ["fix" "auto"]
                       :tokens {:fill "color.surface-muted" :borderRadius "radius.md" :padding "spacing.lg" :gap "spacing.md"}}
            (k/icon "Icon" "Small" "Primary")
            (k/text "Body" "{body}" "sm" :grow true))))

(def all
  [accordion-item accordion menu-item menu popover toast banner drawer action-sheet comment-item chat-bubble
   message-input product-card media-card profile-card testimonial-card notification-item timeline-item
   file-row kanban-card kanban-column settings-row detail-row callout])
