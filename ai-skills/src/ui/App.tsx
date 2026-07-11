import { useCallback, useEffect, useRef, useState } from "react";
import * as bridge from "./bridge";
import type { SkillsPayload, DesignContext, Violation } from "./bridge";
import { Chat } from "./Chat";
import { SkillsPanel } from "./Skills";
import { TokensPanel } from "./Tokens";
import { AuditPanel } from "./Audit";
import { NotificationStack, NOTIFICATION_TTL_MS, type SkillNotification } from "./Notifications";

type Tab = "chat" | "skills" | "audit" | "tokens" | "settings";
type PanelMode = "chat" | "skills" | "all";

/**
 * Which panel this iframe is: the plugin entry passes ?mode=chat|skills in
 * the open URL, which the plugins runtime forwards through the URL hash
 * (manifest version 2). Legacy single-panel installs get every tab.
 */
function panelMode(): PanelMode {
  try {
    const query = new URLSearchParams(window.location.hash.split("?")[1] ?? "");
    const mode = query.get("mode");
    return mode === "chat" || mode === "skills" ? mode : "all";
  } catch {
    return "all";
  }
}

const MODE: PanelMode = panelMode();

const TABS_BY_MODE: Record<PanelMode, Tab[]> = {
  chat: ["chat", "settings"],
  skills: ["skills", "audit", "tokens"],
  all: ["chat", "skills", "audit", "tokens", "settings"],
};

/** Hands a prompt to the Chat panel: pending-ask travels through the file's
 * pluginData; the workspace listens for the open-chat message and docks the
 * chat panel, which drains the prompt on init. */
function handOffToChat(prompt: string) {
  void bridge.call("set-pending-ask", { prompt }).catch(() => {});
  window.parent.postMessage({ type: "penpot-skills:open-chat" }, "*");
}

export interface Settings {
  apiKey: string;
  model: string;
  autoApplyTriggered: boolean;
}

const SETTINGS_KEY = "penpot-skills.settings";
const DEFAULT_SETTINGS: Settings = {
  apiKey: "",
  model: "claude-sonnet-5",
  autoApplyTriggered: false,
};

function parseSettings(raw: string | null | undefined): Settings | null {
  if (!raw) return null;
  try {
    return { ...DEFAULT_SETTINGS, ...JSON.parse(raw) };
  } catch {
    return null;
  }
}

// window.localStorage is only a fast path — embedded cross-origin iframes may
// have partitioned or blocked storage, so the source of truth is the plugin's
// penpot.localStorage, loaded through the bridge after mount.
function loadLocalSettings(): Settings {
  try {
    return parseSettings(localStorage.getItem(SETTINGS_KEY)) ?? DEFAULT_SETTINGS;
  } catch {
    return DEFAULT_SETTINGS;
  }
}

export function App() {
  const [tab, setTab] = useState<Tab>(TABS_BY_MODE[MODE][0]);
  const [skills, setSkills] = useState<SkillsPayload | null>(null);
  const [context, setContext] = useState<DesignContext | null>(null);
  const [settings, setSettings] = useState<Settings>(loadLocalSettings);
  const [notifications, setNotifications] = useState<SkillNotification[]>([]);
  const [violations, setViolations] = useState<Violation[]>([]);
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
        setViolations(e.violations ?? []);
        document.documentElement.dataset.theme = e.theme;
      } else if (e.type === "skills-change") {
        setSkills(e.skills);
      } else if (e.type === "violations-change") {
        setViolations(e.violations);
      } else if (e.type === "theme-change") {
        document.documentElement.dataset.theme = e.theme;
      } else if (e.type === "skill-triggered") {
        // transient notification layer — separate from the chat
        setNotifications((prev) => [
          ...prev.slice(-3),
          { ...e, id: notifId.current++, expiresAt: Date.now() + NOTIFICATION_TTL_MS },
        ]);
        // auto-apply needs the API key, which lives in the chat panel's
        // storage — only meaningful when this panel hosts the chat
        if (MODE !== "skills" && settingsRef.current.autoApplyTriggered && settingsRef.current.apiKey) {
          setPendingAsk(
            `[Penpot change watcher] Triggered skill "${e.skill.name}": ${e.reason}` +
              (e.shape ? ` (shape "${e.shape.name}", id ${e.shape.id})` : "") +
              `\nApply this skill now if appropriate; otherwise explain briefly.`,
          );
        }
      }
    });
    bridge.announceReady();
    // hydrate settings from the plugin's storage (authoritative)
    void bridge
      .call<{ settings: string | null }>("get-settings")
      .then((r) => {
        const stored = parseSettings(r.settings);
        if (stored) setSettings(stored);
      })
      .catch(() => {});
    return off;
  }, []);

  const dismiss = useCallback(
    (id: number) => setNotifications((prev) => prev.filter((n) => n.id !== id)),
    [],
  );

  const toChat = (prompt: string) => {
    if (MODE === "skills") {
      handOffToChat(prompt);
    } else {
      setTab("chat");
      setPendingAsk(prompt);
    }
  };

  const askAgent = (n: SkillNotification) => {
    dismiss(n.id);
    toChat(
      `[Penpot change watcher] Triggered skill "${n.skill.name}": ${n.reason}` +
        (n.shape ? ` (shape "${n.shape.name}", id ${n.shape.id})` : "") +
        `\nApply this skill now.`,
    );
  };

  const saveSettings = async (next: Settings) => {
    setSettings(next);
    const raw = JSON.stringify(next);
    try {
      localStorage.setItem(SETTINGS_KEY, raw);
    } catch {
      /* partitioned/blocked iframe storage — the bridge save below is the one that matters */
    }
    await bridge.call("save-settings", { settings: raw });
  };

  const enforcedCount = skills?.effective.filter((s) => s.enforcement === "enforced").length ?? 0;

  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">{MODE === "chat" ? "✦ Penpot Agent" : "⛨ Penpot Skills"}</span>
        <nav>
          {TABS_BY_MODE[MODE].includes("chat") && (
            <button
              data-appearance={tab === "chat" ? "primary" : "secondary"}
              className={tab === "chat" ? "active" : ""}
              onClick={() => setTab("chat")}
            >
              Chat
            </button>
          )}
          {TABS_BY_MODE[MODE].includes("skills") && (
            <button
              data-appearance={tab === "skills" ? "primary" : "secondary"}
              className={tab === "skills" ? "active" : ""}
              onClick={() => setTab("skills")}
            >
              Skills{skills ? ` (${skills.effective.length})` : ""}
            </button>
          )}
          {TABS_BY_MODE[MODE].includes("audit") && (
            <button
              data-appearance={tab === "audit" ? "primary" : "secondary"}
              className={tab === "audit" ? "active" : ""}
              onClick={() => setTab("audit")}
            >
              Audit{violations.length > 0 ? ` (${violations.length})` : ""}
            </button>
          )}
          {TABS_BY_MODE[MODE].includes("tokens") && (
            <button
              data-appearance={tab === "tokens" ? "primary" : "secondary"}
              className={tab === "tokens" ? "active" : ""}
              onClick={() => setTab("tokens")}
            >
              Tokens
            </button>
          )}
          {TABS_BY_MODE[MODE].includes("settings") && (
            <button
              data-appearance={tab === "settings" ? "primary" : "secondary"}
              className={tab === "settings" ? "active" : ""}
              onClick={() => setTab("settings")}
              title="Settings"
            >
              ⚙
            </button>
          )}
        </nav>
      </header>

      {enforcedCount > 0 && (
        <div className="enforced-banner" onClick={() => setTab("skills")}>
          {enforcedCount} rule{enforcedCount > 1 ? "s" : ""} enforced at the write path
        </div>
      )}

      <NotificationStack notifications={notifications} onDismiss={dismiss} onAskAgent={askAgent} />

      <main className="content">
        {MODE !== "skills" && (
          <div style={{ display: tab === "chat" ? "contents" : "none" }}>
            <Chat
              settings={settings}
              skills={skills}
              context={context}
              pendingAsk={pendingAsk}
              onPendingAskHandled={() => setPendingAsk(null)}
            />
          </div>
        )}
        {tab === "skills" && skills && <SkillsPanel skills={skills} onSkillsChanged={setSkills} />}
        {tab === "audit" && (
          <AuditPanel
            violations={violations}
            onViolationsChanged={setViolations}
            onFixViaChat={toChat}
          />
        )}
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
  onSave: (s: Settings) => Promise<void>;
}) {
  const [draft, setDraft] = useState(settings);
  const [status, setStatus] = useState<"idle" | "saving" | "saved" | "error">("idle");

  // re-sync when the authoritative settings arrive from the plugin storage
  useEffect(() => {
    setDraft(settings);
  }, [settings]);

  const save = async () => {
    setStatus("saving");
    try {
      await onSave(draft);
      setStatus("saved");
      setTimeout(() => setStatus("idle"), 2500);
    } catch {
      setStatus("error");
    }
  };

  return (
    <div className="settings">
      <label>
        Anthropic API key (stored in this plugin, never sent anywhere but your provider)
        <input
          className="input"
          type="password"
          value={draft.apiKey}
          placeholder="sk-ant-..."
          onChange={(e) => setDraft({ ...draft, apiKey: e.target.value })}
        />
      </label>
      <span className={`key-status ${settings.apiKey ? "ok" : ""}`}>
        {settings.apiKey
          ? `✓ Key saved (…${settings.apiKey.slice(-4)}) — the chat is ready`
          : "No key saved yet — the chat needs one"}
      </span>
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
      <button data-appearance="primary" disabled={status === "saving"} onClick={() => void save()}>
        {status === "saving" ? "Saving…" : status === "saved" ? "Saved ✓" : "Save"}
      </button>
      {status === "error" && (
        <div className="msg error">Could not save settings — is the plugin still connected?</div>
      )}
      <p className="hint">
        The chat calls your provider directly from this panel — no MCP server required; skills and
        enforcement live in Penpot, so any provider gets the same rules.
      </p>
    </div>
  );
}
