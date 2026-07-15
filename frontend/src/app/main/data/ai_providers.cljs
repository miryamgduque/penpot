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
;; models. Verified against provider documentation on 2026-07-15.
;;
;; `:vision` is the load-bearing field and the easy one to get wrong, because
;; the answer does not follow from the model's name or reputation:
;;
;;   Anthropic, OpenAI — every current model reads images; there is no separate
;;     vision SKU to pick.
;;   Zhipu, Moonshot   — vision ships as SEPARATE model ids (`glm-4.5v`,
;;     `moonshot-v1-128k-vision-preview`). Every base model below is text-only,
;;     however capable the family sounds.
;;
;; Getting one wrong is not a local mistake: the model is chosen per turn, so a
;; false `:vision true` is a provider error in the user's face mid-conversation.
;; `frontend-tests.data.ai-providers-test` pins each of these down.

(def ai-provider-models
  {"anthropic"
   ;; opus-4.8 and sonnet-5 are 1M-context models — the 200K these two carried
   ;; until 2026-07-15 was a placeholder, not a measurement.
   [{:id "claude-opus-4-8"            :label "Claude Opus 4.8"  :context 1000000 :vision true}
    {:id "claude-sonnet-5"            :label "Claude Sonnet 5"  :context 1000000 :vision true}
    {:id "claude-haiku-4-5-20251001"  :label "Claude Haiku 4.5" :context 200000  :vision true}]
   "openai"
   [{:id "gpt-5"      :label "GPT-5"      :context 400000  :vision true}
    {:id "gpt-5-mini" :label "GPT-5 mini" :context 400000  :vision true}
    {:id "gpt-4.1"    :label "GPT-4.1"    :context 1000000 :vision true}]
   "zhipu"
   ;; the vision line is GLM-4.5V / GLM-4.6V — not offered here
   [{:id "glm-4.6"     :label "GLM-4.6"     :context 200000 :vision false}
    {:id "glm-4.5"     :label "GLM-4.5"     :context 128000 :vision false}
    {:id "glm-4.5-air" :label "GLM-4.5 Air" :context 128000 :vision false}]
   "moonshot"
   ;; the vision line is moonshot-v1-*-vision-preview — not offered here
   ;; TODO: kimi-k2-0905-preview was discontinued 2026-05-25; it is kept listed
   ;; only so anyone who already enabled it still sees a labelled row. Needs a
   ;; product call on the replacement (kimi-k2.6), not a silent swap.
   [{:id "kimi-k2-0905-preview" :label "Kimi K2"          :context 256000 :vision false}
    {:id "moonshot-v1-128k"     :label "Moonshot v1 128K" :context 128000 :vision false}
    {:id "moonshot-v1-32k"      :label "Moonshot v1 32K"  :context 32000  :vision false}]})

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
