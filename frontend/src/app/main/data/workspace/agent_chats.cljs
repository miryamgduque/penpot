;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-chats
  "Durable, per-file agent conversations (see app.rpc.commands.agent-chats).

  The live conversation stays where it always was — `[:ai-panel <file-id>]`
  `:messages`/`:history`/`:usage` — and this namespace moves it to/from the
  backend at turn boundaries. Additions to the per-file slot:

    :chat-id  the active conversation's row id (nil until first saved)
    :chats    this file's conversation list, metadata only, newest first

  Saves happen only between turns (`send-message` emits `persist-chat` where
  it already stores the final history, completed or cancelled), so a row is
  always a consistent snapshot: tool_use/tool_result pairs intact, never a
  half-streamed round. Empty conversations are never persisted.

  Images are stripped before every save — the user's attachments and the
  agent's rendered boards alike — keeping the composer's promise that image
  bytes only ever transit the proxy and are stored nowhere. Restored
  transcripts show a note where each image stood rather than silently losing
  them."
  (:require
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.agent :as agent]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; --- Pure helpers

(def ^:private max-title-chars 60)

(defn derive-title
  "The conversation's list label: its first user message, whitespace collapsed,
  capped at 60 chars on a word boundary where possible. An image-only first
  message has no text, hence the fallback."
  [messages]
  (let [text (some->> messages
                      (filter #(= "user" (:role %)))
                      (first)
                      (:content)
                      (str/trim))]
    (if (str/blank? text)
      "Untitled chat"
      (let [text (str/replace text #"\s+" " ")]
        (if (<= (count text) max-title-chars)
          text
          (str (subs text 0 max-title-chars) "…"))))))

(defn strip-message-images
  "Drops attachment thumbnails from the rendered transcript, leaving a note in
  the bubble — the transcript must not pretend the image was never sent."
  [messages]
  (mapv (fn [{:keys [images content] :as message}]
          (if (seq images)
            (-> message
                (dissoc :images)
                (assoc :content
                       (let [note (dm/str "[" (count images)
                                          " image" (when (> (count images) 1) "s")
                                          " not kept in saved history]")]
                         (if (str/blank? content)
                           note
                           (dm/str content "\n\n" note)))))
            message))
        messages))

(defn chat-payload
  "The `:data` map persisted for one conversation. History images are stripped
  with the agent's own helper (which leaves its omission notes), transcript
  images with ours."
  [{:keys [messages history usage]}]
  {:messages (strip-message-images (vec messages))
   :history (agent/strip-images (vec history))
   :usage usage})

;; --- List fetch (+ restore-most-recent on panel open)

(defn load-chat
  "Installs one saved conversation as the file's live chat. Guarded by the UI
  against running turns — swapping `:history` mid-turn would desync the
  transcript from what `run-turn` is accumulating."
  [id]
  (ptk/reify ::load-chat
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (->> (rp/cmd! :get-agent-chat {:id id})
             (rx/map (fn [{:keys [id data]}]
                       (ptk/reify ::chat-loaded
                         ptk/UpdateEvent
                         (update [_ state]
                           (if (= file-id (:current-file-id state))
                             (update-in state [:ai-panel file-id]
                                        (fn [panel]
                                          (-> panel
                                              ;; a pending pause belongs to the
                                              ;; conversation it interrupted —
                                              ;; never carry it into another
                                              (dissoc :checkpoint)
                                              (assoc
                                               :chat-id id
                                               :messages (vec (:messages data))
                                               :history (vec (:history data))
                                               :usage (:usage data)))))
                             state)))))
             ;; a vanished row (deleted in another tab) is not worth an error
             ;; bubble on open — the empty composer is the correct outcome
             (rx/catch (fn [_] (rx/empty))))))))

(defn- chats-fetched
  "Stores the list; on panel open (`restore?`) also loads the most recent
  conversation when the file has nothing in memory yet — this is what makes a
  hard refresh land back in the conversation."
  [file-id chats restore?]
  (ptk/reify ::chats-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:ai-panel file-id :chats] (vec chats)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [messages (dm/get-in state [:ai-panel file-id :messages])]
        (if (and restore?
                 (empty? messages)
                 (seq chats)
                 (= file-id (:current-file-id state)))
          (rx/of (load-chat (:id (first chats))))
          (rx/empty))))))

(defn fetch-chats
  "Loads this file's conversation list. `restore?` (panel open) additionally
  restores the most recent conversation into an empty panel."
  ([] (fetch-chats false))
  ([restore?]
   (ptk/reify ::fetch-chats
     ptk/WatchEvent
     (watch [_ state _]
       (when-let [file-id (:current-file-id state)]
         (->> (rp/cmd! :get-agent-chats {:file-id file-id})
              (rx/map #(chats-fetched file-id % restore?))
              (rx/catch (fn [_] (rx/empty)))))))))

;; --- Persistence (emitted by send-message at turn boundaries)

(defn- chat-id-allocated
  [file-id id]
  (ptk/reify ::chat-id-allocated
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:ai-panel file-id :chat-id] id))))

(defn persist-chat
  "Saves the file's live conversation, allocating its row id on first save.
  Fire-and-forget: a failed save must not break the turn that just finished —
  the conversation is still intact in memory and the next boundary retries."
  []
  (ptk/reify ::persist-chat
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)
            panel   (dm/get-in state [:ai-panel file-id])]
        (when (and file-id (seq (:history panel)))
          (let [id      (or (:chat-id panel) (uuid/next))
                payload (chat-payload panel)]
            (rx/concat
             (if (:chat-id panel)
               (rx/empty)
               (rx/of (chat-id-allocated file-id id)))
             (->> (rp/cmd! :upsert-agent-chat {:id id
                                               :file-id file-id
                                               :title (derive-title (:messages panel))
                                               :data payload})
                  ;; refresh metadata (order, updated-at, a first save's row)
                  (rx/map (fn [_] (fetch-chats)))
                  (rx/catch (fn [_] (rx/empty)))))))))))

;; --- Rename

(defn rename-chat
  "Renames one saved conversation. Optimistic — the list updates immediately
  and a failed round-trip refetches to revert. Safe while a turn runs: it
  only touches metadata, never the live history. The stored custom title is
  durable: later saves never write `title` on update (see the backend upsert)."
  [id title]
  (let [title (str/trim title)]
    (ptk/reify ::rename-chat
      ptk/UpdateEvent
      (update [_ state]
        (if-let [file-id (when (seq title) (:current-file-id state))]
          (update-in state [:ai-panel file-id :chats]
                     (fn [chats]
                       (mapv #(if (= id (:id %)) (assoc % :title title) %) chats)))
          state))

      ptk/WatchEvent
      (watch [_ _ _]
        (if (seq title)
          (->> (rp/cmd! :rename-agent-chat {:id id :title title})
               (rx/map (fn [_] (fetch-chats)))
               (rx/catch (fn [_] (rx/of (fetch-chats)))))
          (rx/empty))))))

;; --- New / delete

(defn new-chat
  "Starts a fresh conversation. Non-destructive: the previous one is already
  saved (turn boundaries) and stays in the list."
  []
  (ptk/reify ::new-chat
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (update-in state [:ai-panel file-id]
                   ;; :checkpoint goes too — a pending pause belongs to the
                   ;; conversation that paused; offering Continue here would
                   ;; resume the previous conversation's turn
                   (fn [panel] (dissoc panel :messages :history :usage :chat-id :checkpoint)))
        state))))

(defn delete-chat
  "Deletes one saved conversation. Deleting the active one also empties the
  panel — keeping a live chat whose row is gone would resurrect it on the
  next save, which reads as a failed delete."
  [id]
  (ptk/reify ::delete-chat
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)
            active? (= id (dm/get-in state [:ai-panel file-id :chat-id]))]
        (rx/concat
         (if active? (rx/of (new-chat)) (rx/empty))
         (->> (rp/cmd! :delete-agent-chat {:id id})
              (rx/map (fn [_] (fetch-chats)))
              (rx/catch (fn [_] (rx/of (fetch-chats))))))))))
