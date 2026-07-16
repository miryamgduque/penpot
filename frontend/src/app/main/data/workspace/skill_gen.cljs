;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.skill-gen
  "Generate a structured skill document from the guided-capture answers (US #9).

  One non-streaming completion through the same backend proxy the chat uses
  (`:ai-agent-round`, no tools), asking the model for a JSON envelope we can
  parse deterministically — so we never re-implement the aikit frontmatter reader
  on the client and the user never sees the raw structure. The user's confirmed
  `mode` and their example `trigger` / `what` stay authoritative; the model
  supplies the name, label, category (classified into an existing one) and the
  playbook `body`. `reactive` is On-call / Observer (US #14) — user-chosen too."
  (:require
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(def categories
  "The existing categories a generated skill is filed under (closest match)."
  ["Audits" "Build"])

;; --- Prompt

(def ^:private instructions
  (str/join "\n"
            ["You turn a user's description of a design-agent skill into a structured skill document."
             "The skill runs inside Penpot's embedded design agent, which has native tools (read the"
             "design, create/modify shapes, apply tokens, audit the file). It is NOT an external MCP"
             "server and has NO plugin API — do not mention execute_code, penpotUtils, MCP tools or"
             "the Plugin API in the body."
             ""
             "Reply with ONLY a JSON object (no prose, no code fences) with these keys:"
             "- \"name\": a short kebab-case slug, e.g. \"tone-of-voice-checker\""
             "- \"label\": a concise human title, e.g. \"Tone of voice checker\""
             (str "- \"category\": EXACTLY one of " (pr-str categories)
                  " — the closest fit to what the skill does (a skill that only reports fits Audits)")
             "- \"body\": a concise markdown playbook the agent will follow — the method, the order"
             "  to work in, what to check, and where to stop for review. Write it for the native"
             "  tools; no governance/naming boilerplate (the agent already carries those)."]))

(defn- answers->user-message
  [{:keys [what trigger reactive]}]
  (str/join "\n"
            ["Create a skill from this:"
             (str "- What it should do: " what)
             (str "- When it triggers / example phrase: " (or trigger "(none given)"))
             (str "- Reactive behavior: " reactive " (already chosen by the user; the body must respect it)")]))

(defn- generation-body
  "A bare, tool-free provider payload for one completion (mirrors agent/build-round-body
  minus tools and streaming)."
  [{:keys [provider model]} answers]
  (let [user (answers->user-message answers)]
    (js/JSON.stringify
     (clj->js
      (if (= provider "anthropic")
        {:model model
         :max_tokens 4000
         :system instructions
         :messages [{:role "user" :content user}]}
        (cond-> {:model model
                 :messages [{:role "system" :content instructions}
                            {:role "user" :content user}]}
          (= provider "openai")    (assoc :max_completion_tokens 4000)
          (not= provider "openai") (assoc :max_tokens 4000)))))))

;; --- Parse the provider response (pure)

(defn extract-text
  "The assistant text out of a buffered provider response body (a JSON string)."
  [provider body-str]
  (let [body (js->clj (js/JSON.parse body-str) :keywordize-keys true)]
    (if (= provider "anthropic")
      (->> (:content body)
           (filter #(= "text" (:type %)))
           (map :text)
           (str/join ""))
      (get-in body [:choices 0 :message :content]))))

(defn extract-json
  "The first `{ … }` object in `text`, tolerating code fences / surrounding prose."
  [text]
  (when (string? text)
    (let [t     (-> text (str/replace "```json" "") (str/replace "```" ""))
          start (str/index-of t "{")
          end   (str/last-index-of t "}")]
      (when (and start end (< start end))
        (subs t start (inc end))))))

(defn- slugify
  [s]
  (-> (str s) str/lower (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" "")))

(defn clamp-category
  "The model's category matched (case-insensitively) to an existing one; falls
  back to Audits when it doesn't recognizably match."
  [c]
  (let [c (str/lower (str c))]
    (or (some #(when (str/starts-with? (str/lower %) c) %) categories)
        (some #(when (str/starts-with? c (str/lower %)) %) categories)
        "Audits")))

(defn parse-generation
  "Pure: turn the model's reply `text` + the user's `answers` into a skill map
  ready for `:create-skill`, or nil if it isn't usable (retryable). The user's
  `reactive`, `trigger` and `what` stay authoritative."
  [{:keys [what trigger reactive] :as _answers} text]
  (when-let [json (extract-json text)]
    (let [doc  (try (js->clj (js/JSON.parse json) :keywordize-keys true)
                    (catch :default _ nil))
          body (some-> (:body doc) str str/trim not-empty)
          nm   (or (not-empty (slugify (:name doc)))
                   (not-empty (slugify (:label doc)))
                   (not-empty (slugify what)))]
      (when (and (map? doc) body nm)
        {:name nm
         :label (or (not-empty (:label doc)) "New skill")
         :category (clamp-category (:category doc))
         :reactive reactive
         :trigger (or (not-empty trigger) (not-empty (:trigger doc)))
         :description (or (not-empty what) (not-empty (:description doc)))
         :body body}))))

;; --- The call (impure)

(defn generate-skill
  "Runs one completion through the proxy for `settings` (provider+model) and
  returns an observable of the parsed skill map, or errors with a retryable
  ex-info when the model reply can't be parsed into a usable skill."
  [settings answers]
  (->> (rp/cmd! :ai-agent-round
                {:provider (:provider settings)
                 :payload (generation-body settings answers)})
       (rx/map (fn [{:keys [body]}]
                 (let [text (extract-text (:provider settings) body)]
                   (or (parse-generation answers text)
                       (throw (ex-info "The model didn't return a usable skill; try again."
                                       {:type :validation :code :skill-generation-failed}))))))))
