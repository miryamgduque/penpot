;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.pack-make
  "A token pack made from a few choices: a brand color, the fonts, and how
  sharp, dense and lifted it should be. Everything else — the greys, the
  dark theme, the status colors, the scales — follows from those, and every
  pair of text and ground is moved until it can be read.

  Whoever makes a pack decides the few things that give a system its look
  and leaves the forty values that have to agree with each other to this."
  (:require
   [app.common.agent.pack :as cak]
   [app.common.data.macros :as dm]
   [cuerdas.core :as str]))

;; --- Color, as hue, saturation and lightness

(defn- hex->rgb
  [hex]
  (let [hex (str/lower (str/trim hex))
        hex (if-let [[_ r g b] (re-matches #"#([0-9a-f])([0-9a-f])([0-9a-f])" hex)]
              (dm/str "#" r r g g b b)
              hex)]
    (when-let [[_ r g b] (re-matches #"#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})" hex)]
      (mapv #(/ #?(:clj (Integer/parseInt % 16) :cljs (js/parseInt % 16)) 255.0) [r g b]))))

(defn- rgb->hex
  [rgb]
  (let [part (fn [c]
               (let [n (int (+ 0.5 (* 255 (max 0.0 (min 1.0 c)))))
                     h #?(:clj (Integer/toHexString n) :cljs (.toString n 16))]
                 (if (= 1 (count h)) (dm/str "0" h) h)))]
    (dm/str "#" (str/join (map part rgb)))))

(defn- rgb->hsl
  [[r g b]]
  (let [hi (max r g b)
        lo (min r g b)
        l  (/ (+ hi lo) 2.0)
        d  (- hi lo)]
    (if (zero? d)
      [0.0 0.0 l]
      (let [s (/ d (- 1.0 (abs (- (* 2.0 l) 1.0))))
            h (cond
                (= hi r) (mod (/ (- g b) d) 6.0)
                (= hi g) (+ 2.0 (/ (- b r) d))
                :else    (+ 4.0 (/ (- r g) d)))]
        [(* 60.0 h) s l]))))

(defn- hsl->rgb
  [[h s l]]
  (let [c (* s (- 1.0 (abs (- (* 2.0 l) 1.0))))
        x (* c (- 1.0 (abs (- (mod (/ h 60.0) 2.0) 1.0))))
        m (- l (/ c 2.0))
        [r g b] (cond
                  (< h 60)  [c x 0.0]
                  (< h 120) [x c 0.0]
                  (< h 180) [0.0 c x]
                  (< h 240) [0.0 x c]
                  (< h 300) [x 0.0 c]
                  :else     [c 0.0 x])]
    [(+ r m) (+ g m) (+ b m)]))

(defn- hsl
  "A hex color of hue 0–360 and saturation and lightness 0–100."
  [h s l]
  (rgb->hex (hsl->rgb [(mod h 360.0) (/ s 100.0) (/ l 100.0)])))

(defn- readable
  "`color` moved darker or lighter (`toward`, :dark or :light) by the least
  that lets it be told apart from every one of `grounds`."
  [color toward grounds]
  (let [[h s l] (rgb->hsl (hex->rgb color))
        ok?     (fn [candidate]
                  (every? #(<= cak/min-contrast (or (cak/contrast candidate %) 0)) grounds))
        step    (if (= :dark toward) -0.01 0.01)]
    (loop [l l
           n 0]
      (let [candidate (rgb->hex (hsl->rgb [h s (max 0.0 (min 1.0 l))]))]
        (if (or (ok? candidate) (< 100 n))
          candidate
          (recur (+ l step) (inc n)))))))

;; --- The choices

(def corner-steps
  {"sharp" {"radius.sm" 0 "radius.md" 2 "radius.lg" 4}
   "soft"  {"radius.sm" 4 "radius.md" 8 "radius.lg" 12}
   "round" {"radius.sm" 8 "radius.md" 14 "radius.lg" 22}})

(def densities
  {"compact" [2 6 8 12 16 24 32]
   "regular" [4 8 12 16 24 32 48]
   "roomy"   [4 8 14 20 28 40 56]})

(def type-scales
  {"regular" [12 14 16 20 28 40]
   "large"   [12 14 16 22 32 52]})

(def shadows #{"none" "soft" "hard"})

(def neutrals
  "Where the greys lean: a hue and how much of it."
  {"plain" [0 0]
   "cool"  [220 10]
   "warm"  [35 14]
   "brand" nil})

(def ^:private statuses
  {"color.danger" 4 "color.success" 148 "color.warning" 32 "color.info" 205})

(defn- shadow
  [x y blur color]
  [{:offsetX x :offsetY y :blur blur :spread 0 :color color}])

(defn- shadow-tokens
  [kind dark? ink]
  (case kind
    "none" (zipmap ["shadow.sm" "shadow.md" "shadow.lg"] (repeat (shadow 0 0 0 "rgba(0, 0, 0, 0)")))
    "hard" {"shadow.sm" (shadow 2 2 0 ink) "shadow.md" (shadow 4 4 0 ink) "shadow.lg" (shadow 8 8 0 ink)}
    (let [alpha (if dark? [0.4 0.5 0.6] [0.08 0.12 0.18])
          tint  (fn [a] (dm/str "rgba(0, 0, 0, " a ")"))]
      {"shadow.sm" (shadow 0 1 2 (tint (nth alpha 0)))
       "shadow.md" (shadow 0 4 12 (tint (nth alpha 1)))
       "shadow.lg" (shadow 0 12 32 (tint (nth alpha 2)))})))

(defn- mode
  "The colors and shadows of one mode."
  [{:keys [primary hue tint shadow]} dark?]
  (let [grey       (fn [l] (hsl hue tint l))
        background (if dark? (grey 6) (grey (if (pos? tint) 98 100)))
        surface    (if dark? (grey 10) "#ffffff")
        muted      (if dark? (grey 15) (grey 95))
        grounds    [background surface]
        toward     (if dark? :light :dark)
        text       (readable (if dark? (grey 96) (grey 10)) toward [background surface muted])
        text-muted (readable (if dark? (grey 68) (grey 40)) toward grounds)
        on-primary (if dark? background "#ffffff")
        [ph ps _]  (rgb->hsl (hex->rgb primary))
        ;; the brand color, as near to itself as it can be and still be read
        ;; both as text on the grounds and under its own text
        brand      (readable (if dark?
                               (rgb->hex (hsl->rgb [ph ps (max 0.62 (nth (rgb->hsl (hex->rgb primary)) 2))]))
                               primary)
                             toward (conj grounds on-primary))
        status     (fn [h] (readable (hsl h (if dark? 85 72) (if dark? 68 42)) toward [background on-primary]))]
    (merge
     {"color.background" background
      "color.surface" surface
      "color.surface-muted" muted
      "color.border" (if (= "hard" shadow) text (if dark? (grey 26) (grey 84)))
      "color.text" text
      "color.text-muted" text-muted
      "color.primary" brand
      "color.on-primary" on-primary}
     (into {} (map (fn [[role h]] [role (status h)])) statuses)
     (shadow-tokens shadow dark? (if dark? brand text)))))

(def ^:private name-re #"^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

(defn- one-of
  [what value allowed]
  (when-not (contains? (set allowed) value)
    (dm/str what " is one of: " (str/join ", " (sort allowed)))))

(defn choices-problem
  "Why these choices do not make a pack, or nil."
  [{:keys [name title purpose primary headingFont bodyFont corners density typeScale shadow neutral]}]
  (cond
    (not (and (string? name) (re-matches name-re name)))
    "needs a `name` in kebab-case, like \"relay\""

    (not (and (string? title) (not (str/blank? title))))
    "needs a `title`"

    (not (and (string? purpose) (not (str/blank? purpose))))
    "needs a `purpose`: what this system should feel like and for whom, in a sentence"

    (not (and (string? primary) (some? (hex->rgb primary))))
    "needs `primary`: the brand color as \"#rrggbb\""

    (not (and (string? bodyFont) (not (str/blank? bodyFont))))
    "needs `bodyFont`: a font family, like \"Inter\""

    (not (or (nil? headingFont) (and (string? headingFont) (not (str/blank? headingFont)))))
    "headingFont is a font family, like \"Space Grotesk\""

    :else
    (or (one-of "corners" (or corners "soft") (keys corner-steps))
        (one-of "density" (or density "regular") (keys densities))
        (one-of "typeScale" (or typeScale "regular") (keys type-scales))
        (one-of "shadow" (or shadow "soft") shadows)
        (when-not (or (nil? neutral) (contains? neutrals neutral) (some? (hex->rgb neutral)))
          "neutral is plain, cool, warm, brand, or a \"#rrggbb\" the greys lean to"))))

(defn make
  "The pack these choices make: `core`, `modes/light` and `modes/dark`, and
  a Light and a Dark theme. Call `choices-problem` first."
  [{:keys [name title purpose primary headingFont bodyFont corners density typeScale shadow neutral]}]
  (let [corners   (or corners "soft")
        shadow    (or shadow "soft")
        heading   (or headingFont bodyFont)
        [ph ps _] (rgb->hsl (hex->rgb primary))
        [hue tint] (cond
                     (nil? neutral)            [ph (min 8.0 (* 100 ps))]
                     (= "brand" neutral)       [ph (min 14.0 (* 100 ps))]
                     (contains? neutrals neutral) (get neutrals neutral)
                     :else                     (let [[h s _] (rgb->hsl (hex->rgb neutral))]
                                                 [h (min 16.0 (* 100 s))]))
        about     {:primary primary :hue hue :tint tint :shadow shadow}
        family    (fn [font] (dm/str font ", sans-serif"))]
    {:pack 1
     :name name
     :title title
     :purpose purpose
     :fonts (into [] (comp (distinct) (map (fn [font] {:family font}))) [heading bodyFont])
     :sets [{:name "core"
             :tokens (merge
                      (zipmap ["spacing.xs" "spacing.sm" "spacing.md" "spacing.lg" "spacing.xl" "spacing.2xl" "spacing.3xl"]
                              (get densities (or density "regular")))
                      (zipmap ["font-size.xs" "font-size.sm" "font-size.md" "font-size.lg" "font-size.xl" "font-size.2xl"]
                              (get type-scales (or typeScale "regular")))
                      (get corner-steps corners)
                      {"radius.full" 999
                       "border-width.sm" (if (= "hard" shadow) 2 1)
                       "border-width.md" (if (= "hard" shadow) 3 2)
                       "font-weight.regular" 400
                       "font-weight.medium" 500
                       "font-weight.bold" 700
                       "font-family.body" (family bodyFont)
                       "font-family.heading" (family heading)})}
            {:name "modes/light" :tokens (mode about false)}
            {:name "modes/dark" :tokens (mode about true)}]
     :themes [{:name "Light" :group "Mode" :sets ["core" "modes/light"]}
              {:name "Dark" :group "Mode" :sets ["core" "modes/dark"]}]
     :default "Light"}))
