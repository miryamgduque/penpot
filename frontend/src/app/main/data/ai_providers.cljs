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
;; What each model can do is not kept here: it is the provider layer's
;; catalog, read from the agent service through the backend
;; (`get-ai-model-catalog`), one capability record per model — see
;; `agent/src/catalog.js` for the schema. A capability is true, false or nil,
;; and nil means UNKNOWN: the source does not say.
;;
;; The catalog lives in the app state (`:ai-model-catalog`, for what renders
;; it) and in `catalog*`, for the questions asked outside a component — how
;; to encode a turn is decided per message, far from any view.

(defonce ^:private catalog* (atom {}))

(defn set-catalog!
  "Takes `providers`, provider id → capability records, as the catalog."
  [providers]
  (reset! catalog* (into {} (map (fn [[provider records]] [(name provider) (vec records)])) providers)))

(defn models
  "The capability records of a provider's models, or of all when the
  catalog has not arrived, none."
  [provider]
  (get @catalog* provider []))

(defn model
  "The capability record of one model, or nil when the catalog does not
  have it."
  [provider model-id]
  (some #(when (= model-id (:id %)) %) (models provider)))

(defn vision?
  "Whether `model` is KNOWN to accept image input.

  A model outside the catalog, or one whose vision is unknown, answers
  `false`. That is the deliberate direction to be wrong in: guessing `true`
  costs a provider error mid-conversation on content the user already sent,
  while guessing `false` only greys out the attach button on a model that
  might have coped."
  [provider model-id]
  (true? (:vision (model provider model-id))))

(defn selectable?
  "Whether `model` may run the agent: everything but a model KNOWN not to
  call tools. The agent is its tools; a model without them can only talk.
  A model whose tool use nobody stated may be tried."
  [provider model-id]
  (not (false? (:tools (model provider model-id)))))

(defn- ai-model-catalog-fetched
  [{:keys [providers]}]
  (ptk/reify ::ai-model-catalog-fetched
    ptk/UpdateEvent
    (update [_ state]
      (set-catalog! providers)
      (assoc state :ai-model-catalog @catalog*))))

(defn fetch-ai-model-catalog
  []
  (ptk/reify ::fetch-ai-model-catalog
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-ai-model-catalog {})
           (rx/map ai-model-catalog-fetched)
           ;; with no catalog every model is of unknown ability: the agent
           ;; still runs, and sends no images
           (rx/catch (fn [_] (rx/empty)))))))

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
      (rx/merge
       (->> (rp/cmd! :get-ai-providers {})
            (rx/map ai-providers-fetched))
       ;; whoever needs the providers needs to know what their models can do
       (rx/of (fetch-ai-model-catalog))))))

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

;; --- External MCP servers
;;
;; The MCP servers this profile connected to the agent (see
;; app.rpc.commands.ai-providers). State holds them as a vector under
;; `:agent-mcp-servers`; a server's token is write-only from the client.

(defn- agent-mcp-servers-fetched
  [servers]
  (ptk/reify ::agent-mcp-servers-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :agent-mcp-servers (vec servers)))))

(defn fetch-agent-mcp-servers
  []
  (ptk/reify ::fetch-agent-mcp-servers
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-agent-mcp-servers {})
           (rx/map agent-mcp-servers-fetched)))))

(defn set-agent-mcp-server
  "Connects a server or changes one. on-success/on-error via params metadata."
  [params]
  (ptk/reify ::set-agent-mcp-server
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}}
            (meta params)]
        (->> (rp/cmd! :set-agent-mcp-server params)
             (rx/tap on-success)
             (rx/map (fn [_] (fetch-agent-mcp-servers)))
             (rx/catch on-error))))))

(defn delete-agent-mcp-server
  [params]
  (ptk/reify ::delete-agent-mcp-server
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :delete-agent-mcp-server params)
           (rx/map (fn [_] (fetch-agent-mcp-servers)))))))

(defn- agent-mcp-server-tools-fetched
  "What the server `name` answered when asked for its tools: `{:tools [...]}`,
  `{:error hint}`, or `{:loading true}` while it is being asked."
  [name answer]
  (ptk/reify ::agent-mcp-server-tools-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:agent-mcp-server-tools name] answer))))

(defn fetch-agent-mcp-server-tools
  "Asks the connected server `name` which tools it offers."
  [name]
  (ptk/reify ::fetch-agent-mcp-server-tools
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/concat
       (rx/of (agent-mcp-server-tools-fetched name {:loading true}))
       (->> (rp/cmd! :get-agent-mcp-server-tools {:name name})
            (rx/map (fn [{:keys [tools]}]
                      (agent-mcp-server-tools-fetched name {:tools (vec tools)})))
            (rx/catch (fn [cause]
                        (rx/of (agent-mcp-server-tools-fetched
                                name
                                {:error (or (:hint (ex-data cause)) "")})))))))))

(defn hide-agent-mcp-server-tools
  [name]
  (ptk/reify ::hide-agent-mcp-server-tools
    ptk/UpdateEvent
    (update [_ state]
      (update state :agent-mcp-server-tools dissoc name))))

(defn set-agent-mcp-server-tool
  "Switches one tool of the server `name` on or off for the agent."
  [{:keys [name url]} tool enabled?]
  (ptk/reify ::set-agent-mcp-server-tool
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:agent-mcp-server-tools name :tools]
                 (fn [tools]
                   (mapv #(cond-> % (= tool (:name %)) (assoc :is-enabled enabled?)) tools))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [off (into []
                      (comp (remove :is-enabled) (map :name))
                      (get-in state [:agent-mcp-server-tools name :tools]))]
        (rx/of (set-agent-mcp-server {:name name :url url :disabled-tools off}))))))
