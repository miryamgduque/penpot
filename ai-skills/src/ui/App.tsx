import { useCallback, useEffect, useRef, useState } from "react";
import * as bridge from "./bridge";
import type { SkillsPayload, DesignContext } from "./bridge";
import { Chat } from "./Chat";
import { SkillsPanel } from "./Skills";
import { TokensPanel } from "./Tokens";
import { NotificationStack, NOTIFICATION_TTL_MS, type SkillNotification } from "./Notifications";

type Tab = "chat" | "skills" | "tokens" | "settings";

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
  const [notifications, setNotifications] = useState<SkillNotification[]>([]);
  const [pendingAsk, setPendingAsk] = useState<string | null>(null);
  const notifId = useRef(1);
  const settingsRef = useRef(settings);
  useEffect(() => {
    settingsRef.current = settings;
  }, [settings]);

  useEffect(() => {
    const off = bridge.onPluginEvent((e) => {
      if (e.type === "init") {
        setSkills(e.skills);
        setContext(e.context);
        document.documentElement.dataset.theme = e.theme;
      } else if (e.type === "theme-change") {
        document.documentElement.dataset.theme = e.theme;
      } else if (e.type === "skill-triggered") {
        // transient notification layer — separate from the chat
        setNotifications((prev) => [
          ...prev.slice(-3),
          { ...e, id: notifId.current++, expiresAt: Date.now() + NOTIFICATION_TTL_MS },
        ]);
        if (settingsRef.current.autoApplyTriggered && settingsRef.current.apiKey) {
          setPendingAsk(
            `[Penpot change watcher] Triggered skill "${e.skill.name}": ${e.reason}` +
              (e.shape ? ` (shape "${e.shape.name}", id ${e.shape.id})` : "") +
              `\nApply this skill now if appropriate; otherwise explain briefly.`,
          );
        }
      }
    });
    bridge.announceReady();
    return off;
  }, []);

  const dismiss = useCallback(
    (id: number) => setNotifications((prev) => prev.filter((n) => n.id !== id)),
    [],
  );

  const askAgent = (n: SkillNotification) => {
    dismiss(n.id);
    setTab("chat");
    setPendingAsk(
      `[Penpot change watcher] Triggered skill "${n.skill.name}": ${n.reason}` +
        (n.shape ? ` (shape "${n.shape.name}", id ${n.shape.id})` : "") +
        `\nApply this skill now.`,
    );
  };

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
          <button
            data-appearance={tab === "chat" ? "primary" : "secondary"}
            className={tab === "chat" ? "active" : ""}
            onClick={() => setTab("chat")}
          >
            Chat
          </button>
          <button
            data-appearance={tab === "skills" ? "primary" : "secondary"}
            className={tab === "skills" ? "active" : ""}
            onClick={() => setTab("skills")}
          >
            Skills{skills ? ` (${skills.effective.length})` : ""}
          </button>
          <button
            data-appearance={tab === "tokens" ? "primary" : "secondary"}
            className={tab === "tokens" ? "active" : ""}
            onClick={() => setTab("tokens")}
          >
            Tokens
          </button>
          <button
            data-appearance={tab === "settings" ? "primary" : "secondary"}
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

      <NotificationStack notifications={notifications} onDismiss={dismiss} onAskAgent={askAgent} />

      <main className="content">
        <div style={{ display: tab === "chat" ? "contents" : "none" }}>
          <Chat
            settings={settings}
            skills={skills}
            context={context}
            pendingAsk={pendingAsk}
            onPendingAskHandled={() => setPendingAsk(null)}
          />
        </div>
        {tab === "skills" && skills && <SkillsPanel skills={skills} onSkillsChanged={setSkills} />}
        {tab === "tokens" && <TokensPanel />}
        {tab === "settings" && <SettingsPanel settings={settings} onSave={saveSettings} />}
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
          className="input"
          type="password"
          value={draft.apiKey}
          placeholder="sk-ant-..."
          onChange={(e) => setDraft({ ...draft, apiKey: e.target.value })}
        />
      </label>
      <label>
        Model
        <select
          className="select"
          value={draft.model}
          onChange={(e) => setDraft({ ...draft, model: e.target.value })}
        >
          <option value="claude-sonnet-5">Claude Sonnet 5</option>
          <option value="claude-opus-4-8">Claude Opus 4.8</option>
          <option value="claude-haiku-4-5-20251001">Claude Haiku 4.5</option>
        </select>
      </label>
      <div className="checkbox-container">
        <input
          className="checkbox-input"
          id="auto-apply"
          type="checkbox"
          checked={draft.autoApplyTriggered}
          onChange={(e) => setDraft({ ...draft, autoApplyTriggered: e.target.checked })}
        />
        <label htmlFor="auto-apply">Auto-send triggered skills to the agent</label>
      </div>
      <button data-appearance="primary" onClick={() => onSave(draft)}>
        Save
      </button>
      <p className="hint">
        The chat calls your provider directly from this panel — no MCP server required; skills and
        enforcement live in Penpot, so any provider gets the same rules.
      </p>
    </div>
  );
}
