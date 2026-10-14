;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipes.more-controls
  "More built-in controls; see `app.common.agent.recipe` for the format and
  `app.common.agent.recipes.kit` for how they are written."
  (:require
   [app.common.agent.recipes.kit :as k]))

(def ^:private input-tokens
  {:fill "color.surface"
   :strokeColor "color.border"
   :strokeWidth "border-width.sm"
   :borderRadius "radius.md"
   :gap "spacing.sm"
   :paddingX "spacing.md"
   :paddingY "spacing.sm"})

(def ^:private states
  [(k/on {:State "Focused"} {:tokens {:strokeColor "color.primary" :strokeWidth "border-width.md"}})
   (k/on {:State "Disabled"} {:tokens {:fill "color.surface-muted"}})])

(defn- labelled
  "A label over what is typed into or picked from."
  [nm w & children]
  (apply k/board nm {:w w :size ["fix" "auto"] :tokens {:gap "spacing.xs"}}
         (k/text "Label" "{label}" "sm" :weight "medium")
         children))

(def textarea
  (k/recipe
   {:name "textarea" :title "Text area" :category "control"
    :purpose "A labelled box to type several lines into: a message, a note, a description."
    :keywords ["multiline" "message" "comment box" "notes"]
    :params [(k/ptext "label" "Message" "What the box is for")
             (k/ptext "placeholder" "Write something…" "The words shown while it is empty")]
    :variants [(k/axis "State" "Default" "Focused" "Disabled")]}
   (labelled "Text area" 320
             (k/board "Input" {:h 96 :size ["fill" "fix"] :tokens input-tokens :vary states}
                      (k/text "Value" "{placeholder}" "sm" :fill "color.text-muted" :grow true)))))

(def search-field
  (k/recipe
   {:name "search-field" :title "Search field" :category "control"
    :purpose "A box to type what to look for, with a magnifier in front."
    :keywords ["search" "find" "filter" "query"]
    :params [(k/ptext "placeholder" "Search" "The words shown while it is empty")]
    :variants [(k/axis "State" "Default" "Focused" "Disabled")]}
   (k/board "Search field" {:dir "row" :align "center" :w 280 :size ["fix" "auto"] :tokens input-tokens :vary states}
            (k/icon)
            (k/text "Value" "{placeholder}" "sm" :fill "color.text-muted" :grow true))))

(def number-field
  (k/recipe
   {:name "number-field" :title "Number field" :category "control"
    :purpose "A labelled number with a button on each side to take one off or add one."
    :keywords ["stepper" "quantity" "counter" "amount"]
    :params [(k/ptext "label" "Quantity" "What is counted")
             (k/ptext "value" "1" "The number shown")]
    :variants [(k/axis "State" "Default" "Disabled")]}
   (labelled "Number field" 160
             (k/board "Input" {:dir "row" :align "center" :justify "space-between" :size ["fill" "auto"]
                               :tokens input-tokens
                               :vary [(k/on {:State "Disabled"} {:tokens {:fill "color.surface-muted"}})]}
                      (k/icon "Less" "Small" "Muted")
                      (k/text "Value" "{value}" "sm")
                      (k/icon "More" "Small" "Muted")))))

(def date-field
  (k/recipe
   {:name "date-field" :title "Date field" :category "control"
    :purpose "A labelled box that shows a date and opens a calendar to pick one."
    :keywords ["date picker" "calendar" "day" "deadline"]
    :params [(k/ptext "label" "Date" "What the date is of")
             (k/ptext "value" "Pick a date" "The date shown")]
    :variants [(k/axis "State" "Default" "Focused" "Disabled")]}
   (labelled "Date field" 220
             (k/board "Input" {:dir "row" :align "center" :size ["fill" "auto"] :tokens input-tokens :vary states}
                      (k/text "Value" "{value}" "sm" :grow true)
                      (k/icon "Calendar" "Small" "Muted")))))

(def slider
  (k/recipe
   {:name "slider" :title "Slider" :category "control"
    :purpose "A track with a handle to pick a value between two ends."
    :keywords ["range" "volume" "level" "scrubber"]
    :params [(k/ptext "label" "Level" "What is being set")
             (k/ptext "value" "50" "The value shown")]
    :component true}
   (k/board "Slider" {:w 280 :size ["fix" "auto"] :tokens {:gap "spacing.sm"}}
            (k/board "Heading" {:dir "row" :justify "space-between" :size ["fill" "auto"]}
                     (k/text "Label" "{label}" "sm" :weight "medium")
                     (k/text "Value" "{value}" "sm" :fill "color.text-muted"))
            (k/board "Track" {:dir "row" :align "center" :h 16 :size ["fill" "fix"]}
                     (k/rect "Filled" 140 4 "color.primary" :radius "radius.full")
                     (k/ellipse "Handle" 16 16 "color.primary")
                     (k/rect "Rest" nil 4 "color.border" :radius "radius.full" :grow true)))))

(def progress-bar
  (k/recipe
   {:name "progress-bar" :title "Progress bar" :category "control"
    :purpose "How far along something is: a bar that fills, with what it measures and how much."
    :keywords ["progress" "loading" "completion" "meter"]
    :params [(k/ptext "label" "Progress" "What is under way")
             (k/ptext "value" "60%" "How far along it is")]
    :component true}
   (k/board "Progress bar" {:w 280 :size ["fix" "auto"] :tokens {:gap "spacing.xs"}}
            (k/board "Heading" {:dir "row" :justify "space-between" :size ["fill" "auto"]}
                     (k/text "Label" "{label}" "sm" :weight "medium")
                     (k/text "Value" "{value}" "sm" :fill "color.text-muted"))
            (k/board "Track" {:dir "row" :h 8 :size ["fill" "fix"]
                              :tokens {:fill "color.surface-muted" :borderRadius "radius.full"}}
                     (k/rect "Filled" 168 8 "color.primary" :radius "radius.full")))))

(def spinner
  (k/recipe
   {:name "spinner" :title "Spinner" :category "control"
    :purpose "A small mark that something is loading, with a word about what."
    :keywords ["loading" "busy" "wait" "loader"]
    :params [(k/ptext "label" "Loading…" "What is being waited for")]
    :variants [(k/axis "Size" "Medium" "Small")]}
   (k/board "Spinner" {:dir "row" :align "center" :size ["auto" "auto"] :tokens {:gap "spacing.sm"}}
            (k/ellipse "Ring" 20 20 "color.primary" :vary [(k/on {:Size "Small"} {:width 14 :height 14})])
            (k/text "Label" "{label}" "sm" :fill "color.text-muted"))))

(def skeleton
  (k/recipe
   {:name "skeleton" :title "Skeleton" :category "control"
    :purpose "Grey bars where content will be, shown while it loads."
    :keywords ["loading placeholder" "shimmer" "ghost"]
    :params [(k/pnum "lines" 3 1 6 "How many bars")]
    :component true}
   (k/board "Skeleton" {:w 280 :size ["fix" "auto"] :tokens {:gap "spacing.sm"}}
            (k/rect "Line {index}" nil 12 "color.surface-muted" :radius "radius.sm" :grow true
                    :repeat {:param "lines"}))))

(def divider
  (k/recipe
   {:name "divider" :title "Divider" :category "control"
    :purpose "A thin line that parts one group from the next, with a word in the middle when wanted."
    :keywords ["separator" "rule" "line" "or"]
    :params [(k/pbool "labelled" false "Put a word in the middle")
             (k/ptext "label" "or" "That word")]
    :component true}
   (k/board "Divider" {:dir "row" :align "center" :w 320 :size ["fix" "auto"] :tokens {:gap "spacing.md"}}
            (k/rect "Line" nil 1 "color.border" :grow true)
            (k/text "Label" "{label}" "xs" :fill "color.text-muted" :when {:param "labelled"})
            (k/rect "Line end" nil 1 "color.border" :grow true :when {:param "labelled"}))))

(def chip
  (k/recipe
   {:name "chip" :title "Chip" :category "control"
    :purpose "A small rounded label that can be picked or taken away: a filter, a tag, a choice."
    :keywords ["tag" "filter chip" "pill" "token"]
    :params [(k/ptext "label" "Chip" "The word on it")]
    :variants [(k/axis "State" "Default" "Selected")]}
   (k/board "Chip" {:dir "row" :align "center" :size ["auto" "auto"]
                    :tokens {:fill "color.surface" :strokeColor "color.border" :strokeWidth "border-width.sm"
                             :borderRadius "radius.full" :gap "spacing.xs"
                             :paddingX "spacing.md" :paddingY "spacing.xs"}
                    :vary [(k/on {:State "Selected"} {:tokens {:fill "color.primary" :strokeColor "color.primary"}})]}
            (k/text "Label" "{label}" "sm" :weight "medium"
                    :vary [(k/on {:State "Selected"} {:tokens {:fill "color.on-primary"}})]))))

(def link
  (k/recipe
   {:name "link" :title "Link" :category "control"
    :purpose "Words that lead somewhere else, in the color of an action."
    :keywords ["anchor" "hyperlink" "text button"]
    :params [(k/ptext "label" "Learn more" "The words of the link")]
    :variants [(k/axis "Tone" "Primary" "Muted")]}
   (k/board "Link" {:dir "row" :align "center" :size ["auto" "auto"] :tokens {:gap "spacing.xs"}}
            (k/text "Label" "{label}" "sm" :fill "color.primary" :weight "medium"
                    :vary [(k/on {:Tone "Muted"} {:tokens {:fill "color.text-muted"}})]))))

(def icon-button
  (k/recipe
   {:name "icon-button" :title "Icon button" :category "control"
    :purpose "A button that is only an icon: close, more, edit, a tool of a toolbar."
    :keywords ["tool button" "action icon" "close" "more"]
    :variants [(k/axis "Kind" "Ghost" "Filled")
               (k/axis "State" "Default" "Active")]}
   (k/board "Icon button" {:dir "row" :align "center" :justify "center" :w 32 :h 32 :size ["fix" "fix"]
                           :tokens {:fill "color.background" :borderRadius "radius.md"}
                           :vary [(k/on {:Kind "Filled"} {:tokens {:fill "color.surface-muted"}})
                                  (k/on {:State "Active"} {:tokens {:fill "color.primary"}})]}
            (k/icon "Icon" "Small" "Strong"))))

(def segmented-control
  (k/recipe
   {:name "segmented-control" :title "Segmented control" :category "control"
    :purpose "A few choices side by side in one bar, one of them picked: a view, a unit, a mode."
    :keywords ["toggle group" "switcher" "tabs" "mode"]
    :params [(k/pnum "segments" 3 2 5 "How many choices; the first is the picked one")]}
   (k/board "Segmented control" {:dir "row" :align "center" :size ["auto" "auto"]
                                 :tokens {:fill "color.surface-muted" :borderRadius "radius.md" :padding "spacing.xs" :gap "spacing.xs"}}
            (k/place "Segment {index}" "segment" :repeat {:param "segments"} :with {:label "Option {index}"}
                     :vary [(k/at 1 {:variant {:State "Current"}})]))))

(def segment
  (k/recipe
   {:name "segment" :title "Segment" :category "control"
    :purpose "One choice of a segmented control, marked when it is the picked one."
    :keywords ["toggle" "option"]
    :params [(k/ptext "label" "Option" "The word on it")]
    :variants [(k/axis "State" "Default" "Current")]}
   (k/board "Segment" {:dir "row" :align "center" :justify "center" :size ["auto" "auto"]
                       :tokens {:fill "color.surface-muted" :borderRadius "radius.sm" :paddingX "spacing.md" :paddingY "spacing.xs"}
                       :vary [(k/on {:State "Current"} {:tokens {:fill "color.surface"}})]}
            (k/text "Label" "{label}" "sm" :fill "color.text-muted" :weight "medium"
                    :vary [(k/on {:State "Current"} {:tokens {:fill "color.text"}})]))))

(def choice-group
  (k/recipe
   {:name "choice-group" :title "Choice group" :category "control"
    :purpose "A titled list of checkboxes or radio buttons: several options to pick from."
    :keywords ["radio group" "checkbox group" "options" "multiple choice"]
    :params [(k/ptext "label" "Choose" "What is being chosen")
             (k/pnum "options" 3 2 6 "How many options")]}
   (k/board "Choice group" {:size ["auto" "auto"] :tokens {:gap "spacing.sm"}}
            (k/text "Label" "{label}" "sm" :weight "medium")
            (k/place "Option {index}" "choice-control" :repeat {:param "options"}
                     :with {:label "Option {index}"} :variant {:Type "Radio"}
                     :vary [(k/at 1 {:variant {:State "On"}})]))))

(def rating
  (k/recipe
   {:name "rating" :title "Rating" :category "control"
    :purpose "A row of stars that says how good something was judged, with the number beside it."
    :keywords ["stars" "score" "review"]
    :params [(k/ptext "value" "4.0" "The score shown")]
    :component true}
   (k/board "Rating" {:dir "row" :align "center" :size ["auto" "auto"] :tokens {:gap "spacing.xs"}}
            (k/icon "Star {index}" "Small" "Primary" :repeat 5)
            (k/text "Value" "{value}" "sm" :fill "color.text-muted"))))

(def tooltip
  (k/recipe
   {:name "tooltip" :title "Tooltip" :category "control"
    :purpose "A few words that explain a thing when it is pointed at."
    :keywords ["hint" "help text" "hover"]
    :params [(k/ptext "label" "What this does" "The words")]
    :component true}
   (k/board "Tooltip" {:dir "row" :align "center" :size ["auto" "auto"]
                       :tokens {:fill "color.text" :borderRadius "radius.sm" :paddingX "spacing.sm" :paddingY "spacing.xs"}}
            (k/text "Label" "{label}" "xs" :fill "color.background"))))

(def file-upload
  (k/recipe
   {:name "file-upload" :title "File upload" :category "control"
    :purpose "A dashed area to drop a file on, or press to pick one."
    :keywords ["dropzone" "attach" "upload" "drag and drop"]
    :params [(k/ptext "title" "Drop a file here" "What to do")
             (k/ptext "hint" "or click to browse — up to 10 MB" "The line under it")]
    :component true}
   (k/board "File upload" {:align "center" :justify "center" :w 360 :size ["fix" "auto"]
                           :tokens {:fill "color.surface-muted" :strokeColor "color.border" :strokeWidth "border-width.md"
                                    :borderRadius "radius.lg" :padding "spacing.2xl" :gap "spacing.sm"}}
            (k/icon "Icon" "Large" "Muted")
            (k/text "Title" "{title}" "sm" :weight "medium" :align "center")
            (k/text "Hint" "{hint}" "xs" :fill "color.text-muted" :align "center"))))

(def code-field
  (k/recipe
   {:name "code-field" :title "Code field" :category "control"
    :purpose "A row of single boxes for a short code, one digit in each."
    :keywords ["otp" "verification code" "pin" "two factor"]
    :params [(k/pnum "digits" 6 4 8 "How many boxes")]
    :component true}
   (k/board "Code field" {:dir "row" :size ["auto" "auto"] :tokens {:gap "spacing.sm"}}
            (k/board "Digit {index}" {:dir "row" :align "center" :justify "center" :w 44 :h 52 :size ["fix" "fix"]
                                      :tokens {:fill "color.surface" :strokeColor "color.border"
                                               :strokeWidth "border-width.sm" :borderRadius "radius.md"}
                                      :repeat {:param "digits"}}
                     (k/text "Value" "0" "lg" :weight "bold")))))

(def color-swatch
  (k/recipe
   {:name "color-swatch" :title "Color swatch" :category "control"
    :purpose "A sample of a color with its name and its value."
    :keywords ["palette" "color chip" "token sample"]
    :params [(k/ptext "name" "Primary" "The color's name")
             (k/ptext "value" "Its value" "Its value, as text")]
    :component true}
   (k/board "Color swatch" {:w 120 :size ["fix" "auto"] :tokens {:gap "spacing.xs"}}
            (k/rect "Sample" nil 72 "color.primary" :radius "radius.md" :grow true)
            (k/text "Name" "{name}" "sm" :weight "medium")
            (k/text "Value" "{value}" "xs" :fill "color.text-muted"))))

(def key-hint
  (k/recipe
   {:name "key-hint" :title "Key hint" :category "control"
    :purpose "A key of the keyboard, drawn as a small cap: a shortcut shown next to an action."
    :keywords ["kbd" "shortcut" "hotkey"]
    :params [(k/ptext "label" "⌘K" "The key or keys")]
    :component true}
   (k/board "Key hint" {:dir "row" :align "center" :justify "center" :size ["auto" "auto"]
                        :tokens {:fill "color.surface-muted" :strokeColor "color.border" :strokeWidth "border-width.sm"
                                 :borderRadius "radius.sm" :paddingX "spacing.sm" :paddingY "spacing.xs"}}
            (k/text "Label" "{label}" "xs" :fill "color.text-muted" :weight "medium"))))

(def all
  [textarea search-field number-field date-field slider progress-bar spinner skeleton divider chip
   link icon-button segmented-control segment choice-group rating tooltip file-upload code-field
   color-swatch key-hint])
