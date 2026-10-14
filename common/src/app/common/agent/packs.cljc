;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.packs
  "The starter token packs; see `app.common.agent.pack` for the format.

  Each is three sets — what does not change with the mode, and the colors
  and shadows of the light and of the dark mode — and a theme per mode.")

(defn- shadow
  [x y blur color]
  [{:offsetX x :offsetY y :blur blur :spread 0 :color color}])

(defn- starter
  [{:keys [core light dark] :as about}]
  (-> (select-keys about [:name :title :purpose :fonts])
      (assoc :pack 1
             :sets [{:name "core" :tokens core}
                    {:name "modes/light" :tokens light}
                    {:name "modes/dark" :tokens dark}]
             :themes [{:name "Light" :group "Mode" :sets ["core" "modes/light"]}
                      {:name "Dark" :group "Mode" :sets ["core" "modes/dark"]}]
             :default "Light")))

(def ^:private scale
  "The spacing and type steps the three share unless they say otherwise."
  {"spacing.xs" 4 "spacing.sm" 8 "spacing.md" 12 "spacing.lg" 16
   "spacing.xl" 24 "spacing.2xl" 32 "spacing.3xl" 48
   "font-size.xs" 12 "font-size.sm" 14 "font-size.md" 16
   "font-size.lg" 20 "font-size.xl" 28 "font-size.2xl" 40
   "font-weight.regular" 400 "font-weight.medium" 500 "font-weight.bold" 700})

(def neutral
  (starter
   {:name "neutral"
    :title "Neutral"
    :purpose "Quiet and exact: greys, one blue and small radii, for tools and dashboards where the content is the point."
    :fonts [{:family "Inter"}]
    :core (merge scale
                 {"font-family.body" "Inter, sans-serif"
                  "font-family.heading" "Inter, sans-serif"
                  "radius.sm" 4 "radius.md" 8 "radius.lg" 12 "radius.full" 999
                  "border-width.sm" 1 "border-width.md" 2})
    :light {"color.background" "#ffffff"
            "color.surface" "#ffffff"
            "color.surface-muted" "#f4f4f5"
            "color.border" "#d4d4d8"
            "color.text" "#18181b"
            "color.text-muted" "#52525b"
            "color.primary" "#2563eb"
            "color.on-primary" "#ffffff"
            "color.danger" "#dc2626"
            "color.success" "#15803d"
            "color.warning" "#b45309"
            "color.info" "#0369a1"
            "shadow.sm" (shadow 0 1 2 "rgba(24, 24, 27, 0.08)")
            "shadow.md" (shadow 0 4 12 "rgba(24, 24, 27, 0.12)")
            "shadow.lg" (shadow 0 12 32 "rgba(24, 24, 27, 0.18)")}
    :dark {"color.background" "#09090b"
           "color.surface" "#18181b"
           "color.surface-muted" "#27272a"
           "color.border" "#3f3f46"
           "color.text" "#fafafa"
           "color.text-muted" "#a1a1aa"
           "color.primary" "#60a5fa"
           "color.on-primary" "#09090b"
           "color.danger" "#f87171"
           "color.success" "#4ade80"
           "color.warning" "#fbbf24"
           "color.info" "#38bdf8"
           "shadow.sm" (shadow 0 1 2 "rgba(0, 0, 0, 0.4)")
           "shadow.md" (shadow 0 4 12 "rgba(0, 0, 0, 0.5)")
           "shadow.lg" (shadow 0 12 32 "rgba(0, 0, 0, 0.6)")}}))

(def bold
  (starter
   {:name "bold"
    :title "Bold"
    :purpose "Loud and graphic: ink on cream, one electric color, square corners, thick borders and hard shadows, for brands that want to be remembered."
    :fonts [{:family "Space Grotesk"} {:family "Inter"}]
    :core (merge scale
                 {"font-size.lg" 22 "font-size.xl" 32 "font-size.2xl" 52
                  "font-family.body" "Inter, sans-serif"
                  "font-family.heading" "Space Grotesk, sans-serif"
                  "radius.sm" 0 "radius.md" 2 "radius.lg" 4 "radius.full" 999
                  "border-width.sm" 2 "border-width.md" 3})
    :light {"color.background" "#fffdf5"
            "color.surface" "#ffffff"
            "color.surface-muted" "#f3efe0"
            "color.border" "#111111"
            "color.text" "#111111"
            "color.text-muted" "#4a4a4a"
            "color.primary" "#4f2bff"
            "color.on-primary" "#ffffff"
            "color.danger" "#c81e1e"
            "color.success" "#0f7b3f"
            "color.warning" "#a84f00"
            "color.info" "#0b63c4"
            "shadow.sm" (shadow 2 2 0 "#111111")
            "shadow.md" (shadow 4 4 0 "#111111")
            "shadow.lg" (shadow 8 8 0 "#111111")}
    :dark {"color.background" "#0b0b0f"
           "color.surface" "#16161d"
           "color.surface-muted" "#22222c"
           "color.border" "#f5f5f5"
           "color.text" "#f5f5f5"
           "color.text-muted" "#b4b4c0"
           "color.primary" "#c6ff3d"
           "color.on-primary" "#0b0b0f"
           "color.danger" "#ff6b5e"
           "color.success" "#5ee08a"
           "color.warning" "#ffc247"
           "color.info" "#6cb8ff"
           "shadow.sm" (shadow 2 2 0 "#c6ff3d")
           "shadow.md" (shadow 4 4 0 "#c6ff3d")
           "shadow.lg" (shadow 8 8 0 "#c6ff3d")}}))

(def soft
  (starter
   {:name "soft"
    :title "Soft"
    :purpose "Warm and friendly: cream grounds, a violet, round corners, roomy spacing and wide gentle shadows, for consumer apps and anything that should feel easy."
    :fonts [{:family "Nunito"} {:family "Nunito Sans"}]
    :core (merge scale
                 {"spacing.md" 14 "spacing.lg" 20 "spacing.xl" 28 "spacing.2xl" 40 "spacing.3xl" 56
                  "font-family.body" "Nunito Sans, sans-serif"
                  "font-family.heading" "Nunito, sans-serif"
                  "radius.sm" 8 "radius.md" 14 "radius.lg" 22 "radius.full" 999
                  "border-width.sm" 1 "border-width.md" 2})
    :light {"color.background" "#fbf8f4"
            "color.surface" "#ffffff"
            "color.surface-muted" "#f3ede6"
            "color.border" "#e5dcd2"
            "color.text" "#2d2a32"
            "color.text-muted" "#625c68"
            "color.primary" "#6d4aff"
            "color.on-primary" "#ffffff"
            "color.danger" "#c8324b"
            "color.success" "#1f7a54"
            "color.warning" "#a85a00"
            "color.info" "#2a6fb8"
            "shadow.sm" (shadow 0 2 6 "rgba(109, 74, 255, 0.08)")
            "shadow.md" (shadow 0 8 24 "rgba(109, 74, 255, 0.12)")
            "shadow.lg" (shadow 0 20 48 "rgba(109, 74, 255, 0.16)")}
    :dark {"color.background" "#1c1a22"
           "color.surface" "#26232e"
           "color.surface-muted" "#322e3c"
           "color.border" "#453f52"
           "color.text" "#f4f0fa"
           "color.text-muted" "#b8b0c6"
           "color.primary" "#b3a1ff"
           "color.on-primary" "#1c1a22"
           "color.danger" "#ff8fa3"
           "color.success" "#7fd9b0"
           "color.warning" "#ffc47a"
           "color.info" "#8cc3ff"
           "shadow.sm" (shadow 0 2 6 "rgba(0, 0, 0, 0.3)")
           "shadow.md" (shadow 0 8 24 "rgba(0, 0, 0, 0.4)")
           "shadow.lg" (shadow 0 20 48 "rgba(0, 0, 0, 0.5)")}}))

(def all
  "The starter packs, in the order they are offered."
  [neutral bold soft])

(def builtin
  "Name → pack."
  (into {} (map (juxt :name identity)) all))
