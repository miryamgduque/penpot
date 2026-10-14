;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipes.profiles
  "The built-in platform profiles; see `app.common.agent.recipe` for what a
  profile is. Each says what a platform asks of the navigation recipes and
  of the screens, and which variant of a control it places.")

(def ios
  {:profile 1
   :name "ios"
   :title "iOS"
   :purpose "An iPhone app: a 393pt wide screen, a navigation bar with a title and one action, a tab bar with larger icons, rows tall enough to tap."
   :recipes {"top-bar"        {:params {:links 0}
                               :adjust [{:layer "Top bar"
                                         :set {:width 393
                                               :tokens {:paddingX "spacing.lg" :paddingY "spacing.sm"}}}
                                        {:layer "Brand"
                                         :set {:tokens {:fontSize "font-size.md"}}}
                                        {:layer "Action"
                                         :set {:variant {:Kind "Ghost" :Size "Small"}}}]}
             "bottom-tab-bar" {:params {:tabs 5}
                               :adjust [{:layer "Bottom tab bar"
                                         :set {:width 393
                                               :tokens {:paddingX "spacing.xs" :paddingY "spacing.xs"}}}]}
             "sidebar"        {:adjust [{:layer "Sidebar"
                                         :set {:width 320 :height 852
                                               :tokens {:padding "spacing.xl"}}}
                                        {:layer "Items"
                                         :set {:tokens {:gap "spacing.sm"}}}]}
             "button"         {:variant {:Size "Medium"}}
             ;; a phone screen keeps clear of the status bar and of the home
             ;; indicator
             "mobile-list-screen"     {:adjust [{:layer "Mobile list screen"
                                                 :set {:width 393 :height 852
                                                       :tokens {:paddingTop "spacing.3xl" :paddingBottom "spacing.xl"}}}]}
             "mobile-settings-screen" {:adjust [{:layer "Mobile settings screen"
                                                 :set {:width 393 :height 852
                                                       :tokens {:paddingTop "spacing.3xl" :paddingBottom "spacing.xl"}}}]}
             "mobile-chat-screen"     {:adjust [{:layer "Mobile chat screen"
                                                 :set {:width 393 :height 852
                                                       :tokens {:paddingTop "spacing.3xl" :paddingBottom "spacing.xl"}}}]}
             "mobile-header"          {:adjust [{:layer "Mobile header" :set {:width 393}}]}
             "chat-thread"            {:adjust [{:layer "Chat thread" :set {:width 393 :height 852}}]}}})

(def android
  {:profile 1
   :name "android"
   :title "Android"
   :purpose "An Android phone app: a 360dp wide screen, a top app bar, a bottom bar of three to five places, room for the system bars."
   :recipes {"top-bar"        {:params {:links 0}
                               :adjust [{:layer "Top bar"
                                         :set {:width 360
                                               :tokens {:paddingX "spacing.lg" :paddingY "spacing.md"}}}
                                        {:layer "Action"
                                         :set {:variant {:Kind "Ghost" :Size "Small"}}}]}
             "bottom-tab-bar" {:params {:tabs 3}
                               :adjust [{:layer "Bottom tab bar"
                                         :set {:width 360
                                               :tokens {:paddingX "spacing.sm" :paddingY "spacing.md"}}}]}
             "sidebar"        {:adjust [{:layer "Sidebar" :set {:width 304 :height 800}}]}
             "mobile-header"  {:adjust [{:layer "Mobile header"
                                         :set {:width 360
                                               :layout {:justifyContent "start"}
                                               :tokens {:paddingY "spacing.md" :gap "spacing.lg"}}}]}
             "chat-thread"    {:adjust [{:layer "Chat thread" :set {:width 360 :height 800}}]}
             "mobile-list-screen"     {:params {:tabs 3}
                                       :adjust [{:layer "Mobile list screen"
                                                 :set {:width 360 :height 800
                                                       :tokens {:paddingTop "spacing.xl" :paddingBottom "spacing.lg"}}}]}
             "mobile-settings-screen" {:adjust [{:layer "Mobile settings screen"
                                                 :set {:width 360 :height 800
                                                       :tokens {:paddingTop "spacing.xl" :paddingBottom "spacing.lg"}}}]}
             "mobile-chat-screen"     {:adjust [{:layer "Mobile chat screen"
                                                 :set {:width 360 :height 800
                                                       :tokens {:paddingTop "spacing.xl" :paddingBottom "spacing.lg"}}}]}
             "button"         {:variant {:Size "Medium"}}}})

(def web
  {:profile 1
   :name "web"
   :title "Web"
   :purpose "A desktop web page: a 1440px wide screen, a roomy top bar with its links, a narrow sidebar with dense rows, smaller buttons."
   :recipes {"top-bar"        {:adjust [{:layer "Top bar"
                                         :set {:width 1440
                                               :tokens {:paddingX "spacing.2xl" :paddingY "spacing.lg"}}}
                                        {:layer "Links"
                                         :set {:tokens {:gap "spacing.2xl"}}}]}
             "bottom-tab-bar" {:params {:tabs 4}
                               :adjust [{:layer "Bottom tab bar"
                                         :set {:width 480}}]}
             "sidebar"        {:params {:items 6}
                               :adjust [{:layer "Sidebar"
                                         :set {:width 256 :height 900}}]}
             "button"         {:variant {:Size "Small"}}
             ;; a page on the web is as wide as a laptop's window, an app's
             ;; screen as tall
             "landing-page"     {:adjust [{:layer "Landing page" :set {:width 1440}}]}
             "pricing-page"     {:adjust [{:layer "Pricing page" :set {:width 1440}}]}
             "article-page"     {:adjust [{:layer "Article page" :set {:width 1440}}]}
             "dashboard-screen" {:adjust [{:layer "Dashboard screen" :set {:width 1440 :height 900}}]}
             "list-screen"      {:adjust [{:layer "List screen" :set {:width 1440 :height 900}}]}
             "settings-screen"  {:adjust [{:layer "Settings screen" :set {:width 1440 :height 900}}]}}})

(def all [ios android web])

(def builtin
  "Name → profile."
  (into {} (map (juxt :name identity)) all))
