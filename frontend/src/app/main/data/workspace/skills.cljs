;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.skills
  "Bundled Penpot Skills panels, rendered as integrated workspace side
  panels (plugin dock) and started from code-side manifests — like the
  integrated MCP plugin, no installation required.

  Two panels share one runtime bundle: the Agent chat and the Skills
  manager (skills + audit + tokens). The plugins runtime keeps a single
  plugin alive, so opening one panel swaps out the other.

  App/team scope skills live in the database (see app.main.data.skills);
  after opening a panel the workspace fetches them and pushes them into
  the plugin context via window.postMessage — the plugins runtime
  forwards window messages to running plugins."
  (:require
   [app.main.data.plugins :as dp]
   [app.main.repo :as rp]
   [app.plugins.register :as preg]
   [app.util.globals :as ug]
   [app.util.timers :as tm]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def ^:private default-host
  "Host serving the panel assets. Overridable through the
  `penpotSkillsPluginHost` global for deployments; the default matches the
  development server of `/ai-skills`."
  "http://localhost:4500/")

(defn- plugin-host
  []
  (or (unchecked-get ug/global "penpotSkillsPluginHost") default-host))

(def ^:private permissions
  #{"content:read" "content:write"
    "library:read" "library:write"
    "allow:localstorage"})

(defn- manifest
  [panel]
  (case panel
    :chat
    {:plugin-id preg/skills-plugin-id
     :name "Penpot Agent"
     :version 2
     :description "Embedded design chat agent"
     :host (plugin-host)
     :code "plugin-chat.js"
     :permissions permissions}

    :skills
    {:plugin-id preg/skills-manager-plugin-id
     :name "Penpot Skills"
     :version 2
     :description "Design skills, rules, audit and tokens"
     :host (plugin-host)
     :code "plugin-skills.js"
     :permissions permissions}))

(defn- panel-open?
  "A panel may also be closed from its own close button, so the source of
  truth is whether a plugin is currently mounted in the plugin dock."
  []
  (some? (.querySelector js/document "#plugin-dock plugin-modal")))

(defn- current-panel
  "The panel currently docked, correcting the store flag against the DOM."
  [state]
  (when (panel-open?)
    (get-in state [:workspace-local :skills-dock])))

;; --- DB scopes push

(defn- push-scopes!
  "Posts the app/team skill rows into the running plugin context. The
  plugins runtime forwards any window message to running plugins; sent a
  few times because the plugin script needs a moment to boot and register
  its listener (the payload is idempotent)."
  [data]
  (let [msg (clj->js {:type "penpot-skills/scopes"
                      :app (:app data)
                      :team (:team data)
                      :overrides (:overrides data)})]
    (doseq [delay-ms [600 1800 3600]]
      (tm/schedule delay-ms #(.postMessage js/window msg "*")))))

(defn- fetch-and-push-scopes
  []
  (ptk/reify ::fetch-and-push-scopes
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/cmd! :get-design-skills {:team-id team-id})
             (rx/tap push-scopes!)
             (rx/catch (fn [_cause]
                         ;; DB scopes unavailable (old backend, offline…) —
                         ;; the panel falls back to its bundled set
                         (rx/empty)))
             (rx/ignore))))))

;; --- Panel toggling

(defn open-panel
  "Opens the given panel (:chat or :skills) in the plugin dock, replacing
  whatever panel is currently docked. No-op when it is already open."
  [panel]
  (ptk/reify ::open-panel
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :skills-dock] panel))

    ptk/WatchEvent
    (watch [_ state _]
      (if (= panel (current-panel state))
        (rx/empty)
        (rx/of (fetch-and-push-scopes))))

    ptk/EffectEvent
    (effect [_ _ _]
      ;; loading a plugin closes any other one (runtime behavior), which
      ;; is exactly the single-dock swap we want
      (dp/start-plugin! (manifest panel) nil))))

(defn close-panel
  []
  (ptk/reify ::close-panel
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :skills-dock] nil))

    ptk/EffectEvent
    (effect [_ _ _]
      (dp/close-plugin! (manifest :chat))
      (dp/close-plugin! (manifest :skills)))))

(defn toggle-panel
  "Header-button behavior: opens the panel, or closes it when it is the
  one currently docked."
  [panel]
  (ptk/reify ::toggle-panel
    ptk/WatchEvent
    (watch [_ state _]
      (if (= panel (current-panel state))
        (rx/of (close-panel))
        (rx/of (open-panel panel))))))

;; kept as an alias for any existing callers
(defn toggle-skills-panel
  []
  (toggle-panel :skills))
