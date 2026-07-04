import { useEffect, useState } from "react";
import * as bridge from "./bridge";
import type { SkillsPayload, DesignContext, TriggeredSkillEvent } from "./bridge";
import { Chat } from "./Chat";
import { SkillsPanel } from "./Skills";

type Tab = "chat" | "skills" | "settings";

export interface Settings {
  apiKey: string;
  model: string;
  autoApplyTriggered: boolean;
}

const SETTINGS_KEY = "penpot-skills.settings";

function loadSettings(): Settings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY);
    if (raw) return { autoApplyTriggered: false, ...JSON.parse(raw) };
  } catch {
    /* fresh start */
  }
  return { apiKey: "", model: "claude-sonnet-5", autoApplyTriggered: false };
}

export function App() {
  const [tab, setTab] = useState<Tab>("chat");
  const [skills, setSkills] = useState<SkillsPayload | null>(null);
  const [context, setContext] = useState<DesignContext | null>(null);
  const [settings, setSettings] = useState<Settings>(loadSettings);
  const [triggered, setTriggered] = useState<TriggeredSkillEvent | null>(null);

  useEffect(() => {
    const off = bridge.onPluginEvent((e) => {
      if (e.type === "init") {
        setSkills(e.skills);
        setContext(e.context);
        document.documentElement.dataset.theme = e.theme;
      } else if (e.type === "theme-change") {
        document.documentElement.dataset.theme = e.theme;
      } else if (e.type === "skill-triggered") {
        setTriggered(e);
      }
    });
    bridge.announceReady();
    return off;
  }, []);

  const saveSettings = (next: Settings) => {
    setSettings(next);
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(next));
  };

  const enforcedCount = skills?.effective.filter((s) => s.enforcement === "enforced").length ?? 0;

  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">⛨ Penpot Skills</span>
        <nav>
          <button className={tab === "chat" ? "active" : ""} onClick={() => setTab("chat")}>
            Chat
          </button>
          <button className={tab === "skills" ? "active" : ""} onClick={() => setTab("skills")}>
            Skills{skills ? ` (${skills.effective.length})` : ""}
          </button>
          <button
            className={tab === "settings" ? "active" : ""}
            onClick={() => setTab("settings")}
            title="Settings"
          >
            ⚙
          </button>
        </nav>
      </header>

      {enforcedCount > 0 && (
        <div className="enforced-banner" onClick={() => setTab("skills")}>
          {enforcedCount} rule{enforcedCount > 1 ? "s" : ""} enforced at the write path
        </div>
      )}

      <main className="content">
        <div style={{ display: tab === "chat" ? "contents" : "none" }}>
          <Chat
            settings={settings}
            skills={skills}
            context={context}
            triggered={triggered}
            onTriggeredHandled={() => setTriggered(null)}
          />
        </div>
        {tab === "skills" && skills && (
          <SkillsPanel skills={skills} onSkillsChanged={setSkills} />
        )}
        {tab === "settings" && (
          <SettingsPanel settings={settings} onSave={saveSettings} />
        )}
      </main>
    </div>
  );
}

function SettingsPanel({
  settings,
  onSave,
}: {
  settings: Settings;
  onSave: (s: Settings) => void;
}) {
  const [draft, setDraft] = useState(settings);
  return (
    <div className="settings">
      <label>
        Anthropic API key (stays in this browser — bring your own provider)
        <input
          type="password"
          value={draft.apiKey}
          placeholder="sk-ant-..."
          onChange={(e) => setDraft({ ...draft, apiKey: e.target.value })}
        />
      </label>
      <label>
        Model
        <select value={draft.model} onChange={(e) => setDraft({ ...draft, model: e.target.value })}>
          <option value="claude-sonnet-5">Claude Sonnet 5</option>
          <option value="claude-opus-4-8">Claude Opus 4.8</option>
          <option value="claude-haiku-4-5-20251001">Claude Haiku 4.5</option>
        </select>
      </label>
      <label className="row">
        <input
          type="checkbox"
          checked={draft.autoApplyTriggered}
          onChange={(e) => setDraft({ ...draft, autoApplyTriggered: e.target.checked })}
        />
        Auto-send triggered skills to the agent
      </label>
      <button className="primary" onClick={() => onSave(draft)}>
        Save
      </button>
      <p className="hint">
        The chat calls your provider directly from this panel; skills and enforcement live in
        Penpot, so any provider gets the same rules.
      </p>
    </div>
  );
}
