import { useEffect, useRef, useState } from "react";
import type { MessageParam } from "@anthropic-ai/sdk/resources/messages";
import type { Settings } from "./App";
import type { SkillsPayload, DesignContext } from "./bridge";
import {
  buildSystemPrompt,
  runTurn,
  estimateCostUSD,
  EMPTY_USAGE,
  type ToolEvent,
  type UsageTotals,
} from "./agent";
import * as bridge from "./bridge";

interface ChatItem {
  kind: "user" | "assistant" | "tool" | "error";
  text: string;
  tool?: ToolEvent;
}

/** What survives a panel close: the transcript, the raw API history and the spend meter. */
interface PersistedChat {
  items: ChatItem[];
  history: MessageParam[];
  usage: UsageTotals;
}

const MAX_PERSISTED_ITEMS = 200;
const MAX_HISTORY_MESSAGES = 40;

/**
 * Bounds the API history without splitting a tool_use from its tool_result:
 * only cuts at a plain-text user message (every turn starts with one).
 */
function trimHistory(history: MessageParam[]): MessageParam[] {
  const isTurnStart = (m: MessageParam) => m.role === "user" && typeof m.content === "string";
  if (history.length <= MAX_HISTORY_MESSAGES) return history;
  for (let i = history.length - MAX_HISTORY_MESSAGES; i < history.length; i++) {
    if (isTurnStart(history[i])) return history.slice(i);
  }
  for (let i = history.length - 1; i >= 0; i--) {
    if (isTurnStart(history[i])) return history.slice(i);
  }
  return history;
}

/**
 * The embedded chat panel. Talks to the provider directly (no MCP server in
 * the loop); design tools execute in Penpot via the plugin bridge. Triggered
 * skills arrive here only when the user (or auto-apply) forwards them from the
 * notification layer via `pendingAsk`.
 */
export function Chat({
  settings,
  skills,
  context,
  pendingAsk,
  onPendingAskHandled,
}: {
  settings: Settings;
  skills: SkillsPayload | null;
  context: DesignContext | null;
  pendingAsk: string | null;
  onPendingAskHandled: () => void;
}) {
  const [items, setItems] = useState<ChatItem[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [streamText, setStreamText] = useState("");
  const [usage, setUsage] = useState<UsageTotals>(EMPTY_USAGE);
  const [hydrated, setHydrated] = useState(false);
  const historyRef = useRef<MessageParam[]>([]);
  const scrollRef = useRef<HTMLDivElement>(null);
  const busyRef = useRef(false);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // the panel iframe doesn't get focus automatically when docked
  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // resume this file's conversation from the plugin's cross-file store
  useEffect(() => {
    void bridge
      .call<{ chat: string | null }>("get-chat")
      .then((r) => {
        if (!r.chat) return;
        const stored = JSON.parse(r.chat) as PersistedChat;
        if (Array.isArray(stored.items)) setItems(stored.items);
        if (Array.isArray(stored.history)) historyRef.current = stored.history;
        if (stored.usage) setUsage({ ...EMPTY_USAGE, ...stored.usage });
      })
      .catch(() => {
        /* no stored chat / unreadable — start fresh */
      })
      .finally(() => setHydrated(true));
  }, []);

  // persist between turns (never mid-turn: tool results are still streaming in)
  useEffect(() => {
    if (!hydrated || busy) return;
    historyRef.current = trimHistory(historyRef.current);
    const payload: PersistedChat = {
      items: items.slice(-MAX_PERSISTED_ITEMS),
      history: historyRef.current,
      usage,
    };
    void bridge.call("save-chat", { chat: JSON.stringify(payload) }).catch(() => {});
  }, [hydrated, busy, items, usage]);

  function clearChat() {
    if (busyRef.current) return;
    setItems([]);
    setUsage(EMPTY_USAGE);
    setStreamText("");
    historyRef.current = [];
  }

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
  }, [items, streamText]);

  // A triggered skill handed over from the notification layer
  useEffect(() => {
    if (!pendingAsk) return;
    onPendingAskHandled();
    if (!busyRef.current) void send(pendingAsk);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingAsk]);

  // A prompt handed over from the Skills panel through the file's
  // pluginData (cross-panel: e.g. "Fix via chat" on audit violations)
  useEffect(() => {
    if (!hydrated) return;
    void bridge
      .call<{ prompt: string }>("drain-pending-ask")
      .then((r) => {
        if (r.prompt && !busyRef.current) void send(r.prompt);
      })
      .catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hydrated]);

  async function send(message: string, echoUser = true) {
    if (!message.trim() || busyRef.current) return;
    if (!settings.apiKey) {
      setItems((p) => [
        ...p,
        { kind: "error", text: "Add your Anthropic API key in ⚙ Settings first." },
      ]);
      return;
    }
    busyRef.current = true;
    setBusy(true);
    if (echoUser) setItems((p) => [...p, { kind: "user", text: message }]);

    try {
      // fresh context + skills every turn — the file is the source of truth
      const [freshSkills, freshContext] = await Promise.all([
        bridge.call<SkillsPayload>("get-skills"),
        bridge.call<DesignContext>("get-design-context"),
      ]);
      const system = buildSystemPrompt(freshSkills, freshContext);
      historyRef.current = [...historyRef.current, { role: "user", content: message }];

      historyRef.current = await runTurn(
        { apiKey: settings.apiKey, model: settings.model },
        historyRef.current,
        system,
        {
          onTextDelta: (d) => setStreamText((t) => t + d),
          onAssistantDone: (full) => {
            setStreamText("");
            setItems((p) => [...p, { kind: "assistant", text: full }]);
          },
          onToolEvent: (e) => {
            setItems((prev) => {
              const idx = prev.findIndex((i) => i.tool?.id === e.id);
              const item: ChatItem = { kind: "tool", text: e.name, tool: e };
              if (idx >= 0) {
                const next = [...prev];
                next[idx] = item;
                return next;
              }
              return [...prev, item];
            });
          },
          onUsage: (u) =>
            setUsage((prev) => ({
              inputTokens: prev.inputTokens + u.inputTokens,
              outputTokens: prev.outputTokens + u.outputTokens,
              cacheReadTokens: prev.cacheReadTokens + u.cacheReadTokens,
              cacheWriteTokens: prev.cacheWriteTokens + u.cacheWriteTokens,
              requests: prev.requests + u.requests,
            })),
        },
      );
    } catch (e) {
      setItems((p) => [...p, { kind: "error", text: (e as Error).message }]);
    } finally {
      setStreamText("");
      setBusy(false);
      busyRef.current = false;
    }
  }

  const onSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const msg = input;
    setInput("");
    void send(msg);
  };

  return (
    <div className="chat">
      <div className="messages" ref={scrollRef}>
        {items.length === 0 && (
          <div className="empty">
            <p>
              Chat with your design — no MCP required. The agent inherits{" "}
              <b>{skills?.effective.length ?? 0} skills</b> from this file
              {context?.fileName ? ` (${context.fileName})` : ""} — conventions as context, rules
              enforced at the write path.
            </p>
            <p className="hint">Try: “Style the selected frame using our design system.”</p>
          </div>
        )}
        {items.map((item, i) => (
          <Item key={i} item={item} />
        ))}
        {streamText && <div className="msg assistant">{streamText}</div>}
        {busy && !streamText && <div className="msg assistant thinking">…</div>}
      </div>
      {(usage.requests > 0 || items.length > 0) && (
        <div className="chat-status">
          {usage.requests > 0 ? (
            <UsageMeter usage={usage} model={settings.model} />
          ) : (
            <span className="usage-meter">Restored conversation</span>
          )}
          <button
            className="chat-clear"
            type="button"
            disabled={busy}
            title="Clear this file's chat history and start a fresh session"
            onClick={clearChat}
          >
            ✕ Clear
          </button>
        </div>
      )}
      {!settings.apiKey && (
        <div className="composer-hint">No API key saved — add yours in ⚙ Settings to chat.</div>
      )}
      <form className="composer" onSubmit={onSubmit} onClick={() => inputRef.current?.focus()}>
        <textarea
          ref={inputRef}
          className="input"
          value={input}
          rows={2}
          placeholder="Ask the agent…"
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              onSubmit(e);
            }
          }}
        />
        <button
          data-appearance="primary"
          disabled={busy || !input.trim()}
          title={busy ? "Working…" : input.trim() ? "Send" : "Type a message first"}
          type="submit"
        >
          ➤
        </button>
      </form>
    </div>
  );
}

function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

function UsageMeter({ usage, model }: { usage: UsageTotals; model: string }) {
  const promptTokens = usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens;
  const cachedShare = promptTokens > 0 ? Math.round((usage.cacheReadTokens / promptTokens) * 100) : 0;
  const cost = estimateCostUSD(model, usage);
  return (
    <div
      className="usage-meter"
      title="Session token usage across all API calls from this panel. Cost is an estimate at standard list prices — the Anthropic Console has the authoritative numbers."
    >
      {usage.requests} calls · {formatTokens(promptTokens)} in ({cachedShare}% cached) ·{" "}
      {formatTokens(usage.outputTokens)} out
      {cost !== null && <> · ~${cost.toFixed(2)}</>}
    </div>
  );
}

function Item({ item }: { item: ChatItem }) {
  if (item.kind === "tool" && item.tool) {
    const t = item.tool;
    const cls = t.status === "rejected" ? "rejected" : t.status === "error" ? "error" : t.status;
    return (
      <div className={`toolchip ${cls}`}>
        <span className="tool-name">
          {t.status === "running" ? "⟳" : t.status === "ok" ? "✓" : "✕"} {t.name}
        </span>
        {t.status === "rejected" && (
          <span className="tool-detail">
            ⛔ {t.rule}: {t.detail}
          </span>
        )}
        {t.status === "error" && <span className="tool-detail">{t.detail}</span>}
      </div>
    );
  }
  return <div className={`msg ${item.kind}`}>{item.text}</div>;
}
