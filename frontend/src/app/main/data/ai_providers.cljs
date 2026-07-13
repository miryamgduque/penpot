;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.ai-providers
  "Account-level AI provider connections (see app.rpc.commands.ai-providers).
  Several providers can be connected in parallel; state holds a map of
  provider-id → status ({:provider :connected :key-hint :enabled-models}).
  Keys are write-only from the client."
  (:require
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

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
