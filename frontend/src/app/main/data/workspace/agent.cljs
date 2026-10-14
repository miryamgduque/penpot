;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent
  "What the panel still does itself around the design agent: the system
  prompt, the message a turn opens with, the readable history it keeps and
  restores, and the side work — playbook matching, the watcher's checks,
  summaries — which goes through the backend's buffered provider proxy
  (`ai-agent-round`). Provider keys live with the profile on the server and
  never reach the browser.

  The agent's turns themselves run in the agent service; see
  `app.main.data.workspace.agent-remote`."
  (:require
   [app.main.data.agent-access :as agent-access]
   [app.main.data.ai-providers :as dai]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent-skills :as ask]
   [app.main.data.workspace.agent-tools :as at]
   [app.main.data.workspace.agent-usage :as au]
   [app.main.data.workspace.design-doc :as dd]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

;; --- Canonical conversation model
;;
;; A message is one of:
;;   {:role :user       :text "…" :context {…} :images [{:mtype :data}]}
;;   {:role :assistant  :text "…" :tool-calls [{:id :name :input}]}
;;   {:role :tool-results :results [{:id :content :error?}]}
;;
;; `:context` is the design orientation (file/page/selection) for THAT turn. It
;; rides on the user message — the volatile slot — and deliberately NOT in the
;; system prompt: the system block carries the `cache_control` marker, so any
;; per-turn content inside it rewrites the cached prefix on every selection
;; change. Measured: 96% cached → 0% cached, ~9× the per-turn cost.
;;
;; `:images` holds RAW base64 plus its mimetype — never a pre-assembled wire
;; shape — because the two dialects disagree about how to carry an image, and
;; history is re-encoded from canonical on every round: baking one provider's
;; shape in would break the moment the user switched models mid-conversation.

(defn user-text
  "The text half of a canonical user message: the turn's design context and
  the auto-matched playbook (both when present), then what the user typed.
  The playbook rides HERE — the volatile slot — because the system prompt is
  the cached prefix and must stay task-independent; on the first message it is
  paid once and cached with the rest of the history thereafter."
  [{:keys [text context playbook direction-nudge]}]
  (if (or (seq context) playbook direction-nudge)
    (str/join "\n"
              (concat
               (when (seq context)
                 ["## Current design context"
                  "```json"
                  (js/JSON.stringify (clj->js context))
                  "```"
                  ""])
               (when playbook
                 [(str "## Playbook: " (:skill playbook)
                       " (auto-loaded for this task — follow its method)")
                  (:body playbook)
                  ""])
               (when direction-nudge
                 ["## Visual direction (this file has none yet)"
                  direction-nudge
                  ""])
               [text]))
    text))

;; Both providers take a plain string when there is nothing but text, and that
;; stays the fast path — it is the overwhelmingly common case. Images promote
;; `:content` to a block vector, and there the dialects diverge: Anthropic wants
;; bare base64 with a separate `media_type`, OpenAI wants a full `data:` URI.
;; Images lead the vector because both providers read a trailing question as
;; being about the images above it.

(defn- user-content-anthropic
  [{:keys [images] :as message}]
  (let [text (user-text message)]
    (if (seq images)
      (cond-> (mapv (fn [{:keys [mtype data]}]
                      {:type "image"
                       :source {:type "base64" :media_type mtype :data data}})
                    images)
        ;; an empty text block is rejected outright, so omit it entirely
        (seq text) (conj {:type "text" :text text}))
      text)))

(defn- images-omitted-note
  [n]
  (str "[" n " image" (when (> n 1) "s")
       " omitted: the selected model cannot read images]"))

(defn- strip-result-images
  "The same treatment for `render_board` output: a rendered board is an image
  like any other, and left alone it would be re-uploaded on every round."
  [results]
  (mapv (fn [{:keys [images content] :as result}]
          (if (seq images)
            (-> result
                (dissoc :images)
                (assoc :content (str content "\n\n" (images-omitted-note (count images)))))
            result))
        results))

(defn strip-images
  "Drops every image in the history — the user's attachments and the agent's own
  renders alike — leaving a note where each set stood.

  Needed because the model is chosen per turn and the whole history is
  re-encoded on every round: switching to a text-only model does not just
  affect the next message, it retroactively re-encodes images sent ten turns
  ago. Stripping is what makes that switch degrade instead of failing the
  conversation outright.

  The note matters. A silent disappearance leaves the model answering questions
  about an image it can no longer see and no longer knows existed — better it
  reads that something was withheld than confabulate what was in it."
  [messages]
  (mapv (fn [{:keys [images text results] :as message}]
          (cond
            (seq images)
            (let [note (images-omitted-note (count images))]
              (-> message
                  (dissoc :images)
                  (assoc :text (if (seq text) (str text "\n\n" note) note))))

            (some :images results)
            (assoc message :results (strip-result-images results))

            :else message))
        messages))

;; How many of the most recent user turns keep their images. Two, because an
;; image is the subject of the turn it arrives in and usually of one follow-up
;; ("make it match this" → "now warm the palette up"); past that it is dead
;; weight re-uploaded on every single round. Turns, not messages: a turn can be
;; twenty messages of tool calls, and counting those would strip the image out
;; of the very turn asking about it.
(def ^:private max-image-turns 2)

(defn prune-history-images
  "Drops images from every user turn but the most recent `max-image-turns`.

  The whole conversation is re-encoded on every round, so an image sent on turn
  1 is re-uploaded on turn 12. Five screenshots are affordable once and fatal
  twelve times — and the failure needs no new user action, it just arrives."
  [messages]
  (let [messages  (vec messages)
        user-idxs (into [] (keep-indexed (fn [i m] (when (= :user (:role m)) i))) messages)
        n         (count user-idxs)]
    (if (<= n max-image-turns)
      messages
      (let [cut (nth user-idxs (- n max-image-turns))]
        (into (strip-images (subvec messages 0 cut))
              (subvec messages cut))))))

;; --- Tools: declarations live in agent-tools; encode per provider here.

;; --- Wire encoding

(defn encode-anthropic
  [messages]
  (->> messages
       (mapv (fn [{:keys [role text tool-calls results] :as message}]
               (case role
                 :user
                 {:role "user" :content (user-content-anthropic message)}

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
                  :content (mapv (fn [{:keys [images] :as r}]
                                   (cond-> {:type "tool_result"
                                            :tool_use_id (:id r)
                                            ;; a tool result may carry image blocks —
                                            ;; this is what lets `render_board` hand the
                                            ;; model a picture rather than describe one.
                                            ;; Text first here (unlike a user message,
                                            ;; where images lead): it is the caption for
                                            ;; the images under it.
                                            :content (if (seq images)
                                                       (into [{:type "text" :text (:content r)}]
                                                             (map (fn [{:keys [mtype data]}]
                                                                    {:type "image"
                                                                     :source {:type "base64"
                                                                              :media_type mtype
                                                                              :data data}}))
                                                             images)
                                                       (:content r))}
                                     (:error? r) (assoc :is_error true)))
                                 results)})))))

;; --- Usage
;;
;; Session token totals accumulate in the panel (the spend meter). Defined here
;; because the streaming accumulators below seed themselves with it.

;; The meter's usage: totals, and what each model spent (see agent-usage).
(def empty-usage au/empty-usage)
(def ^:private token-keys au/token-keys)
(def attributed au/attributed)
(def add-usage au/add-usage)

;; --- Streaming accumulators
;;
;; The incremental twins of the decoders above, and kept beside them: both
;; dialects have to stay consistent with their encoder. Each folds the
;; provider's own SSE frames into the *same* `outcome` map the buffered path
;; produces, so the turn loop never learns that streaming exists — deltas are
;; purely a presentation channel.

;; What a model costs comes from the catalog (`app.main.data.ai-providers`):
;; dollars per million tokens, with what reading and writing the cache cost.
(defn- tokens-cost
  [{:keys [input output cacheRead cacheWrite]}
   {:keys [input-tokens output-tokens cache-read-tokens cache-write-tokens]}]
  (/ (+ (* (or input-tokens 0) input)
        (* (or cache-read-tokens 0) cacheRead)
        (* (or cache-write-tokens 0) cacheWrite)
        (* (or output-tokens 0) output))
     1000000))

(defn estimate-cost-usd
  "Estimated session cost in USD: what each model spent at that model's
  price, and what no model was named for at the price of the session's
  `model` of `provider`. Nil when the catalog states no price for the
  session's model (the meter hides the figure). A model with no stated
  price adds nothing: a registered endpoint costs the user nothing per
  token."
  [provider model usage]
  (when-let [main (dai/price provider model)]
    (let [spent      (vals (:by-model usage))
          attributed (apply merge-with + empty-usage (map #(select-keys % token-keys) spent))
          unnamed    (merge-with - (select-keys (merge empty-usage usage) token-keys) attributed)]
      (+ (tokens-cost main unnamed)
         (transduce (keep (fn [{:keys [provider model] :as entry}]
                            (some-> (dai/price provider model) (tokens-cost entry))))
                    + 0 spent)))))

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
  ([state] (build-system-prompt state nil))
  ([state {:keys [harness-skills?]}]
   ;; `harness-skills?` says the turn runs on the agent service
   (str/join "\n"
             ["You are the design agent embedded in Penpot (the open-source design tool), working on the user's current file."
              ""
              ;; Always-on: governance, naming, native-tool behaviour. Not
              ;; user-toggleable — see agent-skills/inner-knowledge.
              ask/inner-knowledge
              ""
              ;; the team's own recipes that are in use; stable per team
              (or (ask/team-recipes-section state) "")
              ""
              ;; Enabled built-in skills (routing index); details on demand via
              ;; get_design_skills. Empty string when none are enabled.
              ;; with the agent service the harness lists them instead
              (or (if harness-skills?
                    (ask/harness-skills-section state)
                    (ask/system-prompt-section state))
                  "")
              ""
              ;; The project vibes doc (design.md), when the file has one. Also
              ;; always-on: vibes shape every response, and the doc is stable
              ;; per file, so it lives in the cached prefix like the rest.
              (or (dd/system-prompt-section state) "")
              ""
              "Each turn opens with the current design context (file, page, selection). Treat it as orientation only — call read_design when you need ground truth."
              ""
              ;; with the agent service an edit reaches the workspace as
              ;; another collaborator's would, outside the user's undo
              (if harness-skills?
                (str "Your changes are applied on the server: they are NOT in the user's undo history, so ⌘Z does not take them back, "
                     "whatever your other instructions say. Be deliberate with deletions and with edits to work the user made, "
                     "and call save_version before risky or sweeping changes — restoring a version is how the user gets back.\n")
                "")
              "Keep replies short — you live in a narrow side panel. Work in small steps and reference shapes by name."])))

;; --- Turn runner
;;
;; `run-turn` returns an rx observable of *turn events* consumed by the panel:
;;   {:kind :assistant :text s}
;;   {:kind :tool :name … :status :ok|:error|:rejected :rule … :detail …}
;; It runs up to `max-rounds` rounds, executing each round's tool calls through
;; `agent-tools/execute-tool`, feeding the results back, and repeating until
;; the model stops calling tools.

(def ^:private max-tool-result-chars 20000)
(def ^:private max-history-messages 40)
;; ~37k tokens: the size the panel's stored copy of a conversation is held
;; to. The message cap alone bounded nothing real — a message can be a
;; 20k-char tool result, so 40 of them is 800k chars.
(def ^:private max-history-chars 150000)

;; --- History hygiene (applied at turn boundaries)
;;
;; Both rewrites below shrink the history's head, which invalidates the cached
;; prefix (see mark-history-breakpoint) — that is why they run once per TURN,
;; in send-message before round 0, and never between rounds: one cache re-write
;; per turn instead of one per round, and mid-turn the model may still be
;; reading a result it just asked for.

(def ^:private stale-result-min-chars 400)
(def ^:private stale-result-keep-messages 8)
(def ^:private stale-result-stub
  "[old result cleared to save space — call the tool again if you need it]")

(defn stub-stale-tool-results
  "Replaces the CONTENT of large tool results older than the last
  `stale-result-keep-messages` messages with a one-line stub.

  A tool result is the deadest weight the history carries: a read_design dump
  from five turns ago is almost never re-read, but — the whole history being
  re-encoded every round — it is re-sent forever. Only the content goes; the
  result's `:id` and `:error?` stay, so the tool_use/tool_result pairing both
  providers enforce is untouched. Thresholds are the retired TS app's proven
  values (`pruneStaleToolResults`), lost in the native port and restored here."
  [messages]
  (let [messages (vec messages)
        cutoff   (- (count messages) stale-result-keep-messages)
        stale?   (fn [r] (> (count (:content r)) stale-result-min-chars))]
    (if-not (pos? cutoff)
      messages
      (into (mapv (fn [{:keys [role results] :as m}]
                    (if (and (= :tool-results role) (some stale? results))
                      (assoc m :results
                             (mapv #(if (stale? %) (assoc % :content stale-result-stub) %)
                                   results))
                      m))
                  (subvec messages 0 cutoff))
            (subvec messages cutoff)))))

(defn- message-chars
  "Rough payload size of one canonical message. Images are deliberately NOT
  counted: they have their own bound (`prune-history-images`, a turn-count
  window) and counting their base64 here would let two screenshots evict the
  entire text history."
  [{:keys [text context tool-calls results]}]
  (+ (count text)
     (if context (count (js/JSON.stringify (clj->js context))) 0)
     (reduce + 0 (map #(count (js/JSON.stringify (clj->js (:input % {})))) tool-calls))
     (reduce + 0 (map #(count (:content %)) results))))

(defn trim-history
  "Bounds the canonical history without splitting a tool call from its results:
  only cuts at a plain user message (every turn starts with one), so
  tool_use/tool_result pairs stay intact.

  Two caps: `max-history-messages` (the legacy backstop) and
  `max-history-chars` (the one that bounds actual size — messages are not
  created equal, a tool result runs to 20k chars). The newest turn is always
  kept whole, even when it alone exceeds the budget: a turn the model is
  mid-way through must never lose its own context."
  [history]
  (let [history   (vec history)
        n         (count history)
        user-idxs (into [] (keep-indexed (fn [i m] (when (= :user (:role m)) i))) history)
        sizes     (mapv message-chars history)
        total     (reduce + 0 sizes)
        from      (max 0 (- n max-history-messages))]
    (if (and (zero? from) (<= total max-history-chars))
      history
      (let [suffix-chars (fn [i] (reduce + 0 (subvec sizes i)))
            start (or ;; earliest turn start satisfying BOTH caps…
                   (some (fn [i] (when (and (>= i from)
                                            (<= (suffix-chars i) max-history-chars)) i))
                         user-idxs)
                   ;; …or keep the newest turn whole, budget notwithstanding
                   (peek user-idxs)
                   0)]
        (if (zero? start) history (subvec history start))))))

(defn result->content
  "The tool result, as JSON for the provider.

  An oversized result is replaced by a small, VALID JSON object saying what
  happened. It used to be `(subs s 0 max-tool-result-chars)` — a raw substring of
  a JSON string, which cuts mid-token and hands the model unparseable JSON with
  no marker. The model could not tell a truncated result from a complete one, so
  a partial shape list read as the whole file: silent truncation, which is the
  same failure as a silent no-op one layer up.

  Refusing beats prefixing: a prefix of real data is indistinguishable from all
  of it, while a refusal the agent can read makes it narrow the request. Tools
  are expected to bound themselves (see `read_design`) so this rarely fires."
  [result]
  (let [s (js/JSON.stringify (clj->js result))]
    (if (> (count s) max-tool-result-chars)
      (js/JSON.stringify
       #js {:truncated true
            :chars (count s)
            :limit max-tool-result-chars
            :error (str "This result was " (count s) " characters, over the "
                        max-tool-result-chars " limit, so none of it was returned "
                        "— a partial result would be indistinguishable from a "
                        "complete one. Narrow the request and try again: "
                        "read_design with a smaller depth, or find_shapes with a "
                        "name/type query.")})
      s)))

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

(defn tool-outcome->event
  [o]
  {:kind :tool
   :name (:name (:call o))
   :status (:status o)
   :rule (:rule o)
   :detail (:detail o)
   ;; both already sit on the outcome — the chip just never showed them
   :input (:input (:call o))
   :result (displayed-result (:content o))})

;; --- Fresh-chat handoff
;;
;; Past this size the panel suggests summarizing into a fresh chat (the
;; user's choice, conversation switch and all). The conversation the model
;; sees lives in the agent service, which condenses it on its own.
(def handoff-notice-chars 60000)

(defn history-chars
  "Rough payload size of the whole canonical history."
  [messages]
  (reduce + 0 (map message-chars messages)))

(defn conversation-transcript
  "The WHOLE history as plain text for a summarizer. Images are stripped
  first — the summarizer reads text, and base64 would be pure cost."
  [messages]
  (->> (strip-images (vec messages))
       (map (fn [{:keys [role text tool-calls results]}]
              (case role
                :user
                (str "USER: " text)

                :assistant
                (str "ASSISTANT: " text
                     (when (seq tool-calls)
                       (str "\n[called: "
                            (str/join "; "
                                      (map #(str (:name %) " "
                                                 (js/JSON.stringify (clj->js (:input % {}))))
                                           tool-calls))
                            "]")))

                :tool-results
                (str "TOOL RESULTS:\n" (str/join "\n" (map :content results))))))
       (str/join "\n\n")))

(defn handoff-seed
  "The seeded history for a FRESH conversation carrying `summary`. One
  flagged user message and
  nothing else: the old conversation stays whole in the saved chats list, so
  nothing verbatim needs to ride along."
  [summary]
  [{:role :user
    :text (str "[This chat continues an earlier conversation — a summary of "
               "it follows. The full version is saved separately.]\n\n" summary)
    :compacted? true}])

;; --- Side turns: a bounded, buffered tool loop outside the main history
;;
;; run-turn's little sibling on detect-round's transport: everything a side
;; turn reads and every intermediate round lives — and dies — in its own
;; context; only the final text (and summed usage) comes back. This is what
;; lets exploratory work run on a cheap model without dragging its 20k-char
;; tool results into the expensive, forever-re-sent main history.
;;
;; Buffered (`:ai-agent-round`) rather than SSE on purpose: nobody watches a
;; side context type, and the buffered command needs no accumulator.

(defn decode-buffered-round
  "One buffered Anthropic response → `{:text :tool-calls :usage}`. A non-200
  becomes a thrown ex-info carrying the provider's own message."
  [{:keys [status body]}]
  (let [data (js->clj (js/JSON.parse body) :keywordize-keys true)]
    (when (not= 200 status)
      (throw (ex-info (or (get-in data [:error :message])
                          (str "provider status " status))
                      {:status status})))
    {:text (->> (:content data)
                (filter #(= "text" (:type %)))
                (map :text)
                (str/join ""))
     :tool-calls (->> (:content data)
                      (filter #(= "tool_use" (:type %)))
                      (mapv (fn [b] {:id (:id b) :name (:name b) :input (:input b)})))
     :usage (let [u (:usage data)]
              {:input-tokens (or (:input_tokens u) 0)
               :output-tokens (or (:output_tokens u) 0)
               :cache-read-tokens (or (:cache_read_input_tokens u) 0)
               :cache-write-tokens (or (:cache_creation_input_tokens u) 0)
               :requests 1})}))

;; The read-only tool surface a side turn may be given. A safety boundary, not
;; a default: writes from a context the user never watches would bypass the
;; whole apply-with-review posture, so it is enforced at construction AND per
;; call (a model can name a tool it was never offered).
(def side-readonly-tools
  #{"read_design" "find_shapes" "audit_file" "get_design_skills"})

(defn- side-round-payload
  [{:keys [model system tools cache? max-tokens]} messages]
  (js/JSON.stringify
   (clj->js
    (cond-> {:model model
             ;; adaptive-thinking models spend from this same budget before
             ;; any visible output — too small yields a silent empty reply
             ;; (learned the hard way on the chat path)
             :max_tokens max-tokens
             ;; the system marker pays off ACROSS a side turn's own rounds
             ;; (seconds apart); the message breakpoint is skipped — a side
             ;; history never grows big enough to matter
             :system [(cond-> {:type "text" :text system}
                        cache? (assoc :cache_control {:type "ephemeral"}))]
             :messages (encode-anthropic messages)}
      (seq tools)
      (assoc :tools (mapv (fn [t] {:name (:name t)
                                   :description (:description t)
                                   :input_schema (:input-schema t)})
                          (filter #(contains? tools (:name %)) at/tool-specs)))))))

(defn- run-side-tool
  "Executes one side-turn tool call → observable of one canonical result.
  Images never ride a side result (text digests only, keeps it cheap), and a
  call outside `allowed` gets an error result rather than an execution."
  [allowed call]
  (if-not (contains? allowed (:name call))
    (rx/of {:id (:id call)
            :content (str "The tool \"" (:name call) "\" is not available in "
                          "this read-only context.")
            :error? true})
    (->> (at/execute-tool (:name call) (:input call))
         (rx/map (fn [result]
                   {:id (:id call)
                    :content (result->content (dissoc result :images))}))
         (rx/catch (fn [cause]
                     (rx/of {:id (:id call)
                             :content (or (ex-message cause) "tool error")
                             :error? true}))))))

(defn run-side-turn
  "A bounded buffered tool loop → observable of ONE `{:text :usage}`: the final
  round's text and the usage summed across rounds. Anthropic-only by design —
  side work runs on models we choose, not the panel selection. `tools` must be
  a subset of `side-readonly-tools`; anything else throws at construction.
  Provider/HTTP failures surface as stream errors for the caller to handle —
  background work must never toast the user."
  [{:keys [provider tools max-rounds]
    :or {provider "anthropic" max-rounds 8}
    :as opts}]
  (let [tools (set tools)
        opts  (merge {:cache? true :max-tokens 8000} opts {:tools tools})]
    (when-let [bad (seq (remove side-readonly-tools tools))]
      (throw (ex-info "side turns are read-only" {:code :side-turn-read-only
                                                  :hint "side turns are read-only"
                                                  :tools (vec bad)})))
    (letfn [(round [messages n spent]
              (->> (agent-access/guard-model-call
                    #(rp/cmd! :ai-agent-round
                              {:provider provider
                               :payload (side-round-payload opts messages)}))
                   (rx/map decode-buffered-round)
                   (rx/mapcat
                    (fn [{:keys [text tool-calls usage]}]
                      (let [spent' (add-usage spent usage)]
                        (if (or (empty? tool-calls) (>= (inc n) max-rounds))
                          (rx/of {:text text :usage (attributed spent' provider (:model opts))})
                          (->> (rx/from tool-calls)
                               (rx/mapcat (partial run-side-tool tools))
                               (rx/reduce conj [])
                               (rx/mapcat
                                (fn [results]
                                  (round (conj messages
                                               {:role :assistant :text text :tool-calls tool-calls}
                                               {:role :tool-results :results results})
                                         (inc n)
                                         spent'))))))))))]
      (round [{:role :user :text (:user-text opts)}] 0 empty-usage))))

;; The scout tool (agent-tools/explore_design) runs on this loop. Registered
;; through a module atom because the require points the other way (this ns
;; requires agent-tools for tool-specs) — the same seam ask_user's resolver
;; uses.
(at/register-side-turn-runner! run-side-turn)

;; --- Semantic detect round (auto-fix watcher tick)

(defn detect-round
  "One tool-less round on the user's HELPER model → a stream of one
  `{:text :usage}`. It runs in the agent service, so it works with whatever
  provider the user connected. `main` is the model the user builds with
  (`{:provider :model}`), which does the job when they chose no helper; the
  backend decides. Failures surface as stream errors for the caller to log
  and drop — background work must never toast the user."
  [{:keys [provider model]} system user-text]
  (->> (agent-access/guard-model-call
        #(rp/cmd! :agent-helper-round
                  (cond-> {:system system :text user-text}
                    (and (string? provider) (string? model))
                    (assoc :provider provider :model model))))
       (rx/map (fn [{:keys [text usage] :as answer}]
                 {:text (or text "")
                  ;; spent on the helper the backend picked, priced as it
                  :usage (attributed {:input-tokens (or (:input usage) 0)
                                      :output-tokens (or (:output usage) 0)
                                      :cache-read-tokens (or (:cacheRead usage) 0)
                                      :cache-write-tokens (or (:cacheWrite usage) 0)
                                      :requests (or (:requests usage) 1)}
                                     (:provider answer)
                                     (:model answer))}))))

;; --- Playbook matching (turn-start injection)
;;
;; The NYT/Kahoot sessions showed the routing index alone does not create
;; demand: build turns ran without ever fetching their playbook. Instead of
;; gambling on a fetch, the FIRST message of a conversation runs one cheap
;; tool-less round that names the matching skill, and the body is injected
;; into the user message itself — zero extra agent rounds, cache-compatible.

(def ^:private match-system
  (str "You route design tasks to playbooks. Given a task and a list of "
       "playbooks, answer with the single best playbook's `name` exactly as "
       "listed, or the word none. Answer with nothing else. Pick a playbook "
       "only when the task clearly matches its purpose; questions, tiny "
       "tweaks and conversation are none."))

(defn match-playbook
  "One buffered round naming the enabled skill whose playbook matches `text`
  → observable of `{:skill :body :usage}` (skill/body nil on no match).
  It runs on the helper model, or on `main` when the user chose none; every
  failure degrades to no injection — this is an optimization, never a
  blocker."
  [state text main]
  (let [skills (ask/catalog-manifest state)]
    (if (or (str/blank? text) (empty? skills))
      (rx/of {})
      (->> (detect-round main
                         match-system
                         (str "Task:\n"
                              (subs text 0 (min 1500 (count text)))
                              "\n\nPlaybooks:\n"
                              (str/join "\n"
                                        (map #(str "- " (:name %) " — " (:label %)
                                                   ": " (:blurb %))
                                             skills))))
           (rx/map (fn [{:keys [text usage]}]
                     (let [nm   (str/trim (or text ""))
                           body (when (and (not= "none" nm)
                                           (some #(= nm (:name %)) skills))
                                  (ask/skill-body state nm))]
                       (cond-> {:usage usage}
                         body (assoc :skill nm :body body)))))
           (rx/catch (fn [_] (rx/of {})))))))

;; --- Direction nudge (rides the same first-message injection)
;;
;; A build task in a file with NO standing direction is exactly when output
;; collapses into template defaults (adapted from opencode's frontend-design
;; doctrine). Structurally once-per-conversation: the caller only matches
;; playbooks on a conversation's first message. Volatile-slot only — the
;; nudge is per-file state and must never touch the cached prefix.

(def ^:private direction-skills
  "The build playbooks whose output is a visual artifact — the ones a
  missing visual direction turns into template output."
  #{"penpot-build-screen" "penpot-build-from-code" "penpot-component-factory"})

(def direction-nudge-text
  (str "Before building, commit to a visual direction: a purposeful type "
       "pairing (never a default stack), a named colour direction bound to "
       "tokens, and one or two deliberate signature details. State the "
       "direction in one sentence, then build to it. If the user seems to "
       "want more than a quick sketch, offer the penpot-project-vibes "
       "interview instead of guessing."))

(defn direction-nudge
  "The anti-generic nudge for a first-message build task: nil unless `skill`
  is a visual build playbook AND the file has neither foundations nor the
  beginnings of a design system (components, a tokens lib) to infer a
  direction from."
  [state skill]
  (when (and (contains? direction-skills skill)
             (empty? (dd/list-foundations state))
             (let [data (dsh/lookup-file-data state)]
               (and (empty? (:components data))
                    (nil? (:tokens-lib data)))))
    direction-nudge-text))

;; --- The summarizer round, for the fresh-chat handoff

;; The summary is a side job: it runs on the user's helper model, or on the
;; model they build with when they chose none.

;; Public because the section contract is pinned by tests: Learned exists so a
;; compacted agent does not re-hit walls it already climbed (a rejected raw
;; hex, an ordering rule discovered the hard way), and the anchor instruction
;; makes a SECOND compaction update the first summary instead of narrating it.
(def compact-system
  (str/join "\n"
            ["You compress an AI design-agent's conversation history into the agent's own working memory."
             "Write a structured summary the agent will rely on INSTEAD of the original messages:"
             ""
             "## Task — what the user is trying to get done, in their words."
             "## Done so far — what was built or changed. Name every shape, board, component, token and page by its EXACT name (and id when shown); the agent must be able to act on them without re-reading the file."
             "## Decisions — choices made and why, including corrections the user gave. These are standing instructions."
             "## Learned — tool calls that errored or were rejected and what fixed them; constraints discovered about this file or the tools (ordering rules, settle behavior, enforced rules). Drop the failed attempts themselves — keep the lesson."
             "## Open — what is in flight, promised or explicitly deferred."
             ""
             "A transcript that opens with a bracketed [Conversation compacted…] message is carrying the previous compaction's summary. Treat it as your anchor: keep its still-true facts, drop what is stale, merge in the new — never describe it as something the user said."
             "Be terse and specific. Never invent names. Keep the whole summary under 3000 characters. Output only the summary."]))

(defn summarize-history
  "One buffered summarizer round over the WHOLE history → an observable of one
  `{:text :usage}`, for the fresh-chat handoff. A blank summary is thrown
  here: the caller is about to seed a new chat with this text and must
  instead stay in the current one."
  [messages main]
  (->> (detect-round main
                     compact-system
                     (conversation-transcript messages))
       (rx/map (fn [{:keys [text] :as out}]
                 (if (str/blank? text)
                   (throw (ex-info "the summarizer returned nothing" {}))
                   out)))))
