;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent
  "The Agents chat model, routed through the Penpot backend proxy
  (`::sse/ai-agent-round-stream`) — provider keys live with the profile on the
  server and never reach the browser. The proxy is a dumb pipe: it forwards our
  payload verbatim and re-emits the provider's own SSE frames, so both wire
  dialects are decoded here. This is the native CLJS port of
  `ai-skills/src/ui/agent.ts`.

  The conversation history is kept in ONE canonical form and re-encoded into
  the wire form of whichever provider is currently selected (Anthropic
  Messages for Claude models; OpenAI chat.completions for OpenAI-compatible
  providers). That is what lets a user switch models — even across providers —
  mid-conversation and carry the whole history."
  (:require
   [app.main.data.workspace.agent-skills :as ask]
   [app.main.data.workspace.agent-tools :as at]
   [app.main.repo :as rp]
   [app.util.sse :as sse]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

;; --- Canonical conversation model
;;
;; A message is one of:
;;   {:role :user       :text "…" :context {…}}
;;   {:role :assistant  :text "…" :tool-calls [{:id :name :input}]}
;;   {:role :tool-results :results [{:id :content :error?}]}
;;
;; `:context` is the design orientation (file/page/selection) for THAT turn. It
;; rides on the user message — the volatile slot — and deliberately NOT in the
;; system prompt: the system block carries the `cache_control` marker, so any
;; per-turn content inside it rewrites the cached prefix on every selection
;; change. Measured: 96% cached → 0% cached, ~9× the per-turn cost.

(defn- user-content
  "Renders a canonical user message for the wire: the turn's design context (when
  present) followed by what the user typed. Both providers take a plain string
  here, so one renderer serves both."
  [{:keys [text context]}]
  (if (seq context)
    (str/join "\n" ["## Current design context"
                    "```json"
                    (js/JSON.stringify (clj->js context))
                    "```"
                    ""
                    text])
    text))

(defn- anthropic?
  "Providers that speak the Anthropic Messages API; the rest are
  OpenAI-compatible (chat.completions)."
  [provider]
  (= provider "anthropic"))

;; --- Tools: declarations live in agent-tools; encode per provider here.

(defn- anthropic-tools
  []
  (mapv (fn [t] {:name (:name t)
                 :description (:description t)
                 :input_schema (:input-schema t)})
        at/tool-specs))

(defn- openai-tools
  []
  (mapv (fn [t] {:type "function"
                 :function {:name (:name t)
                            :description (:description t)
                            :parameters (:input-schema t)}})
        at/tool-specs))

;; --- Wire encoding

(defn encode-anthropic
  [messages]
  (->> messages
       (mapv (fn [{:keys [role text tool-calls results] :as message}]
               (case role
                 :user
                 {:role "user" :content (user-content message)}

                 :assistant
                 (let [blocks (cond-> []
                                (seq text)
                                (conj {:type "text" :text text})
                                :always
                                (into (map (fn [c]
                                             {:type "tool_use"
                                              :id (:id c)
                                              :name (:name c)
                                              :input (:input c)}))
                                      tool-calls))]
                   ;; an assistant message must carry at least one block
                   {:role "assistant"
                    :content (if (seq blocks) blocks [{:type "text" :text "…"}])})

                 :tool-results
                 {:role "user"
                  :content (mapv (fn [r]
                                   (cond-> {:type "tool_result"
                                            :tool_use_id (:id r)
                                            :content (:content r)}
                                     (:error? r) (assoc :is_error true)))
                                 results)})))))

(defn encode-openai
  [system messages]
  (into [{:role "system" :content system}]
        (mapcat (fn [{:keys [role text tool-calls results] :as message}]
                  (case role
                    :user
                    [{:role "user" :content (user-content message)}]

                    :assistant
                    [(cond-> {:role "assistant" :content (when (seq text) text)}
                       (seq tool-calls)
                       (assoc :tool_calls
                              (mapv (fn [c]
                                      {:id (:id c)
                                       :type "function"
                                       :function {:name (:name c)
                                                  :arguments (js/JSON.stringify
                                                              (clj->js (or (:input c) {})))}})
                                    tool-calls)))]

                    :tool-results
                    (mapv (fn [r]
                            {:role "tool" :tool_call_id (:id r) :content (:content r)})
                          results))))
        messages))

(defn- build-round-body
  "`stream?` sets the provider's own stream flag. It is set here, not by the
  proxy: the backend forwards the payload byte-for-byte and never decodes it,
  and OpenAI's usage opt-in is dialect-specific anyway."
  [{:keys [provider model]} messages system stream?]
  (js/JSON.stringify
   (clj->js
    (if (anthropic? provider)
      (cond-> {:model model
               ;; generous budget: adaptive-thinking models can spend a chunk
               ;; reasoning before any visible output; a small budget yields a
               ;; silent empty reply
               :max_tokens 32000
               ;; the ephemeral marker caches tools+system across rounds
               :system [{:type "text" :text system
                         :cache_control {:type "ephemeral"}}]
               :messages (encode-anthropic messages)}
        (seq at/tool-specs) (assoc :tools (anthropic-tools))
        stream?             (assoc :stream true))
      (cond-> {:model model
               :messages (encode-openai system messages)}
        (seq at/tool-specs)      (assoc :tools (openai-tools))
        (= provider "openai")    (assoc :max_completion_tokens 16000)
        (not= provider "openai") (assoc :max_tokens 16000)
        stream?                  (assoc :stream true)
        ;; without this opt-in the final chunk carries no usage at all and the
        ;; spend meter silently reads zero
        stream?                  (assoc :stream_options {:include_usage true}))))))

;; --- Usage
;;
;; Session token totals accumulate in the panel (the spend meter). Defined here
;; because the streaming accumulators below seed themselves with it.

(def empty-usage
  {:input-tokens 0 :output-tokens 0 :cache-read-tokens 0 :cache-write-tokens 0 :requests 0})

(defn add-usage
  "Merges a round's usage into the running totals."
  [a b]
  (merge-with + (or a empty-usage) (or b empty-usage)))

;; --- Streaming accumulators
;;
;; The incremental twins of the decoders above, and kept beside them: both
;; dialects have to stay consistent with their encoder. Each folds the
;; provider's own SSE frames into the *same* `outcome` map the buffered path
;; produces, so the turn loop never learns that streaming exists — deltas are
;; purely a presentation channel.

(def empty-accumulator
  {:text "" :tools (sorted-map) :stop-reason nil :usage empty-usage})

(defn- parse-json-frame
  "A provider `data:` payload. OpenAI's terminator `[DONE]` is not JSON."
  [s]
  (when (and (string? s) (not= "[DONE]" (str/trim s)))
    (try
      (js->clj (js/JSON.parse s) :keywordize-keys true)
      (catch :default _ nil))))

(defn- decode-tool-json
  "A tool call with no arguments streams zero deltas, so the accumulated JSON
  is empty — mirror the buffered decoder and default to no input."
  [s]
  (if (str/blank? s)
    {}
    (try
      (js->clj (js/JSON.parse s) :keywordize-keys true)
      (catch :default _ {}))))

(defn accumulate-anthropic
  "Folds one Anthropic stream frame into `acc`. Returns `[acc text-delta]`."
  [acc frame]
  (case (:type frame)
    "message_start"
    (let [usage (get-in frame [:message :usage])]
      [(assoc acc :usage {:input-tokens (or (:input_tokens usage) 0)
                          :output-tokens (or (:output_tokens usage) 0)
                          :cache-read-tokens (or (:cache_read_input_tokens usage) 0)
                          :cache-write-tokens (or (:cache_creation_input_tokens usage) 0)
                          :requests 1})
       nil])

    "content_block_start"
    (let [block (:content_block frame)]
      [(if (= "tool_use" (:type block))
         (assoc-in acc [:tools (:index frame)]
                   {:id (:id block) :name (:name block) :json ""})
         acc)
       nil])

    "content_block_delta"
    (let [delta (:delta frame)]
      (case (:type delta)
        "text_delta"
        (let [text (or (:text delta) "")]
          [(update acc :text str text) text])

        "input_json_delta"
        [(update-in acc [:tools (:index frame) :json] str (or (:partial_json delta) "")) nil]

        [acc nil]))

    ;; the only frame carrying the real output count — usage spans two events
    "message_delta"
    [(-> acc
         (assoc :stop-reason (get-in frame [:delta :stop_reason]))
         (assoc-in [:usage :output-tokens] (or (get-in frame [:usage :output_tokens]) 0)))
     nil]

    [acc nil]))

(defn accumulate-openai
  "Folds one OpenAI-dialect stream chunk into `acc`. Returns `[acc text-delta]`."
  [acc frame]
  (let [choice (get-in frame [:choices 0])
        delta  (:delta choice)
        usage  (:usage frame)
        acc    (cond-> acc
                 (some? usage)
                 (assoc :usage {:input-tokens (or (:prompt_tokens usage) 0)
                                :output-tokens (or (:completion_tokens usage) 0)
                                :cache-read-tokens (or (get-in usage [:prompt_tokens_details :cached_tokens]) 0)
                                :cache-write-tokens 0
                                :requests 1})

                 (some? (:finish_reason choice))
                 (assoc :stop-reason (:finish_reason choice)))
        ;; id/name arrive only on the first fragment per index; the index is
        ;; the tool-call's, not the choice's
        acc    (reduce (fn [acc call]
                         (let [idx (:index call)]
                           (cond-> acc
                             (:id call)   (assoc-in [:tools idx :id] (:id call))
                             (get-in call [:function :name])
                             (assoc-in [:tools idx :name] (get-in call [:function :name]))
                             :always
                             (update-in [:tools idx :json] str
                                        (or (get-in call [:function :arguments]) "")))))
                       acc
                       (:tool_calls delta))
        text   (:content delta)]
    (if (and (string? text) (seq text))
      [(update acc :text str text) text]
      [acc nil])))

(defn accumulator->outcome
  "The same shape the buffered decoders return, so the turn loop is unchanged."
  [{:keys [text tools stop-reason usage]} anthropic?]
  {:text text
   :tool-calls (mapv (fn [[_ t]]
                       {:id (:id t) :name (:name t) :input (decode-tool-json (:json t))})
                     tools)
   :stopped-for-length? (= (if anthropic? "max_tokens" "length") stop-reason)
   :usage usage})

;; $ per million tokens (standard list price; cache read ≈ 0.1×, write ≈ 1.25×).
(def ^:private pricing
  {"claude-sonnet-5" {:input 3 :output 15}
   "claude-opus-4-8" {:input 5 :output 25}
   "claude-haiku-4-5-20251001" {:input 1 :output 5}})

(defn estimate-cost-usd
  "Estimated session cost in USD for Claude models; nil for anything unpriced
  (the meter hides the figure)."
  [model {:keys [input-tokens output-tokens cache-read-tokens cache-write-tokens]}]
  (when-let [{:keys [input output]} (get pricing model)]
    (/ (+ (* input-tokens input)
          (* cache-read-tokens input 0.1)
          (* cache-write-tokens input 1.25)
          (* output-tokens output))
       1000000)))

;; --- System prompt
;;
;; STABLE CONTENT ONLY. This is the cached prefix (the Anthropic `system` block
;; carries the `cache_control` marker, and tools render ahead of it), so every
;; byte here is re-read at ~0.1× on a hit — and any per-turn value would rewrite
;; the whole prefix at 1.25× instead. The turn's design context therefore lives
;; on the user message; see `user-content`.

(defn build-system-prompt
  ;; `state` for the per-user enabled-skills index (US #8); `context` is
  ;; deliberately NOT here — it is volatile and would break the cached prefix,
  ;; so it rides on the user message instead (US #26).
  [state]
  (str/join "\n"
            ["You are the design agent embedded in Penpot (the open-source design tool), working on the user's current file."
             ""
             ;; Always-on: governance, naming, native-tool behaviour. Not
             ;; user-toggleable — see agent-skills/inner-knowledge.
             ask/inner-knowledge
             ""
             ;; Enabled built-in skills (routing index); details on demand via
             ;; get_design_skills. Empty string when none are enabled.
             (or (ask/system-prompt-section state) "")
             ""
             "Each turn opens with the current design context (file, page, selection). Treat it as orientation only — call read_design when you need ground truth."
             ""
             "Keep replies short — you live in a narrow side panel. Work in small steps and reference shapes by name."]))

;; --- Turn runner
;;
;; `run-turn` returns an rx observable of *turn events* consumed by the panel:
;;   {:kind :assistant :text s}
;;   {:kind :tool :name … :status :ok|:error|:rejected :rule … :detail …}
;; It runs up to `max-rounds` rounds, executing each round's tool calls through
;; `agent-tools/execute-tool`, feeding the results back, and repeating until
;; the model stops calling tools.

(def ^:private max-rounds 32)
(def ^:private max-tool-result-chars 20000)
(def ^:private max-history-messages 40)

(defn trim-history
  "Bounds the canonical history without splitting a tool call from its results:
  only cuts at a plain user message (every turn starts with one), so
  tool_use/tool_result pairs stay intact."
  [history]
  (let [history (vec history)
        n       (count history)]
    (if (<= n max-history-messages)
      history
      (let [turn-start? (fn [m] (= :user (:role m)))
            from        (- n max-history-messages)]
        (or (some (fn [i] (when (turn-start? (nth history i)) (subvec history i)))
                  (range from n))
            (some (fn [i] (when (turn-start? (nth history i)) (subvec history i)))
                  (range (dec n) -1 -1))
            history)))))

(defn- empty-reply-text
  [outcome]
  (if (:stopped-for-length? outcome)
    "⚠️ I ran out of output budget before finishing — please send the request again."
    "⚠️ The model returned no visible output — try rephrasing the request."))

(defn- result->content
  [result]
  (let [s (js/JSON.stringify (clj->js result))]
    (if (> (count s) max-tool-result-chars)
      (subs s 0 max-tool-result-chars)
      s)))

(defn- tool-outcome->result
  [o]
  {:id (:id (:call o)) :content (:content o) :error? (:error? o)})

(defn- cancelled-result
  [call]
  {:id (:id call)
   :content "Cancelled by the user before this tool ran."
   :error? true})

(defn cancel-history
  "Closes any tool call left unanswered by a cancelled turn.

  Both providers reject a request whose assistant message makes a tool call
  with no matching result in the next message — Anthropic with \"tool_use ids
  were found without tool_result blocks\", OpenAI with an unmatched
  tool_call_id. Synthesizing an errored result is the documented remedy, and
  doing it here (on the canonical history) covers both wire formats at once.

  Without this, the *next* turn 400s and nothing points back at the cancel."
  [messages]
  (let [messages (vec messages)
        last-msg (peek messages)]
    (if-let [calls (and (= :assistant (:role last-msg))
                        (seq (:tool-calls last-msg)))]
      (conj messages {:role :tool-results :results (mapv cancelled-result calls)})
      messages)))

;; What the transcript keeps for an expanded tool row. The canonical history
;; already holds the full result (up to `max-tool-result-chars`, 20k, per call
;; — 32 rounds of which is a lot of state to duplicate), and this copy is a UI
;; artifact, not the wire format.
(def ^:private max-displayed-result-chars 2000)

(defn- displayed-result
  [content]
  (when (string? content)
    (if (> (count content) max-displayed-result-chars)
      (subs content 0 max-displayed-result-chars)
      content)))

(defn- tool-outcome->event
  [o]
  {:kind :tool
   :name (:name (:call o))
   :status (:status o)
   :rule (:rule o)
   :detail (:detail o)
   ;; both already sit on the outcome — the chip just never showed them
   :input (:input (:call o))
   :result (displayed-result (:content o))})

(defn- stream-round
  "One provider round over SSE. Emits `{:kind :assistant-delta}` as text
  arrives, then exactly one `{:kind :outcome}` carrying the same map the
  buffered decoders return.

  The accumulator is a local atom rather than an `rx/scan`: the fold has to
  emit text deltas *and* survive to the end of the stream, and this keeps the
  round's state private to one subscription."
  [settings messages system]
  (let [anthropic?* (anthropic? (:provider settings))
        acc*        (atom empty-accumulator)]
    (->> (rp/cmd! ::sse/ai-agent-round-stream
                  {:provider (:provider settings)
                   :payload (build-round-body settings messages system true)})
         (rx/mapcat
          (fn [event]
            (case (sse/get-type event)
              ;; the backend taps `:delta` per provider `data:` line; a tapped
              ;; `:error` is turned into a thrown ex-info by `sse/read-stream`
              ;; before it reaches us
              "delta"
              (if-let [frame (parse-json-frame (sse/get-payload event))]
                (let [[acc text] (if anthropic?*
                                   (accumulate-anthropic @acc* frame)
                                   (accumulate-openai @acc* frame))]
                  (reset! acc* acc)
                  (if (seq text)
                    (rx/of {:kind :assistant-delta :text text})
                    (rx/empty)))
                (rx/empty))

              "end"
              (rx/of {:kind :outcome :outcome (accumulator->outcome @acc* anthropic?*)})

              (rx/empty)))))))

(defn run-turn
  [settings history system]
  (letfn [(run-tool [call]
            ;; → observable of one {:call :status :content :error? :rule :detail}
            (->> (at/execute-tool (:name call) (:input call))
                 (rx/map (fn [result]
                           {:call call :status :ok :content (result->content result)}))
                 (rx/catch (fn [cause]
                             (let [rule (:rule (ex-data cause))]
                               (rx/of {:call call
                                       :status (if rule :rejected :error)
                                       :rule rule
                                       :detail (ex-message cause)
                                       :content (or (ex-message cause) "tool error")
                                       :error? true}))))))

          (tool-round [messages' round _text calls]
            ;; no text emission: this round's text already reached the panel as
            ;; deltas. Run the tools and feed the results into the next round.
            (rx/concat
             (->> (rx/from calls)
                  (rx/mapcat run-tool)
                  (rx/reduce conj [])
                  (rx/mapcat
                   (fn [outcomes]
                     (rx/concat
                      (rx/from (mapv tool-outcome->event outcomes))
                      (step (conj messages' {:role :tool-results
                                             :results (mapv tool-outcome->result outcomes)})
                            (inc round))))))))

          (step [messages round]
            (if (>= round max-rounds)
              (rx/empty)
              (->> (stream-round settings messages system)
                   (rx/mapcat
                    (fn [ev]
                      ;; deltas flow straight through to the panel; the single
                      ;; :outcome drives the loop exactly as the decoded
                      ;; response used to
                      (if (not= :outcome (:kind ev))
                        (rx/of ev)
                        (let [outcome   (:outcome ev)
                              text      (:text outcome)
                              calls     (:tool-calls outcome)
                              messages' (conj messages {:role :assistant
                                                        :text text
                                                        :tool-calls calls})]
                          (rx/concat
                           (rx/of {:kind :usage :usage (:usage outcome)}
                                  ;; A cancel unsubscribes this stream from the
                                  ;; outside, so the loop never learns it was
                                  ;; stopped. Publishing the history as it grows
                                  ;; is what lets the caller close the turn off.
                                  {:kind :turn-history :history messages'})
                           (cond
                             (and (empty? text) (empty? calls))
                             ;; nothing streamed, so there is no bubble to seal
                             (rx/of {:kind :assistant :text (empty-reply-text outcome)}
                                    {:kind :done :history (trim-history messages')})

                             ;; text already streamed — nothing left to render
                             (empty? calls)
                             (rx/of {:kind :done :history (trim-history messages')})

                             :else
                             (tool-round messages' round text calls))))))))))]
    (step (vec history) 0)))
