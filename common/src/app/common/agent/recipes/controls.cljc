;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipes.controls
  "The built-in recipes for controls; see `app.common.agent.recipe` for the
  format. A control is a component: it has no slot, and what is placed of it
  is an instance.")

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
                               :fontFamily "font-family.body"
                               :fontWeight "font-weight.medium"}
                      :vary [{:on {:Kind "Secondary"} :set {:tokens {:fill "color.text"}}}
                             {:on {:Kind "Ghost"} :set {:tokens {:fill "color.primary"}}}
                             {:on {:Size "Small"} :set {:tokens {:fontSize "font-size.xs"}}}]}]}})

(defn- field
  "What a text field and a select share: a label, a bordered input and a
  line of help. `inside` is what the input holds."
  [nm states inside more]
  {:type "board"
   :name nm
   :width 280
   :layout {:type "flex" :dir "column"}
   :layoutChild {:verticalSizing "auto"}
   :tokens {:gap "spacing.xs"}
   :children (into [{:type "text"
                     :name "Label"
                     :text "{label}"
                     :tokens {:fill "color.text"
                              :fontSize "font-size.sm"
                              :fontFamily "font-family.body"
                              :fontWeight "font-weight.medium"}}
                    {:type "board"
                     :name "Input"
                     :layout {:type "flex" :dir "row" :alignItems "center"}
                     :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                     :tokens {:fill "color.surface"
                              :strokeColor "color.border"
                              :strokeWidth "border-width.sm"
                              :borderRadius "radius.md"
                              :gap "spacing.sm"
                              :paddingX "spacing.md"
                              :paddingY "spacing.sm"}
                     :vary states
                     :children inside}]
                   more)})

(def text-field
  {:recipe 1
   :name "text-field"
   :title "Text field"
   :purpose "A labelled box to type one line into: a name, an email, a search."
   :category "control"
   :keywords ["input" "textbox" "form field" "email" "password" "search"]
   :params [{:name "label" :type "text" :default "Label"
             :description "What the field is for"}
            {:name "placeholder" :type "text" :default "Placeholder"
             :description "The words shown while it is empty"}
            {:name "helper" :type "text" :default "Helper text"
             :description "The line under the field"}]
   :variants [{:name "State" :values ["Default" "Focused" "Error" "Disabled"]}]
   :tree (field "Text field"
                [{:on {:State "Focused"}
                  :set {:tokens {:strokeColor "color.primary" :strokeWidth "border-width.md"}}}
                 {:on {:State "Error"}
                  :set {:tokens {:strokeColor "color.danger"}}}
                 {:on {:State "Disabled"}
                  :set {:tokens {:fill "color.surface-muted"}}}]
                [{:type "text"
                  :name "Value"
                  :text "{placeholder}"
                  :layoutChild {:horizontalSizing "fill"}
                  :tokens {:fill "color.text-muted" :fontSize "font-size.sm"
                           :fontFamily "font-family.body"}}]
                [{:type "text"
                  :name "Helper"
                  :text "{helper}"
                  :tokens {:fill "color.text-muted" :fontSize "font-size.xs"
                           :fontFamily "font-family.body"}
                  :vary [{:on {:State "Error"} :set {:tokens {:fill "color.danger"}}}]}])})

(def select
  {:recipe 1
   :name "select"
   :title "Select"
   :purpose "A labelled box that opens a list to pick one option from."
   :category "control"
   :keywords ["dropdown" "picker" "combobox" "menu" "options"]
   :params [{:name "label" :type "text" :default "Label"
             :description "What is being chosen"}
            {:name "value" :type "text" :default "Choose an option"
             :description "The option shown in the box"}]
   :variants [{:name "State" :values ["Closed" "Open" "Disabled"]}]
   :tree (field "Select"
                [{:on {:State "Open"}
                  :set {:tokens {:strokeColor "color.primary" :strokeWidth "border-width.md"}}}
                 {:on {:State "Disabled"}
                  :set {:tokens {:fill "color.surface-muted"}}}]
                [{:type "text"
                  :name "Value"
                  :text "{value}"
                  :layoutChild {:horizontalSizing "fill"}
                  :tokens {:fill "color.text" :fontSize "font-size.sm"
                           :fontFamily "font-family.body"}}
                 {:type "recipe"
                  :name "Chevron"
                  :placeholder "icon"
                  :use "icon"
                  :variant {:Size "Small"}}]
                [{:type "board"
                  :name "Menu"
                  :when {:variant "State" :is "Open"}
                  :layout {:type "flex" :dir "column"}
                  :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                  :tokens {:fill "color.surface"
                           :strokeColor "color.border"
                           :strokeWidth "border-width.sm"
                           :borderRadius "radius.md"
                           :paddingY "spacing.xs"}
                  :children [{:type "board"
                              :name "Option {index}"
                              :repeat 3
                              :layout {:type "flex" :dir "row" :alignItems "center"}
                              :layoutChild {:horizontalSizing "fill" :verticalSizing "auto"}
                              :tokens {:fill "color.surface"
                                       :paddingX "spacing.md"
                                       :paddingY "spacing.sm"}
                              :vary [{:at 1 :set {:tokens {:fill "color.surface-muted"}}}]
                              :children [{:type "text"
                                          :name "Option"
                                          :text "Option {index}"
                                          :tokens {:fill "color.text" :fontSize "font-size.sm"
                                                   :fontFamily "font-family.body"}}]}]}])})

(def choice-control
  {:recipe 1
   :name "choice-control"
   :title "Choice control"
   :purpose "A checkbox, a radio button or a switch with its label: one option to turn on or off."
   :category "control"
   :keywords ["checkbox" "radio" "switch" "toggle" "tick" "option"]
   :params [{:name "label" :type "text" :default "Option"
             :description "What turning it on means"}]
   :variants [{:name "Type" :values ["Checkbox" "Radio" "Switch"]}
              {:name "State" :values ["Off" "On"]}]
   :tree {:type "board"
          :name "Choice control"
          :layout {:type "flex" :dir "row" :alignItems "center"}
          :layoutChild {:horizontalSizing "auto" :verticalSizing "auto"}
          :tokens {:gap "spacing.sm"}
          :children [{:type "board"
                      :name "Box"
                      :when {:variant "Type" :is "Checkbox"}
                      :width 20
                      :height 20
                      :layout {:type "flex" :dir "row" :alignItems "center" :justifyContent "center"}
                      :layoutChild {:horizontalSizing "fix" :verticalSizing "fix"}
                      :tokens {:fill "color.surface"
                               :strokeColor "color.border"
                               :strokeWidth "border-width.sm"
                               :borderRadius "radius.sm"}
                      :vary [{:on {:State "On"}
                              :set {:tokens {:fill "color.primary" :strokeColor "color.primary"}}}]
                      :children [{:type "rect"
                                  :name "Mark"
                                  :when {:variant "State" :is "On"}
                                  :width 10
                                  :height 10
                                  :tokens {:fill "color.on-primary"}}]}
                     {:type "board"
                      :name "Ring"
                      :when {:variant "Type" :is "Radio"}
                      :width 20
                      :height 20
                      :layout {:type "flex" :dir "row" :alignItems "center" :justifyContent "center"}
                      :layoutChild {:horizontalSizing "fix" :verticalSizing "fix"}
                      :tokens {:fill "color.surface"
                               :strokeColor "color.border"
                               :strokeWidth "border-width.sm"
                               :borderRadius "radius.full"}
                      :vary [{:on {:State "On"} :set {:tokens {:strokeColor "color.primary"}}}]
                      :children [{:type "ellipse"
                                  :name "Dot"
                                  :when {:variant "State" :is "On"}
                                  :width 10
                                  :height 10
                                  :tokens {:fill "color.primary"}}]}
                     {:type "board"
                      :name "Track"
                      :when {:variant "Type" :is "Switch"}
                      :width 36
                      :height 20
                      :layout {:type "flex" :dir "row" :alignItems "center" :justifyContent "start"
                               :padding {:top 2 :right 2 :bottom 2 :left 2}}
                      :layoutChild {:horizontalSizing "fix" :verticalSizing "fix"}
                      :tokens {:fill "color.border" :borderRadius "radius.full"}
                      :vary [{:on {:State "On"}
                              :set {:tokens {:fill "color.primary"}
                                    :layout {:justifyContent "end"}}}]
                      :children [{:type "ellipse"
                                  :name "Thumb"
                                  :width 16
                                  :height 16
                                  :tokens {:fill "color.surface"}}]}
                     {:type "text"
                      :name "Label"
                      :text "{label}"
                      :tokens {:fill "color.text" :fontSize "font-size.sm"
                               :fontFamily "font-family.body"}}]}})

(def badge
  {:recipe 1
   :name "badge"
   :title "Badge"
   :purpose "A small label that marks a state, a count or a category next to something else."
   :category "control"
   :keywords ["tag" "chip" "pill" "status" "label" "count"]
   :params [{:name "label" :type "text" :default "Badge"
             :description "The word or number it shows"}]
   :variants [{:name "Tone" :values ["Neutral" "Primary" "Success" "Warning" "Danger"]}]
   :tree {:type "board"
          :name "Badge"
          :layout {:type "flex" :dir "row" :alignItems "center" :justifyContent "center"}
          :layoutChild {:horizontalSizing "auto" :verticalSizing "auto"}
          :tokens {:fill "color.surface-muted"
                   :borderRadius "radius.full"
                   :paddingX "spacing.sm"
                   :paddingY "spacing.xs"}
          :vary [{:on {:Tone "Primary"} :set {:tokens {:fill "color.primary"}}}
                 {:on {:Tone "Success"} :set {:tokens {:fill "color.success"}}}
                 {:on {:Tone "Warning"} :set {:tokens {:fill "color.warning"}}}
                 {:on {:Tone "Danger"} :set {:tokens {:fill "color.danger"}}}]
          :children [{:type "text"
                      :name "Label"
                      :text "{label}"
                      :tokens {:fill "color.on-primary"
                               :fontSize "font-size.xs"
                               :fontFamily "font-family.body"
                               :fontWeight "font-weight.medium"}
                      :vary [{:on {:Tone "Neutral"} :set {:tokens {:fill "color.text"}}}]}]}})

(def avatar
  {:recipe 1
   :name "avatar"
   :title "Avatar"
   :purpose "A round mark for a person or an account: initials now, a picture when there is one."
   :category "control"
   :keywords ["profile" "user" "picture" "initials" "account"]
   :params [{:name "initials" :type "text" :default "AB"
             :description "One or two letters"}]
   :variants [{:name "Size" :values ["Medium" "Small" "Large"]}]
   :tree {:type "board"
          :name "Avatar"
          :width 40
          :height 40
          :layout {:type "flex" :dir "row" :alignItems "center" :justifyContent "center"}
          :layoutChild {:horizontalSizing "fix" :verticalSizing "fix"}
          :tokens {:fill "color.surface-muted" :borderRadius "radius.full"}
          :vary [{:on {:Size "Small"} :set {:width 28 :height 28}}
                 {:on {:Size "Large"} :set {:width 56 :height 56}}]
          :children [{:type "text"
                      :name "Initials"
                      :text "{initials}"
                      :tokens {:fill "color.text"
                               :fontSize "font-size.sm"
                               :fontFamily "font-family.body"
                               :fontWeight "font-weight.medium"}
                      :vary [{:on {:Size "Small"} :set {:tokens {:fontSize "font-size.xs"}}}
                             {:on {:Size "Large"} :set {:tokens {:fontSize "font-size.lg"}}}]}]}})

(def icon
  {:recipe 1
   :name "icon"
   :title "Icon"
   :purpose "The place of an icon: a box of its size and tone, which set_icons swaps for a real icon."
   :category "control"
   :keywords ["glyph" "symbol" "pictogram" "placeholder"]
   :variants [{:name "Size" :values ["Small" "Medium" "Large"]}
              {:name "Tone" :values ["Muted" "Strong" "Primary"]}]
   :tree {:type "board"
          :name "Icon"
          :width 16
          :height 16
          :tokens {:fill "color.text-muted" :borderRadius "radius.sm"}
          :vary [{:on {:Size "Medium"} :set {:width 20 :height 20}}
                 {:on {:Size "Large"} :set {:width 24 :height 24}}
                 {:on {:Tone "Strong"} :set {:tokens {:fill "color.text"}}}
                 {:on {:Tone "Primary"} :set {:tokens {:fill "color.primary"}}}]}})

(def all [button text-field select choice-control badge avatar icon])
