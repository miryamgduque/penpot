;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.session-review
  "Hand a recorded session to a model and get a critique back.

  Phase 09 — the payoff. Everything before this captured what happened; this is
  the part that says whether it was any good.

  ## Read-only by construction, not by policy

  The review runs as a **tool-less** side turn (`agent/run-side-turn` with
  `:tools nil`, the same shape `detect-round` uses). That is a stronger guarantee
  than an allowlist: a reviewing agent with mutation tools would inevitably
  \"helpfully\" fix what it criticizes, corrupting the very file under review and
  making the critique unfalsifiable. With no tools at all it structurally cannot.

  The timeline is the evidence, so no tools are needed to read the file either.

  ## Budget

  A long session's timeline can be large, and the RPC caps `:payload` at 4M
  chars. `windowed-text` keeps the newest events within a char budget and, when
  it drops any, **says so in the prompt** — a critique of a truncated record
  presented as complete would be exactly the failure this feature exists to catch
  in humans.

  ## What it asks for

  Specific, evidence-cited observations tied to timestamps, not generic design
  advice. \"At +4:12 the same shape moved six times\" is worth reading; \"consider
  using a grid\" is not, and a model will happily produce the latter unless told
  otherwise."
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.agent :as agent]
   [app.main.data.workspace.session-events :as se]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(def max-timeline-chars
  "Char budget for the rendered timeline inside the prompt. Comfortably under the
  4M payload cap with room for the system prompt; the point is to keep a review
  cheap and focused, not to squeeze in every event of a three-hour session."
  60000)

(def review-model
  "Reviewing is judgement work, not routing, so it runs on a strong model rather
  than the cheap one used for ticks and digests."
  "claude-opus-4-8")

(def review-system
  ;; public because its instructions ARE the contract — the "cite evidence" and
  ;; "no generic advice" rules are what make a critique worth reading, and tests
  ;; pin them
  (str "You are reviewing a recorded design session in Penpot: a timeline of "
       "every layout interaction, by every participant, in order.\n\n"
       "Each line is `+<elapsed> <actor>: <what happened>`. The actor is either "
       "`user` or `agent <model>`. Agent lines were produced by an AI assistant "
       "acting on a person's behalf; user lines are a person editing directly.\n\n"
       "Write a short critique with three sections:\n"
       "**What went well** — concrete things worth repeating.\n"
       "**What went badly** — waste, churn, rework, thrash.\n"
       "**What to improve** — specific, actionable changes.\n\n"
       "Rules:\n"
       "- Cite evidence. Reference the elapsed times of the lines you mean.\n"
       "- Be specific to THIS session. Generic design advice ('consider a grid "
       "system') is worthless here; if you have nothing specific to say for a "
       "section, say so briefly.\n"
       "- Look for process signals: repeated edits to one shape, long runs of "
       "single-item work that could have been batched, undo/redo churn, an agent "
       "and a person fighting over the same shapes.\n"
       "- Do not speculate about intent you cannot see. The timeline records "
       "actions, not reasons.\n"
       "- Keep it under 400 words."))

(defn windowed-text
  "Render a timeline for the prompt, keeping the NEWEST events within the char
  budget. Returns `{:text … :dropped n}` so the caller can disclose truncation
  rather than pass a partial record off as whole."
  ([events] (windowed-text events max-timeline-chars))
  ([events budget]
   (loop [kept (vec events)
          dropped 0]
     (let [text (se/timeline->prompt-text kept)]
       (cond
         (<= (count text) budget) {:text text :dropped dropped}
         (<= (count kept) 1)      {:text text :dropped dropped}
         :else                    (recur (subvec kept 1) (inc dropped)))))))

(defn build-prompt
  "The user message for a review: the timeline, plus what was dropped if
  anything, plus the session's own shape (participants, how it ended).

  `budget` is injectable so truncation can be exercised without generating the
  thousands of events it would otherwise take to breach the real ceiling."
  ([session] (build-prompt session max-timeline-chars))
  ([session budget]
   (let [events   (vec (:events session []))
         {:keys [text dropped]} (windowed-text events budget)
         actors   (into #{} (map (fn [e]
                                   (if (= :agent (:who e))
                                     (str "agent " (:model e))
                                     "a person")))
                        events)
         reason   (some-> (:stop-reason session) name)]
     (str "Session timeline (" (count events) " events"
          (when (pos? dropped)
            (str ", OLDEST " dropped " OMITTED to fit the budget — your review "
                 "covers only the part shown, and you should say so"))
          "):\n\n"
          text
          "\n\nParticipants: " (str/join ", " (sort actors))
          (when reason
            (str "\nHow the recording ended: " reason
                 (case reason
                   "event-cap" " (hit the event ceiling, so the session may have continued beyond this record)"
                   "time-cap"  " (hit the time ceiling, so the session may have continued beyond this record)"
                   "file-closed" " (the file was closed)"
                   "")))
          (when (pos? (:raw-dropped session 0))
            (str "\nNote: " (:raw-dropped session)
                 " raw operations were dropped by a client-side buffer; the "
                 "timeline itself is intact."))
          "\n\nReview this session."))))

;; --- the turn

(defn- review-stored
  [session-id review]
  (ptk/reify ::review-stored
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:design-session-review session-id] review))))

(defn review-session
  "Run a critique of one recorded session.

  `on-event` receives `[:note text]` / `[:review text]` / `[:usage usage]` /
  `[:error code]` so the caller decides how to surface it — the panel appends to
  its transcript and meters the spend, and a test can just collect."
  [session on-event]
  (ptk/reify ::review-session
    ptk/WatchEvent
    (watch [_ state _]
      (let [events (:events session [])]
        (if (empty? events)
          (do (on-event [:note "Nothing to review — that recording captured no interactions."])
              (rx/empty))
          (let [provider (dm/get-in state [:ai-panel :review-provider] "anthropic")]
            (on-event [:note (dm/str "✦ Reviewing " (count events) " recorded events…")])
            (->> (agent/run-side-turn
                  {:provider provider
                   :model review-model
                   :system review-system
                   :user-text (build-prompt session)
                   ;; NO TOOLS: a reviewer that can mutate would fix what it
                   ;; criticizes and make its own critique unfalsifiable
                   :tools nil
                   :max-rounds 1
                   :max-tokens 2000
                   :cache? false})
                 (rx/mapcat
                  (fn [{:keys [text usage]}]
                    (when usage (on-event [:usage usage]))
                    (if (str/blank? text)
                      (do (on-event [:error :empty-review]) (rx/empty))
                      (do (on-event [:review text])
                          ;; store it so it can be re-read without re-spending
                          (->> (rp/cmd! :set-design-session-review
                                        {:id (:id session) :review text})
                               (rx/map (fn [_] (review-stored (:id session) text)))
                               (rx/catch (fn [_] (rx/empty))))))))
                 (rx/catch
                  (fn [cause]
                    (on-event [:error (or (:code (ex-data cause)) :review-failed)])
                    (rx/empty))))))))))
