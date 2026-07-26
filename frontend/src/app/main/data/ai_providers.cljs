;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.ai-providers
  "Account-level AI provider connections (see app.rpc.commands.ai-providers).
  Several providers can be connected in parallel; state holds a map of
  provider-id → status ({:provider :connected :key-hint :enabled-models}).
  Keys are write-only from the client.

  Also home to the curated model catalog, which lives here rather than in the
  settings UI because the agent needs it too: whether the selected model can
  read an image decides how a turn is encoded, not just how a button renders."
  (:require
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; --- Model catalog
;;
;; Hand-maintained: there is no cross-provider capability API, and the one
;; provider that does expose one (Anthropic's /v1/models) only covers its own
;; models. Every id, context size, and `:vision` value below was verified
;; against provider documentation on 2026-07-15; the Moonshot rows were
;; re-verified on 2026-07-21 for the K3 launch. Nothing here is inferred.
;; Moonshot's docs moved host in that window (platform.moonshot.ai →
;; platform.kimi.ai); the API base URI in the backend is unchanged.
;;
;; `:vision` is the load-bearing field and the easy one to get wrong, because
;; the answer does not follow from the model's name or reputation — and the
;; rule differs per provider, so there is no single instinct that works:
;;
;;   Anthropic, OpenAI — every current model reads images. No vision SKU to
;;     hunt for; looking for one wastes time.
;;   Moonshot          — the CURRENT models (Kimi K2.5+ and K3) are natively
;;     multimodal, and K3/K2.6/K2.7-code read video too. The
;;     base/`-vision-preview` split only ever applied to the sunsetting
;;     moonshot-v1 line.
;;   Zhipu             — vision still ships as SEPARATE ids. `glm-5.2` is
;;     text-only and there is no `glm-5.2v`; `glm-5v-turbo` is the vision model.
;;
;; Getting one wrong is not a local mistake: the model is chosen per turn, so a
;; false `:vision true` is a provider error in the user's face mid-conversation,
;; and a false `:vision false` silently hides the attach button.
;; `frontend-tests.data.ai-providers-test` pins each of these down.
;;
;; This list will rot. Providers ship roughly quarterly and retire on ~6-month
;; notice; three ids here were dead or dying when this pass ran. Re-verify
;; before trusting it, and prefer deleting a row over leaving a stale one — the
;; settings UI keeps enabled-but-uncatalogued models visible, so nobody is
;; stranded by a removal.

(def ai-provider-models
  {"anthropic"
   ;; every current Claude model reads images (per the models overview)
   [{:id "claude-fable-5"             :label "Claude Fable 5"   :context 1000000 :vision true}
    {:id "claude-opus-4-8"            :label "Claude Opus 4.8"  :context 1000000 :vision true}
    {:id "claude-sonnet-5"            :label "Claude Sonnet 5"  :context 1000000 :vision true}
    {:id "claude-haiku-4-5-20251001"  :label "Claude Haiku 4.5" :context 200000  :vision true}]
   "openai"
   ;; the 5.6 tiers share one generation, context, and capability set — they
   ;; differ only on the cost/speed ladder, so switching between them cannot
   ;; surprise the user with a capability cliff. `gpt-5.6` is an ALIAS that
   ;; routes to Sol; the explicit id is pinned, the alias is not.
   [{:id "gpt-5.6-sol"   :label "GPT-5.6 Sol"   :context 1050000 :vision true}
    {:id "gpt-5.6-terra" :label "GPT-5.6 Terra" :context 1050000 :vision true}
    {:id "gpt-5.6-luna"  :label "GPT-5.6 Luna"  :context 1050000 :vision true}
    ;; a generation behind, but cheaper than Luna's "cheap" tier
    ;; ($0.75/$4.50 vs $1/$6) at the cost of context
    {:id "gpt-5.4-mini"  :label "GPT-5.4 mini"  :context 400000  :vision true}]
   "zhipu"
   ;; ⚠ `glm-5-turbo` (text) and `glm-5v-turbo` (vision) differ by one
   ;; character. The `v` is the whole difference; do not "fix" a typo here.
   [{:id "glm-5.2"      :label "GLM-5.2"      :context 1000000 :vision false}
    {:id "glm-4.7"      :label "GLM-4.7"      :context 200000  :vision false}
    {:id "glm-5v-turbo" :label "GLM-5V Turbo" :context 200000  :vision true}]
   "moonshot"
   ;; K3 is the flagship and the only Kimi with a 1M window — the rest of the
   ;; line is 256K. There is still no plain `kimi-k2.7`: K2.7 shipped only as
   ;; coding variants, so K2.6 stays the cheap general-purpose row.
   ;; Note the ids use a DOT (`kimi-k2.6`, not `kimi-k2-6`).
   ;; ⚠ `kimi-k2.5` and the whole `moonshot-v1` line sunset 2026-08-31 and are
   ;; already closed to new accounts, so they are deliberately not offered.
   [{:id "kimi-k3"                   :label "Kimi K3"             :context 1000000 :vision true}
    {:id "kimi-k2.6"                 :label "Kimi K2.6"           :context 262144  :vision true}
    {:id "kimi-k2.7-code"            :label "Kimi K2.7 Code"      :context 262144  :vision true}
    ;; same model as k2.7-code on a faster decode path (~180 tok/s)
    {:id "kimi-k2.7-code-highspeed"  :label "Kimi K2.7 Code Fast" :context 262144  :vision true}]})

(defn vision?
  "Whether `model` accepts image input.

  A model outside the catalog answers `false`. That is the deliberate direction
  to be wrong in: guessing `true` costs a provider error mid-conversation on
  content the user already sent, while guessing `false` only greys out the
  attach button on a model that might have coped."
  [provider model]
  (boolean (some (fn [entry] (when (= model (:id entry)) (:vision entry)))
                 (get ai-provider-models provider))))

(defn- ai-providers-fetched
  [statuses]
  (ptk/reify ::ai-providers-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :ai-providers
             (into {} (map (juxt :provider identity)) statuses)))))

(defn fetch-ai-providers
  []
  (ptk/reify ::fetch-ai-providers
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-ai-providers {})
           (rx/map ai-providers-fetched)))))

(defn set-ai-provider-key
  "Stores (or rotates) a provider API key WITHOUT validating it against
  the provider — an invalid key or an account without funds only surfaces
  when the user sends a message. on-success/on-error via params metadata."
  [params]
  (ptk/reify ::set-ai-provider-key
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}}
            (meta params)]
        (->> (rp/cmd! :set-ai-provider-key params)
             (rx/tap on-success)
             (rx/map (fn [_] (fetch-ai-providers)))
             (rx/catch on-error))))))

(defn set-ai-provider-models
  "Persists which of a provider's models feed the chat pool."
  [params]
  (ptk/reify ::set-ai-provider-models
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :set-ai-provider-models params)
           (rx/map (fn [_] (fetch-ai-providers)))
           (rx/catch (fn [_] (rx/of (fetch-ai-providers))))))))

(defn disconnect-ai-provider
  [params]
  (ptk/reify ::disconnect-ai-provider
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :disconnect-ai-provider params)
           (rx/map (fn [_] (fetch-ai-providers)))))))
