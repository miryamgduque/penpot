;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.skills
  "Bundled Penpot Skills panel: design skills, tokens and an embedded chat
  agent, rendered as an integrated workspace side panel (plugin dock). It is
  started from a code-side manifest — like the integrated MCP plugin — so it
  needs no installation through the plugins manager."
  (:require
   [app.main.data.plugins :as dp]
   [app.plugins.register :as preg]
   [app.util.globals :as ug]
   [potok.v2.core :as ptk]))

(def ^:private default-host
  "Host serving the Skills panel assets. Overridable through the
  `penpotSkillsPluginHost` global for deployments; the default matches the
  development server of `/ai-skills`."
  "http://localhost:4500/")

(defn- plugin-host
  []
  (or (unchecked-get ug/global "penpotSkillsPluginHost") default-host))

(defn- manifest
  []
  {:plugin-id preg/skills-plugin-id
   :name "Penpot Skills"
   :version 2
   :description "Design skills, tokens and an embedded chat agent"
   :host (plugin-host)
   :code "plugin.js"
   :permissions
   #{"content:read" "content:write"
     "library:read" "library:write"
     "allow:localstorage"}})

(defn- panel-open?
  "The panel may also be closed from its own close button, so the source of
  truth is whether the plugin is currently mounted in the plugin dock."
  []
  (some? (.querySelector js/document "#plugin-dock plugin-modal")))

(defn toggle-skills-panel
  "Opens the Skills panel as a docked workspace panel, or closes it when it
  is already open."
  []
  (ptk/reify ::toggle-skills-panel
    ptk/EffectEvent
    (effect [_ _ _]
      (if (panel-open?)
        (dp/close-plugin! (manifest))
        (dp/start-plugin! (manifest) nil)))))
