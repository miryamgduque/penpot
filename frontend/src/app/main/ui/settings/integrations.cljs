;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.settings.integrations
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.broadcast :as mbc]
   [app.main.data.agent-access :as agent-access]
   [app.main.data.agent-switch :as agent-switch]
   [app.main.data.ai-providers :as dai]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu*]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.checkbox :refer [checkbox*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.controls.switch :refer [switch*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.notifications.actionable :refer [actionable*]]
   [app.main.ui.ds.notifications.shared.notification-pill :refer [notification-pill*]]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.forms :as fc]
   [app.util.clipboard :as clipboard]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def notification-timeout 7000)

(def ^:private schema:form-access-token
  [:map
   [:name [::sm/text {:max 250}]]
   [:expiration-date [::sm/text {:max 250}]]])

(def ^:private schema:form-mcp-token
  [:map
   [:expiration-date [::sm/text {:max 250}]]])

(def form-initial-data-access-token
  {:name ""
   :expiration-date "never"})

(def form-initial-data-mcp-token
  {:expiration-date "never"})

(mf/defc input-copy*
  {::mf/private true}
  [{:keys [value on-copy-to-clipboard]}]
  [:div {:class (stl/css :input-copy)}
   [:> input* {:type "text"
               :default-value value
               :read-only true}]
   [:div {:class (stl/css :input-copy-button-wrapper)}
    [:> icon-button* {:variant "secondary"
                      :class (stl/css :input-copy-button)
                      :aria-label (tr "integrations.copy-to-clipboard")
                      :on-click on-copy-to-clipboard
                      :icon i/clipboard}]]])

(mf/defc token-created*
  {::mf/private true}
  [{:keys [title is-mcp]}]
  (let [token-created (mf/deref refs/access-token-created)

        on-copy-to-clipboard
        (mf/use-fn
         (mf/deps token-created)
         (fn [event]
           (dom/prevent-default event)
           (-> (clipboard/to-clipboard (:token token-created))
               (.then (fn [_]
                        (st/emit! (ntf/show {:level :info
                                             :type :toast
                                             :content (if is-mcp
                                                        (tr "integrations.notification.success.mcp-key-copied")
                                                        (tr "integrations.notification.success.token-copied"))
                                             :timeout notification-timeout}))))
               (.catch (fn [_]
                         (st/emit! (ntf/show {:level :error
                                              :type :toast
                                              :content (tr "errors.clipboard-api-unavailable")
                                              :timeout notification-timeout})))))))]

    [:div {:class (stl/css :modal-form)}
     [:> text* {:as "h2"
                :typography t/headline-large
                :class (stl/css :color-primary)}
      title]

     (when-not is-mcp
       [:> notification-pill* {:level :info
                               :type :context}
        [:> text* {:as "div"
                   :typography t/body-small
                   :class (stl/css :color-primary)}
         (tr "integrations.token.info.non-recuperable")]])

     [:div {:class (stl/css :modal-content)}
      [:> input-copy* {:value (:token token-created "")
                       :on-copy-to-clipboard on-copy-to-clipboard}]

      [:> text* {:as "div"
                 :typography t/body-small
                 :class (stl/css :color-secondary)}
       (if (:expires-at token-created)
         (if is-mcp
           (tr "integrations.mcp-key.will-expire" (ct/format-inst (:expires-at token-created) "PPP"))
           (tr "integrations.token.will-expire" (ct/format-inst (:expires-at token-created) "PPP")))
         (if is-mcp
           (tr "integrations.mcp-key.will-not-expire")
           (tr "integrations.token.will-not-expire")))]]

     [:div {:class (stl/css :modal-footer)}
      [:> button* {:variant "secondary"
                   :on-click modal/hide!}
       (tr "labels.close")]]]))

(mf/defc create-token*
  {::mf/private true}
  [{:keys [title info is-mcp on-created]}]
  (let [form (fm/use-form
              :initial (if is-mcp
                         form-initial-data-mcp-token
                         form-initial-data-access-token)
              :schema (if is-mcp
                        schema:form-mcp-token
                        schema:form-access-token))

        on-error
        (mf/use-fn
         #(st/emit! (ntf/error (tr "errors.generic"))
                    (modal/hide)))

        on-success
        (mf/use-fn
         (mf/deps on-created)
         (fn []
           (when (fn? on-created)
             (on-created))))

        on-submit
        (mf/use-fn
         (mf/deps is-mcp)
         (fn [form]
           (let [cdata      (:clean-data @form)
                 mdata      {:on-success (partial on-success form)
                             :on-error   (partial on-error form)}
                 expiration (:expiration-date cdata)
                 params     (cond-> {:name  (:name cdata)
                                     :perms (:perms cdata)}
                              (not= "never" expiration)
                              (assoc :expiration expiration)

                              (true? is-mcp)
                              (assoc :type "mcp" :name "MCP key"))]

             (st/emit! (du/create-access-token (with-meta params mdata))))))]

    [:> fc/form* {:form form
                  :class (stl/css :modal-form)
                  :on-submit on-submit}

     [:> text* {:as "h2"
                :typography t/headline-large
                :class (stl/css :color-primary)}
      title]

     (when (some? info)
       [:> notification-pill* {:level :info
                               :type :context}
        [:> text* {:as "div"
                   :typography t/body-small
                   :class (stl/css :color-primary)}
         info]])

     (if is-mcp
       [:div {:class (stl/css :modal-content)}
        [:> text* {:as "div"
                   :typography t/body-medium
                   :class (stl/css :color-secondary)}
         (tr "integrations.info.mcp-server")]]

       [:div {:class (stl/css :modal-content)}
        [:> fc/form-input* {:type "text"
                            :auto-focus? true
                            :form form
                            :name :name
                            :label (tr "integrations.name.label")
                            :placeholder (tr "integrations.name.placeholder")}]])

     [:div {:class (stl/css :modal-content)}
      [:> text* {:as "label"
                 :typography t/body-small
                 :for :expiration-date
                 :class (stl/css :color-primary)}
       (tr "integrations.expiration-date.label")]
      [:> fc/form-select* {:options [{:label (tr "integrations.expiration-never")    :value "never" :id "never"}
                                     {:label (tr "integrations.expiration-30-days")  :value "720h"  :id "720h"}
                                     {:label (tr "integrations.expiration-60-days")  :value "1440h" :id "1440h"}
                                     {:label (tr "integrations.expiration-90-days")  :value "2160h" :id "2160h"}
                                     {:label (tr "integrations.expiration-180-days") :value "4320h" :id "4320h"}]
                           :default-selected "never"
                           :name :expiration-date}]]

     [:div {:class (stl/css :modal-footer)}
      [:> button* {:variant "secondary"
                   :on-click modal/hide!}
       (tr "labels.cancel")]
      [:> fc/form-submit* {:variant "primary"}
       title]]]))

(mf/defc create-access-token-modal
  {::mf/register modal/components
   ::mf/register-as :create-access-token}
  []
  (let [created?
        (mf/use-state false)

        on-close
        (mf/use-fn
         (fn []
           (reset! created? false)
           (st/emit! (modal/hide))))

        on-created
        (mf/use-fn
         (fn []
           (reset! created? true)
           (st/emit! (ntf/success (tr "integrations.notification.success.created")))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-close-button)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click on-close
                         :icon i/close}]]

      (if @created?
        [:> token-created* {:title (tr "integrations.create-access-token.title.created")}]
        [:> create-token* {:title (tr "integrations.create-access-token.title")
                           :on-created on-created}])]]))

(mf/defc generate-mcp-token-modal
  {::mf/register modal/components
   ::mf/register-as :generate-mcp-token}
  []
  (let [created? (mf/use-state false)

        on-close
        (mf/use-fn
         (fn []
           (reset! created? false)
           (st/emit! (modal/hide))))

        on-created
        (mf/use-fn
         (fn []
           ;; NOTE: Analytics events use "key" terminology for historical reasons;
           ;; these names are immutable to avoid breaking analytics dashboards.
           (st/emit! (du/update-profile-props {:mcp-enabled true})
                     (ntf/success (tr "integrations.notification.success.created"))
                     (ev/event {::ev/name "generate-mcp-key"
                                ::ev/origin "integrations"})
                     (ev/event {::ev/name "enable-mcp"
                                ::ev/origin "integrations"
                                :source "key-creation"})
                     (mbc/event :mcp/enable {}))
           (reset! created? true)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-close-button)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click on-close
                         :icon i/close}]]

      (if @created?
        [:> token-created* {:title (tr "integrations.generate-mcp-key.title.created")
                            :is-mcp true}]
        [:> create-token* {:title (tr "integrations.generate-mcp-key.title")
                           :is-mcp true
                           :on-created on-created}])]]))

(defn- mcp-access-token?
  [atoken]
  (= "mcp" (:type atoken)))

(mf/defc regenerate-mcp-token-modal
  {::mf/register modal/components
   ::mf/register-as :regenerate-mcp-token}
  []
  (let [created?      (mf/use-state false)

        on-close
        (mf/use-fn
         (fn []
           (reset! created? false)
           (st/emit! (modal/hide))))

        on-created
        (mf/use-fn
         (fn []
           ;; NOTE: Analytics event uses "key" terminology for historical reasons.
           (st/emit! (du/update-profile-props {:mcp-enabled true})
                     (ntf/success (tr "integrations.notification.success.created"))
                     (ev/event {::ev/name "regenerate-mcp-key"
                                ::ev/origin "integrations"})
                     (mbc/event :mcp/enable {}))
           (reset! created? true)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-close-button)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click on-close
                         :icon i/close}]]

      (if @created?
        [:> token-created* {:title (tr "integrations.regenerate-mcp-key.title.created")
                            :is-mcp true}]
        [:> create-token* {:title (tr "integrations.regenerate-mcp-key.title")
                           :info (tr "integrations.regenerate-mcp-key.info")
                           :is-mcp true
                           :on-created on-created}])]]))

(mf/defc token-item*
  {::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [id name expires-at on-delete]}]
  (let [expires-txt (some-> expires-at (ct/format-inst "PPP"))
        expired?    (and (some? expires-at) (> (ct/now) expires-at))

        menu-open*  (mf/use-state false)
        menu-open?  (deref menu-open*)

        handle-menu-close
        (mf/use-fn
         #(reset! menu-open* false))

        handle-menu-click
        (mf/use-fn
         #(reset! menu-open* (not menu-open?)))

        handle-delete
        (mf/use-fn
         (mf/deps on-delete id)
         #(on-delete id))

        handle-open-confirm-modal
        (mf/use-fn
         (mf/deps handle-delete)
         (fn []
           (st/emit! (modal/show {:type :confirm
                                  :title (tr "integrations.delete-token.title")
                                  :message (tr "integrations.delete-token.message")
                                  :accept-label (tr "integrations.delete-token.accept")
                                  :on-accept handle-delete}))))

        options
        (mf/with-memo [handle-delete]
          [{:name    (tr "labels.delete")
            :id      "token-delete"
            :handler handle-open-confirm-modal}])]

    [:div {:class (stl/css :item) :data-id (str id)}
     [:> text* {:as "div"
                :typography t/body-medium
                :title name
                :class (stl/css :item-title)}
      name]

     [:> text* {:as "div"
                :typography t/body-small
                :class (stl/css-case :item-subtitle true
                                     :warning expired?)}
      (cond
        (nil? expires-at) (tr "integrations.no-expiration")
        expired?          (tr "integrations.expired-on" expires-txt)
        :else             (tr "integrations.expires-on" expires-txt))]

     [:div {:class (stl/css :item-actions)}
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :item-button)
                        :aria-pressed menu-open?
                        :aria-label (tr "labels.options")
                        :on-click handle-menu-click
                        :icon i/menu}]
      [:> context-menu* {:on-close handle-menu-close
                         :show menu-open?
                         :min-width true
                         :top -10
                         :left -138
                         :options options}]]]))

(defn valid-mcp-token?
  "Given access tokens list, return if it contains a valid (not expired) mcp key"
  [{:keys [expires-at] :as token}]
  (if token
    (or (nil? expires-at)
        (> expires-at (ct/now)))
    false))

(mf/defc mcp-server-section*
  {::mf/private true}
  [{:keys [access-tokens]}]
  (let [profile       (mf/deref refs/profile)

        token         (mf/with-memo [access-tokens]
                        (when-let [token (d/seek mcp-access-token? access-tokens)]
                          (assoc token :is-valid (valid-mcp-token? token))))

        token-valid?  (get token :is-valid)
        token-str     (get token :token)
        enabled?      (-> profile :props :mcp-enabled boolean)

        url           (dm/str cf/mcp-server-url "?userToken=" token-str)

        show-enabled? (and enabled? token-valid?)
        tooltip-id    (mf/use-id)

        handle-mcp-change
        (mf/use-fn
         (fn [value]
           (st/emit! (du/update-profile-props {:mcp-enabled value})
                     (ntf/show {:level :info
                                :type :toast
                                :content (if (true? value)
                                           (tr "integrations.notification.success.mcp-server-enabled")
                                           (tr "integrations.notification.success.mcp-server-disabled"))
                                :timeout notification-timeout})
                     (ev/event {::ev/name (if (true? value) "enable-mcp" "disable-mcp")
                                ::ev/origin "integrations"
                                :source "toggle"})
                     (if value
                       (mbc/event :mcp/enable {})
                       (mbc/event :mcp/disable {})))))

        handle-open-modal-generate
        (mf/use-fn
         #(st/emit! (modal/show {:type :generate-mcp-token})))

        handle-open-modal-regenerate
        (mf/use-fn
         #(st/emit! (modal/show {:type :regenerate-mcp-token})))

        handle-delete
        (mf/use-fn
         (fn [id]
           (let [params {:id id}
                 mdata  {:on-success #(st/emit! (du/fetch-access-tokens))}]
             (st/emit! (du/delete-access-token (with-meta params mdata))
                       (du/update-profile-props {:mcp-enabled false})
                       (mbc/event :mcp/disable {})))))

        on-copy-to-clipboard
        (mf/use-fn
         (mf/deps url)
         (fn [event]
           (dom/prevent-default event)
           (clipboard/to-clipboard url)
           (st/emit! (ntf/show {:level :info
                                :type :toast
                                :content (tr "integrations.notification.success.copied-link")
                                :timeout notification-timeout})
                     (ev/event {::ev/name "copy-mcp-url"
                                ::ev/origin "integrations"}))))]

    [:section {:class (stl/css :mcp-server-section)}
     [:div
      [:div {:class (stl/css :title)}
       [:> heading* {:level 2
                     :typography t/title-medium
                     :class (stl/css :color-primary :mcp-server-title)}
        (tr "integrations.mcp-server.title")]
       [:> text* {:as "span"
                  :typography t/body-small
                  :class (stl/css :beta)}
        (tr "integrations.mcp-server.title.beta")]]

      [:> text* {:as "div"
                 :typography t/body-medium
                 :class (stl/css :color-secondary)}
       (tr "integrations.mcp-server.description")]]

     [:div
      [:> text* {:as "h3"
                 :typography t/headline-small
                 :class (stl/css :color-primary)}
       (tr "integrations.mcp-server.status")]

      [:div {:class (stl/css :mcp-server-block)}
       (when (and (some? token) (not token-valid?))
         [:> notification-pill* {:level :error
                                 :type :context}
          [:div {:class (stl/css :mcp-server-notification)}
           [:> text* {:as "div"
                      :typography t/body-medium
                      :class (stl/css :color-primary)}
            (tr "integrations.mcp-server.status.expired.0")]

           [:> text* {:as "div"
                      :typography t/body-medium
                      :class (stl/css :color-primary)}
            (tr "integrations.mcp-server.status.expired.1")]]])

       [:div {:class (stl/css :mcp-server-switch)}
        [:> switch* {:label (if show-enabled?
                              (tr "integrations.mcp-server.status.enabled")
                              (tr "integrations.mcp-server.status.disabled"))
                     :default-checked show-enabled?
                     :on-change handle-mcp-change}]

        (when-not token
          [:div {:class (stl/css :mcp-server-switch-cover)
                 :on-click handle-open-modal-generate}])

        (when (and token (not token-valid?))
          [:div {:class (stl/css :mcp-server-switch-cover)
                 :on-click handle-open-modal-regenerate}])]]]

     (when (some? token)
       [:div {:class (stl/css :mcp-server-key)}
        [:> text* {:as "h3"
                   :typography t/headline-small
                   :class (stl/css :color-primary)}
         (tr "integrations.mcp-server.mcp-keys.title")]

        [:div {:class (stl/css :mcp-server-block)}
         [:div {:class (stl/css :mcp-server-regenerate)}
          [:> button* {:variant "primary"
                       :class (stl/css :fit-content)
                       :on-click handle-open-modal-regenerate}
           (tr "integrations.mcp-server.mcp-keys.regenerate")]
          [:> tooltip* {:content (tr "integrations.mcp-server.mcp-keys.tootip")
                        :id tooltip-id}
           [:> icon* {:icon-id i/info
                      :class (stl/css :color-secondary)}]]]

         [:div {:class (stl/css :list)}
          [:> token-item* {:key (:id token)
                           :id (:id token)
                           :name (:name token)
                           :expires-at (:expires-at token)
                           :on-delete handle-delete}]]]])

     [:> notification-pill* {:level :default
                             :type :context}
      [:div {:class (stl/css :mcp-server-notification)}
       (when token
         [:> text* {:as "div"
                    :typography t/body-medium
                    :class (stl/css :color-secondary)}
          (tr "integrations.mcp-server.mcp-keys.info")])

       (when token
         [:> input-copy* {:value url
                          :on-copy-to-clipboard on-copy-to-clipboard}])

       [:> text* {:as "div"
                  :typography t/body-medium
                  :class (stl/css :color-secondary)}
        [:a {:href cf/mcp-help-center-uri
             :target "_blank"
             :rel "noopener noreferrer"
             :class (stl/css :mcp-server-notification-link)}
         (tr "integrations.mcp-server.mcp-keys.help")
         [:> icon* {:icon-id i/open-link}]]]]]]))

;; --- AI providers section (account-level connections for the design agent)
;;
;; One card per supported provider, each independently connectable — this
;; is not an either/or choice. The key is stored on blur without any
;; up-front validation (an invalid key or an unfunded account only shows
;; up when the user sends a message). Each card lists a curated subset of
;; the provider's latest models; the models the user toggles on across all
;; cards form the single pool that populates the chat's model picker.

(def ^:private ai-provider-defs
  (into [{:id "anthropic" :label "Anthropic"   :models-hint "Claude models"}
         {:id "openai"    :label "OpenAI"      :models-hint "GPT models"}
         {:id "zhipu"     :label "Zhipu AI"    :models-hint "GLM models"}
         {:id "moonshot"  :label "Moonshot AI" :models-hint "Kimi models"}]
        ;; offered once each has passed the provider verification check on
        ;; this instance; until then behind the same flag as on the server
        (when (contains? cf/flags :design-agent-preview-providers)
          [{:id "google"   :label "Google"    :models-hint "Gemini models"}
           {:id "xai"      :label "xAI"       :models-hint "Grok models"}
           {:id "mistral"  :label "Mistral"   :models-hint "Mistral models"}
           {:id "deepseek" :label "DeepSeek"  :models-hint "DeepSeek models"}])))

;; What each model can do comes from the provider layer's catalog, held in
;; app.main.data.ai-providers: the agent reads it to decide how to encode a
;; turn, so it cannot live in a UI ns.

(defn- format-context
  "Human-friendly context-window size, e.g. 200000 → \"200K\", 1000000 → \"1M\"."
  [n]
  (cond
    (nil? n)        nil
    (>= n 1000000)  (dm/str (quot n 1000000) "M")
    (>= n 1000)     (dm/str (quot n 1000) "K")
    :else           (dm/str n)))

;; What stands in for a key on an endpoint that asks for none: the server
;; keeps one value per connection, and the endpoint ignores it.
(def ^:private no-key "not-needed")

(mf/defc ai-provider-card*
  {::mf/private true}
  [{:keys [provider label models-hint status keyless]}]
  (let [connected?  (:connected status)
        enabled     (:enabled-models status)
        enabled-set (set enabled)

        api-key*    (mf/use-state "")
        api-key     (deref api-key*)

        pending*    (mf/use-state false)
        pending?    (deref pending*)

        ;; a transient "Saved" acknowledgement next to the key field,
        ;; cleared as soon as the user edits the field again
        saved?*     (mf/use-state false)
        saved?      (deref saved?*)

        ;; the model checkboxes live in a dropdown menu opened from a trigger
        models-open* (mf/use-state false)
        models-open? (deref models-open*)
        models-ref   (mf/use-ref nil)
        on-toggle-models (mf/use-fn #(swap! models-open* not))

        ;; the curated catalog, plus any enabled model no longer in it
        ;; (kept visible so the user can toggle it back off)
        catalog     (->> (get (mf/deref refs/ai-model-catalog) provider [])
                         (mapv (fn [{:keys [id name contextLimit vision tools]}]
                                 {:id id :label name :context contextLimit :vision (true? vision)
                                  :no-tools (false? tools)})))
        catalog-ids (set (map :id catalog))
        rows        (concat catalog
                            (for [m enabled
                                  :when (not (contains? catalog-ids m))]
                              ;; what it can do is not known: it may be tried, and is sent no images
                              {:id m :label m :context nil :vision false :unknown true}))

        save-key
        (mf/use-fn
         (mf/deps provider label)
         (fn [raw]
           (let [key (str/trim (or raw ""))]
             ;; autosave on blur: skip empties, no provider round-trip
             (when-not (str/blank? key)
               (reset! pending* true)
               (st/emit!
                (dai/set-ai-provider-key
                 (with-meta {:provider provider :api-key key}
                   {:on-success
                    (fn [_]
                      (reset! pending* false)
                      (reset! api-key* "")
                      (reset! saved?* true)
                      (st/emit! (ntf/show {:level :info
                                           :type :toast
                                           :content (tr "integrations.ai-provider.notification.key-saved" label)
                                           :timeout notification-timeout})))
                    :on-error
                    (fn [_cause]
                      (reset! pending* false)
                      (st/emit! (ntf/error (tr "integrations.ai-provider.error.generic")))
                      (rx/empty))})))))))

        on-key-change
        (mf/use-fn
         (fn [event]
           (reset! saved?* false)
           (reset! api-key* (dom/get-target-val event))))

        on-key-blur
        (mf/use-fn
         (mf/deps save-key)
         (fn [event]
           (save-key (dom/get-target-val event))))

        ;; Enter saves without leaving the field
        on-key-down
        (mf/use-fn
         (mf/deps save-key)
         (fn [event]
           (when (= "Enter" (.-key event))
             (dom/prevent-default event)
             (save-key (dom/get-target-val event)))))

        on-toggle-model
        (mf/use-fn
         (mf/deps provider enabled)
         (fn [event]
           (let [node    (dom/get-target event)
                 model   (dom/get-data node "model")
                 checked (dom/checked? node)
                 next    (if checked
                           (conj (vec enabled) model)
                           (vec (remove #(= % model) enabled)))]
             (st/emit! (dai/set-ai-provider-models {:provider provider
                                                    :models next})))))

        on-disconnect
        (mf/use-fn
         (mf/deps provider label)
         (fn []
           (st/emit! (dai/disconnect-ai-provider {:provider provider})
                     (ntf/show {:level :info
                                :type :toast
                                :content (tr "integrations.ai-provider.notification.disconnected" label)
                                :timeout notification-timeout}))))]

    ;; close the models dropdown on any click outside of it
    (mf/with-effect [models-open?]
      (when ^boolean models-open?
        (let [on-doc (fn [event]
                       (let [node (mf/ref-val models-ref)]
                         (when (and node (not (.contains node (dom/get-target event))))
                           (reset! models-open* false))))]
          (.addEventListener js/document "pointerdown" on-doc)
          (fn [] (.removeEventListener js/document "pointerdown" on-doc)))))

    [:div {:class (stl/css :provider-card)
           :data-testid (dm/str "ai-provider-" provider)}
     [:div {:class (stl/css :provider-card-header)}
      [:div {:class (stl/css :provider-card-title)}
       [:div {:class (stl/css :provider-card-name)}
        [:> text* {:as "h3"
                   :typography t/headline-small
                   :class (stl/css :color-primary)}
         label]
        [:> text* {:as "span"
                   :typography t/body-small
                   :class (stl/css :color-secondary :provider-models-hint)}
         models-hint]]
       (when ^boolean connected?
         [:div {:class (stl/css :provider-connected)}
          [:> text* {:as "span"
                     :typography t/body-small
                     :class (stl/css :provider-connected-tag)}
           (if keyless
             (tr "integrations.ai-endpoint.status.in-use")
             (tr "integrations.ai-provider.status.saved" (:key-hint status)))]
          [:> icon-button* {:variant "ghost"
                            :icon-size "s"
                            :aria-label (tr "integrations.ai-provider.disconnect")
                            :icon i/delete
                            :on-click on-disconnect}]])]]

     ;; an endpoint that asks for no key is switched on with one click
     (when (and keyless (not connected?))
       [:div {:class (stl/css :provider-key-form)}
        [:> button* {:variant "secondary"
                     :disabled pending?
                     :on-click #(save-key no-key)}
         (tr "integrations.ai-endpoint.use")]])

     ;; the key is autosaved on blur — no explicit connect action
     (when-not keyless
       [:div {:class (stl/css :provider-key-form)}
        [:div {:class (stl/css :provider-key-row)}
         [:> input* {:type "text"
                     :value api-key
                     :aria-label (tr "integrations.ai-provider.key.label")
                     :placeholder (if connected?
                                    (tr "integrations.ai-provider.key.placeholder-rotate")
                                    (tr "integrations.ai-provider.key.placeholder"))
                     :on-change on-key-change
                     :on-blur on-key-blur
                     :on-key-down on-key-down}]]

        (when (or pending? saved?)
          [:> text* {:as "div"
                     :typography t/body-small
                     :class (stl/css :provider-key-status)}
           (if pending?
             (tr "integrations.ai-provider.saving")
             (tr "integrations.ai-provider.saved"))])])

     [:div {:class (stl/css :provider-models)}
      [:> text* {:as "h4"
                 :typography t/body-medium
                 :class (stl/css :color-primary)}
       (tr "integrations.ai-provider.models.title")]

      (when (and connected? (empty? enabled))
        [:> text* {:as "div"
                   :typography t/body-small
                   :class (stl/css :provider-models-note)}
         (tr "integrations.ai-provider.models.select-one")])

      [:div {:class (stl/css :provider-models-combobox)
             :ref models-ref}
       [:button {:type "button"
                 :class (stl/css-case :provider-models-trigger true
                                      :provider-models-trigger-open models-open?)
                 :disabled (not connected?)
                 :on-click on-toggle-models}
        [:span {:class (stl/css :provider-models-trigger-label)}
         (if (seq enabled)
           (tr "integrations.ai-provider.models.count" (count enabled))
           (tr "integrations.ai-provider.models.select-placeholder"))]
        [:> icon* {:icon-id i/arrow-down :class (stl/css :provider-models-caret)}]]

       (when models-open?
         [:ul {:class (stl/css :provider-models-menu)}
          (for [{:keys [id label context vision no-tools unknown]} rows]
            [:li {:key id :class (stl/css :provider-models-item)}
             [:> checkbox* {:id (dm/str "ai-model-" provider "-" id)
                            :label label
                            :checked (and (contains? enabled-set id) (not no-tools))
                            ;; the agent is its tools: a model without them cannot be chosen
                            :disabled (or (not connected?) no-tools)
                            :data-model id
                            :on-change on-toggle-model}]
             (when-let [ctx (format-context context)]
               [:span {:class (stl/css :provider-models-context)}
                (tr "integrations.ai-provider.models.context" ctx)])
             ;; only called out when present: the agent chat can send images to
             ;; these models, so it is worth knowing which of your enabled
             ;; models can actually look at one
             (when vision
               [:span {:class (stl/css :provider-models-vision)
                       :title (tr "integrations.ai-provider.models.vision-hint")}
                (tr "integrations.ai-provider.models.vision")])
             (when no-tools
               [:span {:class (stl/css :provider-models-vision)
                       :title (tr "integrations.ai-provider.models.no-tools-hint")}
                (tr "integrations.ai-provider.models.no-tools")])
             (when unknown
               [:span {:class (stl/css :provider-models-vision)
                       :title (tr "integrations.ai-provider.models.unknown-hint")}
                (tr "integrations.ai-provider.models.unknown")])])])]]]))

(def ^:private same-as-main "same")

(mf/defc ai-helper-model*
  "The model for the agent's side jobs, chosen among the models the user
  enabled; by default the one they build with."
  {::mf/private true}
  [{:keys [statuses]}]
  (let [catalog (mf/deref refs/ai-model-catalog)
        pool    (mf/with-memo [statuses catalog]
                  (vec (for [[_ {:keys [provider enabled-models]}] statuses
                             model enabled-models
                             :when (dai/selectable? provider model)]
                         {:provider provider :model model})))
        chosen  (some (fn [[_ {:keys [provider helper-model]}]]
                        (when (string? helper-model) (dm/str provider "/" helper-model)))
                      statuses)
        options (mf/with-memo [pool]
                  (into [{:id same-as-main :label (tr "integrations.ai-provider.helper.same")}]
                        (map (fn [{:keys [provider model]}]
                               {:id (dm/str provider "/" model)
                                :label (dm/str (or (:name (dai/model provider model)) model)
                                               " · " provider)}))
                        pool))
        on-change
        (mf/use-fn
         (fn [id]
           (if (= same-as-main id)
             (st/emit! (dai/set-ai-helper-model {}))
             (let [[provider model] (str/split id "/" 2)]
               (st/emit! (dai/set-ai-helper-model {:provider provider :model model}))))))]
    (when (seq pool)
      [:div {:class (stl/css :ai-helper-model)}
       [:> heading* {:level 3
                     :typography t/headline-small
                     :class (stl/css :color-primary)}
        (tr "integrations.ai-provider.helper.title")]
       [:> text* {:as "div"
                  :typography t/body-medium
                  :class (stl/css :color-secondary)}
        (tr "integrations.ai-provider.helper.description")]
       ;; keyed by what is chosen: the select reads its value once
       [:> select* {:key (or chosen same-as-main)
                    :aria-label (tr "integrations.ai-provider.helper.title")
                    :options options
                    :default-selected (or chosen same-as-main)
                    :on-change on-change}]])))

(def ^:private ai-endpoint-error-keys
  {:ai-endpoint-address-invalid "integrations.ai-endpoint.error.address"
   :ai-endpoint-name-taken      "integrations.ai-endpoint.error.name"
   :ai-endpoint-models-repeated "integrations.ai-endpoint.error.models"
   :ai-endpoints-limit-reached  "integrations.ai-endpoint.error.limit"})

(def ^:private empty-model
  {:id "" :tools true :vision false :context ""})

(mf/defc ai-endpoints-admin*
  "Where an instance admin registers the model endpoints of the organisation:
  an address, and the models it serves with what each can do. Users only
  ever see the name."
  {::mf/private true}
  [{:keys [endpoints]}]
  (let [form*   (mf/use-state {:name "" :base-uri "" :requires-key false :models [empty-model]})
        form    (deref form*)
        error*  (mf/use-state nil)
        error   (deref error*)
        ready?  (and (not (str/blank? (:name form)))
                     (not (str/blank? (:base-uri form)))
                     (some #(not (str/blank? (:id %))) (:models form)))

        on-field
        (mf/use-fn
         (fn [event]
           (let [node  (dom/get-target event)
                 field (keyword (dom/get-data node "field"))]
             (reset! error* nil)
             (swap! form* assoc field (if (= :requires-key field)
                                        (dom/checked? node)
                                        (dom/get-value node))))))

        on-model
        (mf/use-fn
         (fn [event]
           (let [node  (dom/get-target event)
                 index (d/parse-integer (dom/get-data node "index"))
                 field (keyword (dom/get-data node "field"))]
             (swap! form* assoc-in [:models index field]
                    (if (contains? #{:tools :vision} field)
                      (dom/checked? node)
                      (dom/get-value node))))))

        on-add-model
        (mf/use-fn #(swap! form* update :models conj empty-model))

        on-save
        (mf/use-fn
         (mf/deps form)
         (fn []
           (let [models (->> (:models form)
                             (remove #(str/blank? (:id %)))
                             (mapv (fn [{:keys [id tools vision context]}]
                                     (cond-> {:id (str/trim id) :tools tools :vision vision}
                                       (some? (d/parse-integer context))
                                       (assoc :context-limit (d/parse-integer context))))))]
             (st/emit!
              (dai/set-ai-endpoint
               (with-meta {:name (str/trim (:name form))
                           :base-uri (str/trim (:base-uri form))
                           :requires-key (:requires-key form)
                           :models models}
                 {:on-success
                  (fn [_]
                    (reset! form* {:name "" :base-uri "" :requires-key false :models [empty-model]}))
                  :on-error
                  (fn [cause]
                    (reset! error* (tr (get ai-endpoint-error-keys (:code (ex-data cause))
                                            "integrations.ai-endpoint.error.generic")))
                    (rx/empty))}))))))

        on-delete
        (mf/use-fn
         (fn [event]
           (let [id (dom/get-data (dom/get-current-target event) "id")]
             (st/emit! (dai/delete-ai-endpoint {:id (uuid/parse id)})))))]

    [:div {:class (stl/css :ai-endpoints-admin)
           :data-testid "ai-endpoints-admin"}
     [:> heading* {:level 3
                   :typography t/headline-small
                   :class (stl/css :color-primary)}
      (tr "integrations.ai-endpoint.admin.title")]
     [:> text* {:as "div"
                :typography t/body-medium
                :class (stl/css :color-secondary)}
      (tr "integrations.ai-endpoint.admin.description")]

     (for [{:keys [id name base-uri models]} endpoints]
       [:div {:key (dm/str id) :class (stl/css :ai-endpoint-row)}
        [:div {:class (stl/css :ai-endpoint-row-text)}
         [:> text* {:as "span" :typography t/body-medium :class (stl/css :color-primary)} name]
         [:> text* {:as "span" :typography t/body-small :class (stl/css :color-secondary)}
          (dm/str base-uri " · " (str/join ", " (map :id models)))]]
        [:> icon-button* {:variant "ghost"
                          :icon-size "s"
                          :aria-label (tr "integrations.ai-endpoint.admin.remove" name)
                          :icon i/delete
                          :data-id (dm/str id)
                          :on-click on-delete}]])

     [:div {:class (stl/css :ai-endpoint-form)}
      [:> input* {:type "text"
                  :value (:name form)
                  :data-field "name"
                  :aria-label (tr "integrations.ai-endpoint.admin.name")
                  :placeholder (tr "integrations.ai-endpoint.admin.name")
                  :on-change on-field}]
      [:> input* {:type "text"
                  :value (:base-uri form)
                  :data-field "base-uri"
                  :aria-label (tr "integrations.ai-endpoint.admin.address")
                  :placeholder (tr "integrations.ai-endpoint.admin.address-placeholder")
                  :on-change on-field}]
      [:> checkbox* {:id "ai-endpoint-requires-key"
                     :label (tr "integrations.ai-endpoint.admin.requires-key")
                     :checked (:requires-key form)
                     :data-field "requires-key"
                     :on-change on-field}]

      [:> text* {:as "div" :typography t/body-small :class (stl/css :color-secondary)}
       (tr "integrations.ai-endpoint.admin.models-hint")]
      (for [[index model] (map-indexed vector (:models form))]
        [:div {:key index :class (stl/css :ai-endpoint-model)}
         [:> input* {:type "text"
                     :value (:id model)
                     :data-index index
                     :data-field "id"
                     :aria-label (tr "integrations.ai-endpoint.admin.model-id")
                     :placeholder (tr "integrations.ai-endpoint.admin.model-id")
                     :on-change on-model}]
         [:> input* {:type "text"
                     :value (:context model)
                     :data-index index
                     :data-field "context"
                     :aria-label (tr "integrations.ai-endpoint.admin.context")
                     :placeholder (tr "integrations.ai-endpoint.admin.context")
                     :on-change on-model}]
         [:> checkbox* {:id (dm/str "ai-endpoint-model-tools-" index)
                        :label (tr "integrations.ai-endpoint.admin.tools")
                        :checked (:tools model)
                        :data-index index
                        :data-field "tools"
                        :on-change on-model}]
         [:> checkbox* {:id (dm/str "ai-endpoint-model-vision-" index)
                        :label (tr "integrations.ai-provider.models.vision")
                        :checked (:vision model)
                        :data-index index
                        :data-field "vision"
                        :on-change on-model}]])

      (when (some? error)
        [:> text* {:as "div" :typography t/body-small :class (stl/css :ai-endpoint-error)} error])

      [:div {:class (stl/css :ai-endpoint-actions)}
       [:> button* {:variant "ghost" :on-click on-add-model}
        (tr "integrations.ai-endpoint.admin.add-model")]
       [:> button* {:variant "primary" :disabled (not ready?) :on-click on-save}
        (tr "integrations.ai-endpoint.admin.save")]]]]))

(mf/defc agent-enabled-files*
  "While the account switch is off, the files where the user turned the
  agent back on, as a count with one action that turns it off in all of
  them. The files are not listed."
  {::mf/private true}
  []
  (let [n (mf/deref refs/agent-enabled-file-count)

        on-accept
        (mf/use-fn
         #(st/emit! (agent-switch/disable-in-enabled-files)))]

    (mf/with-effect []
      (st/emit! (agent-switch/fetch-enabled-file-count)))

    (when (and (number? n) (pos? n))
      [:> actionable* {:class (stl/css :agent-enabled-files)
                       :role "status"
                       :accept-label (tr "integrations.ai-provider.enabled-files.action")
                       :on-accept on-accept}
       (tr "integrations.ai-provider.enabled-files" (i18n/c n))])))

(mf/defc agent-status*
  "The account-wide agent switch. Instance scope already holds here (the
  section renders only when the instance ships the agent), so it reads the
  account setting alone."
  {::mf/private true}
  []
  (let [profile  (mf/deref refs/profile)
        enabled? (not (true? (-> profile :props :agent-disabled)))

        on-change
        (mf/use-fn
         (fn [value]
           (st/emit! (agent-switch/set-account-enabled value)
                     (ntf/show {:level :info
                                :type :toast
                                :content (if (true? value)
                                           (tr "integrations.ai-provider.notification.agent-enabled")
                                           (tr "integrations.ai-provider.notification.agent-disabled"))
                                :timeout notification-timeout}))))]

    [:div
     [:> text* {:as "h3"
                :typography t/headline-small
                :class (stl/css :color-primary)}
      (tr "integrations.ai-provider.status")]

     [:div {:class (stl/css :ai-provider-status)}
      [:> switch* {:label (if enabled?
                            (tr "integrations.ai-provider.status.enabled")
                            (tr "integrations.ai-provider.status.disabled"))
                   :default-checked enabled?
                   :on-change on-change}]

      [:> text* {:as "p"
                 :typography t/body-medium
                 :class (stl/css :color-secondary)}
       (tr "integrations.ai-provider.status.hint")]

      ;; only while the account switch is off; mounting it loads the count
      (when-not ^boolean enabled?
        [:> agent-enabled-files* {}])]]))

(mf/defc ai-providers-section*
  {::mf/private true}
  []
  (let [statuses  (mf/deref refs/ai-providers)
        endpoints (mf/deref refs/ai-endpoints)]

    (mf/with-effect []
      (st/emit! (dai/fetch-ai-providers)))

    [:section {:class (stl/css :ai-provider-section)}
     [:div
      [:div {:class (stl/css :title)}
       [:> heading* {:level 2
                     :typography t/title-medium
                     :class (stl/css :color-primary :ai-provider-title)}
        (tr "integrations.ai-provider.title")]
       [:> text* {:as "span"
                  :typography t/body-small
                  :class (stl/css :beta)}
        (tr "integrations.mcp-server.title.beta")]]

      [:> text* {:as "div"
                 :typography t/body-medium
                 :class (stl/css :color-secondary :ai-provider-description)}
       (tr "integrations.ai-provider.description")]]

     [:> agent-status* {}]

     (for [{:keys [id label models-hint]} ai-provider-defs]
       [:> ai-provider-card* {:key id
                              :provider id
                              :label label
                              :models-hint models-hint
                              :status (get statuses id)}])

     ;; the models this organisation runs itself, registered by an admin
     (for [{:keys [provider name requires-key]} (:endpoints endpoints)]
       [:> ai-provider-card* {:key provider
                              :provider provider
                              :label name
                              :models-hint (tr "integrations.ai-endpoint.hint")
                              :keyless (not requires-key)
                              :status (get statuses provider)}])

     (when (:can-manage endpoints)
       [:> ai-endpoints-admin* {:endpoints (:endpoints endpoints)}])

     [:> ai-helper-model* {:statuses statuses}]]))

(def ^:private agent-mcp-error-keys
  {:mcp-server-name-invalid   "integrations.agent-mcp.error.name"
   :mcp-server-url-not-https  "integrations.agent-mcp.error.https"
   :ssrf-blocked-target       "integrations.agent-mcp.error.private"
   :mcp-servers-limit-reached "integrations.agent-mcp.error.limit"})

(mf/defc agent-mcp-server-tools*
  "What a connected server offers, one switch per tool: a tool switched off is
  never offered to the agent."
  {::mf/private true}
  [{:keys [server answer]}]
  (let [{:keys [tools error loading]} answer]
    [:div {:class (stl/css :agent-mcp-tools)
           :data-testid (dm/str "agent-mcp-tools-" (:name server))}
     (cond
       ^boolean loading
       [:> text* {:as "span" :typography t/body-small :class (stl/css :color-secondary)}
        (tr "integrations.agent-mcp.tools.loading")]

       (some? error)
       [:> text* {:as "span" :typography t/body-small :class (stl/css :color-secondary)}
        (tr "integrations.agent-mcp.tools.error" error)]

       (empty? tools)
       [:> text* {:as "span" :typography t/body-small :class (stl/css :color-secondary)}
        (tr "integrations.agent-mcp.tools.none")]

       :else
       (for [{:keys [name description is-enabled]} tools]
         [:div {:key name :class (stl/css :agent-mcp-tool)}
          [:div {:class (stl/css :agent-mcp-tool-text)}
           [:> text* {:as "span" :typography t/body-medium :class (stl/css :color-primary)}
            name]
           (when-not (str/blank? description)
             [:> text* {:as "span"
                        :typography t/body-small
                        :class (stl/css :color-secondary :agent-mcp-tool-description)}
              description])]
          [:> switch* {:aria-label (tr "integrations.agent-mcp.tools.enabled" name)
                       :default-checked is-enabled
                       :on-change (fn [checked]
                                    (st/emit! (dai/set-agent-mcp-server-tool server name (boolean checked))))}]]))]))

(mf/defc agent-mcp-servers-section*
  "The external MCP servers this user connected to the agent: a list, and a
  short form to add one. A server's token is typed here and never shown again."
  {::mf/private true}
  []
  (let [servers (mf/deref refs/agent-mcp-servers)
        listed  (mf/deref refs/agent-mcp-server-tools)
        name*   (mf/use-state "")
        url*    (mf/use-state "")
        token*  (mf/use-state "")
        name    (deref name*)
        url     (deref url*)
        token   (deref token*)
        ready?  (and (not (str/blank? name)) (not (str/blank? url)))

        on-add
        (mf/use-fn
         (mf/deps name url token)
         (fn [event]
           (dom/prevent-default event)
           (let [params (cond-> {:name (str/trim name) :url (str/trim url)}
                          (not (str/blank? token)) (assoc :token (str/trim token)))]
             (st/emit!
              (dai/set-agent-mcp-server
               (with-meta params
                 {:on-success
                  (fn [_]
                    (reset! name* "")
                    (reset! url* "")
                    (reset! token* "")
                    (st/emit! (ntf/success (tr "integrations.agent-mcp.notification.connected" (:name params)))))
                  :on-error
                  (fn [cause]
                    (let [code (:code (ex-data cause))]
                      (st/emit! (ntf/error (tr (get agent-mcp-error-keys code "integrations.agent-mcp.error.generic")))))
                    (rx/empty))}))))))]

    (mf/with-effect []
      (st/emit! (dai/fetch-agent-mcp-servers)))

    [:section {:class (stl/css :ai-provider-section)
               :data-testid "agent-mcp-servers"}
     [:div
      [:div {:class (stl/css :title)}
       [:> heading* {:level 2
                     :typography t/title-medium
                     :class (stl/css :color-primary :ai-provider-title)}
        (tr "integrations.agent-mcp.title")]
       [:> text* {:as "span"
                  :typography t/body-small
                  :class (stl/css :beta)}
        (tr "integrations.mcp-server.title.beta")]]

      [:> text* {:as "div"
                 :typography t/body-medium
                 :class (stl/css :color-secondary :ai-provider-description)}
       (tr "integrations.agent-mcp.description")]]

     (for [{:keys [name url has-token is-enabled] :as server} servers]
       [:div {:key name
              :class (stl/css :provider-card)
              :data-testid (dm/str "agent-mcp-server-" name)}
        [:div {:class (stl/css :provider-card-header)}
         [:div {:class (stl/css :provider-card-title)}
          [:div {:class (stl/css :provider-card-name)}
           [:> text* {:as "h3"
                      :typography t/headline-small
                      :class (stl/css :color-primary)}
            name]
           [:> text* {:as "span"
                      :typography t/body-small
                      :class (stl/css :color-secondary :provider-models-hint)}
            url]]
          [:div {:class (stl/css :provider-connected)}
           (when ^boolean has-token
             [:> text* {:as "span"
                        :typography t/body-small
                        :class (stl/css :provider-connected-tag)}
              (tr "integrations.agent-mcp.token-saved")])
           [:> switch* {:label (tr "integrations.agent-mcp.enabled")
                        :default-checked is-enabled
                        :on-change (fn [checked]
                                     (st/emit! (dai/set-agent-mcp-server
                                                {:name name :url url :is-enabled (boolean checked)})))}]
           [:> icon-button* {:variant "ghost"
                             :icon-size "s"
                             :aria-label (tr "integrations.agent-mcp.remove")
                             :icon i/delete
                             :on-click (fn [_] (st/emit! (dai/delete-agent-mcp-server {:name name})))}]]]]
        (let [answer (get listed name)]
          [:*
           [:> button* {:variant "secondary"
                        :type "button"
                        :data-testid (dm/str "agent-mcp-show-tools-" name)
                        :on-click (fn [_]
                                    (st/emit! (if (some? answer)
                                                (dai/hide-agent-mcp-server-tools name)
                                                (dai/fetch-agent-mcp-server-tools name))))}
            (if (some? answer)
              (tr "integrations.agent-mcp.tools.hide")
              (tr "integrations.agent-mcp.tools.show"))]
           (when (some? answer)
             [:> agent-mcp-server-tools* {:server server :answer answer}])])])

     [:form {:class (stl/css :provider-card)
             :on-submit on-add}
      [:div {:class (stl/css :provider-key-form)}
       [:div {:class (stl/css :provider-key-row)}
        [:> input* {:type "text"
                    :value name
                    :aria-label (tr "integrations.agent-mcp.name.label")
                    :placeholder (tr "integrations.agent-mcp.name.placeholder")
                    :on-change #(reset! name* (dom/get-target-val %))}]]
       [:div {:class (stl/css :provider-key-row)}
        [:> input* {:type "text"
                    :value url
                    :aria-label (tr "integrations.agent-mcp.url.label")
                    :placeholder (tr "integrations.agent-mcp.url.placeholder")
                    :on-change #(reset! url* (dom/get-target-val %))}]]
       [:div {:class (stl/css :provider-key-row)}
        [:> input* {:type "password"
                    :value token
                    :aria-label (tr "integrations.agent-mcp.token.label")
                    :placeholder (tr "integrations.agent-mcp.token.placeholder")
                    :on-change #(reset! token* (dom/get-target-val %))}]]
       [:> button* {:variant "primary"
                    :type "submit"
                    :disabled (not ready?)}
        (tr "integrations.agent-mcp.connect")]]]]))

(mf/defc access-tokens-section*
  {::mf/private true}
  [{:keys [access-tokens]}]
  (let [access-tokens
        (mf/with-memo [access-tokens]
          (not-empty (filter #(nil? (:type %)) access-tokens)))

        handle-click
        (mf/use-fn
         #(st/emit! (modal/show {:type :create-access-token})))

        handle-delete
        (mf/use-fn
         (fn [token-id]
           (let [params {:id token-id}
                 mdata  {:on-success #(st/emit! (du/fetch-access-tokens))}]
             (st/emit! (du/delete-access-token (with-meta params mdata))))))]

    [:section {:class (stl/css :access-tokens-section)}
     [:> heading* {:level 2
                   :typography t/title-medium
                   :class (stl/css :color-primary)}
      (tr "integrations.access-tokens.personal")]

     [:> text* {:as "div"
                :typography t/body-medium
                :class (stl/css :color-secondary)}
      (tr "integrations.access-tokens.personal.description")]

     [:> button* {:variant "primary"
                  :class (stl/css :fit-content)
                  :on-click handle-click}
      (tr "integrations.access-tokens.create")]

     (if access-tokens
       [:div {:class (stl/css :list)}
        (for [{:keys [id] :as token} access-tokens]
          [:> token-item* {:key (dm/str id)
                           :id id
                           :name (:name token)
                           :expires-at (:expires-at token)
                           :on-delete handle-delete}])]

       [:div {:class (stl/css :frame)}
        [:> text* {:as "div"
                   :typography t/body-medium
                   :class (stl/css :color-secondary :text-center)}
         [:div (tr "integrations.access-tokens.empty.no-access-tokens")]
         [:div (tr "integrations.access-tokens.empty.add-one")]]])]))

(mf/defc integrations-page*
  []
  (let [access-tokens (mf/deref refs/access-tokens)
        props         (mf/props {:access-tokens access-tokens})

        access-tokens-enabled?
        (contains? cf/flags :access-tokens)

        mcp-enabled?
        (contains? cf/flags :mcp)

        ;; instance scope: provider keys stay manageable, and the agent can
        ;; be switched back on here, while it is off for the user
        agent-available?
        (agent-access/instance-enabled?)

        agent-mcp-enabled?
        (and agent-available? (contains? cf/flags :design-agent-mcp))]

    (mf/with-effect []
      (dom/set-html-title (tr "title.settings.integrations"))
      (st/emit! (du/fetch-access-tokens)))

    [:section {:class (stl/css :integrations)
               :aria-labelledby "integrations-section-title"}
     [:> heading* {:level 2
                   :typography t/title-large
                   :class (stl/css :color-primary)
                   :id "integrations-section-title"}
      (tr "integrations.title")]

     (when ^boolean mcp-enabled?
       [:> mcp-server-section* props])

     (when ^boolean mcp-enabled?
       [:hr {:class (stl/css :separator)}])

     (when ^boolean agent-available?
       [:> ai-providers-section* {}])

     (when ^boolean agent-mcp-enabled?
       [:hr {:class (stl/css :separator)}])

     (when ^boolean agent-mcp-enabled?
       [:> agent-mcp-servers-section* {}])

     (when (and ^boolean agent-available? ^boolean access-tokens-enabled?)
       [:hr {:class (stl/css :separator)}])

     (when ^boolean access-tokens-enabled?
       [:> access-tokens-section* props])]))
