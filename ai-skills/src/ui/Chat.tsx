import { useEffect, useRef, useState } from "react";
import type { SkillsPayload, DesignContext, AiPoolEntry } from "./bridge";
import {
  buildSystemPrompt,
  runTurn,
  estimateCostUSD,
  EMPTY_USAGE,
  type CanonicalMessage,
  type ToolEvent,
  type UsageTotals,
} from "./agent";
import * as bridge from "./bridge";

interface ChatItem {
  kind: "user" | "assistant" | "tool" | "error";
  text: string;
  tool?: ToolEvent;
}

/** What survives a panel close: the transcript, the canonical history, the
 * spend meter and the selected pool entry. v2 = canonical history format —
 * older persisted chats keep their transcript but drop the API history. */
interface PersistedChat {
  v?: number;
  items: ChatItem[];
  history: CanonicalMessage[];
  usage: UsageTotals;
  selection?: AiPoolEntry | null;
}

const PERSIST_VERSION = 2;
const MAX_PERSISTED_ITEMS = 200;
const MAX_HISTORY_MESSAGES = 40;

export const PROVIDER_LABELS: Record<string, string> = {
  anthropic: "Anthropic",
  openai: "OpenAI",
  zhipu: "Zhipu AI",
  moonshot: "Moonshot AI",
};

export function providerLabel(id: string): string {
  return PROVIDER_LABELS[id] ?? id;
}

/**
 * Bounds the canonical history without splitting a tool call from its
 * results: only cuts at a plain user message (every turn starts with one).
 */
function trimHistory(history: CanonicalMessage[]): CanonicalMessage[] {
  const isTurnStart = (m: CanonicalMessage) => m.role === "user";
  if (history.length <= MAX_HISTORY_MESSAGES) return history;
  for (let i = history.length - MAX_HISTORY_MESSAGES; i < history.length; i++) {
    if (isTurnStart(history[i])) return history.slice(i);
  }
  for (let i = history.length - 1; i >= 0; i--) {
    if (isTurnStart(history[i])) return history.slice(i);
  }
  return history;
}

function inPool(pool: AiPoolEntry[], sel: AiPoolEntry | null): boolean {
  return !!sel && pool.some((e) => e.provider === sel.provider && e.model === sel.model);
}

function poolKey(e: AiPoolEntry): string {
  return `${e.provider}::${e.model}`;
}

/**
 * The embedded chat panel. Rounds go through the Penpot backend proxy (the
 * provider keys never reach the browser); design tools execute in Penpot
 * via the plugin bridge. The model picker spans the enabled models of ALL
 * connected providers and can be switched mid-conversation — the canonical
 * history carries over, tool calls included. Triggered skills arrive here
 * only when the user (or auto-apply) forwards them from the notification
 * layer via `pendingAsk`. With an empty model pool it renders the
 * first-run state instead.
 */
export function Chat({
  pool,
  settingsUri,
  skills,
  context,
  pendingAsk,
  onPendingAskHandled,
}: {
  pool: AiPoolEntry[];
  settingsUri: string | null;
  skills: SkillsPayload | null;
  context: DesignContext | null;
  pendingAsk: string | null;
  onPendingAskHandled: () => void;
}) {
  const [items, setItems] = useState<ChatItem[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [usage, setUsage] = useState<UsageTotals>(EMPTY_USAGE);
  const [hydrated, setHydrated] = useState(false);
  const [selection, setSelection] = useState<AiPoolEntry | null>(null);
  const historyRef = useRef<CanonicalMessage[]>([]);
  const scrollRef = useRef<HTMLDivElement>(null);
  const busyRef = useRef(false);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const selectionValid = inPool(pool, selection);

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
        if (stored.v === PERSIST_VERSION) {
          if (Array.isArray(stored.history)) historyRef.current = stored.history;
          if (stored.selection) setSelection(stored.selection);
        }
        if (stored.usage) setUsage({ ...EMPTY_USAGE, ...stored.usage });
      })
      .catch(() => {
        /* no stored chat / unreadable — start fresh */
      })
      .finally(() => setHydrated(true));
  }, []);

  // a fresh conversation defaults to the pool's first model; an explicit
  // selection that became invalid (provider disconnected / model disabled)
  // is kept visible so the disabled-conversation state can explain itself
  useEffect(() => {
    if (!hydrated) return;
    if (selection === null && pool.length > 0) setSelection(pool[0]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hydrated, pool]);

  // persist between turns (never mid-turn: tool results are still coming in)
  useEffect(() => {
    if (!hydrated || busy) return;
    historyRef.current = trimHistory(historyRef.current);
    const payload: PersistedChat = {
      v: PERSIST_VERSION,
      items: items.slice(-MAX_PERSISTED_ITEMS),
      history: historyRef.current,
      usage,
      selection,
    };
    void bridge.call("save-chat", { chat: JSON.stringify(payload) }).catch(() => {});
  }, [hydrated, busy, items, usage, selection]);

  function clearChat() {
    if (busyRef.current) return;
    setItems([]);
    setUsage(EMPTY_USAGE);
    historyRef.current = [];
  }

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
  }, [items, busy]);

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
    if (!selection || !inPool(pool, selection)) return;
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
      historyRef.current = [...historyRef.current, { role: "user", text: message }];

      historyRef.current = await runTurn(
        { provider: selection.provider, model: selection.model },
        historyRef.current,
        system,
        {
          onAssistantDone: (full) => {
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

  // First-run state: no provider connected, or none with an enabled model.
  // The actual setup lives on /settings/integrations — not in this panel.
  if (hydrated && pool.length === 0) {
    return (
      <div className="chat">
        <div className="first-run">
          {/* Lucide "unplug" icon, inlined to keep the bundle self-contained */}
          <svg
            className="first-run-icon"
            xmlns="http://www.w3.org/2000/svg"
            width="32"
            height="32"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="m19 5 3-3" />
            <path d="m2 22 3-3" />
            <path d="M6.3 20.3a2.4 2.4 0 0 0 3.4 0L12 18l-6-6-2.3 2.3a2.4 2.4 0 0 0 0 3.4Z" />
            <path d="M7.5 13.5 10 11" />
            <path d="M10.5 16.5 13 14" />
            <path d="m12 6 6 6 2.3-2.3a2.4 2.4 0 0 0 0-3.4l-2.6-2.6a2.4 2.4 0 0 0-3.4 0Z" />
          </svg>
          <h2>Connect an AI provider</h2>
          <p>
            The agent needs an AI provider before it can be used. Connect one from your account
            settings and enable the models you want to chat with.
          </p>
          <button
            data-appearance="primary"
            type="button"
            onClick={() => {
              if (settingsUri) window.open(settingsUri, "_blank");
            }}
          >
            Connect an AI provider
          </button>
        </div>
      </div>
    );
  }

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
        {busy && <div className="msg assistant thinking">…</div>}
      </div>

      {(usage.requests > 0 || items.length > 0) && (
        <div className="chat-status">
          {usage.requests > 0 ? (
            <UsageMeter usage={usage} model={selection?.model ?? ""} />
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

      {/* one pool across all connected providers; switching mid-conversation
          (provider included) carries the canonical history over */}
      <div className="model-picker-row">
        <ModelPicker
          pool={pool}
          selection={selection}
          selectionValid={selectionValid}
          disabled={busy}
          onSelect={setSelection}
        />
      </div>

      {!selectionValid && selection && (
        <div className="composer-hint">
          {providerLabel(selection.provider)} was disconnected (or “{selection.model}” is no
          longer enabled). Pick another model above to resume this conversation.
        </div>
      )}

      <form className="composer" onSubmit={onSubmit} onClick={() => inputRef.current?.focus()}>
        <textarea
          ref={inputRef}
          className="input"
          value={input}
          rows={2}
          disabled={!selectionValid}
          placeholder={selectionValid ? "Ask the agent…" : "Select a model to resume…"}
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
          disabled={busy || !input.trim() || !selectionValid}
          title={
            !selectionValid
              ? "Select a model first"
              : busy
                ? "Working…"
                : input.trim()
                  ? "Send"
                  : "Type a message first"
          }
          type="submit"
        >
          ➤
        </button>
      </form>
    </div>
  );
}

/**
 * Compact model picker in the spirit of Cursor's: a trigger showing the
 * current model that opens a popover of the whole pool grouped by
 * provider. Closes on outside-click or Escape. Selecting an entry switches
 * the model mid-conversation — the canonical history carries over.
 */
function ModelPicker({
  pool,
  selection,
  selectionValid,
  disabled,
  onSelect,
}: {
  pool: AiPoolEntry[];
  selection: AiPoolEntry | null;
  selectionValid: boolean;
  disabled: boolean;
  onSelect: (e: AiPoolEntry) => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const providers = [...new Set(pool.map((e) => e.provider))];

  useEffect(() => {
    if (!open) return;
    const onDoc = (ev: MouseEvent) => {
      if (ref.current && !ref.current.contains(ev.target as Node)) setOpen(false);
    };
    const onKey = (ev: KeyboardEvent) => {
      if (ev.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onDoc);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDoc);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const current = selection && selectionValid ? selection : null;

  return (
    <div className="model-picker" ref={ref}>
      <button
        type="button"
        className="model-picker-trigger"
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        title="Choose the model for this conversation"
        onClick={() => setOpen((o) => !o)}
      >
        <span className="model-picker-current">
          {current ? (
            <>
              <span className="model-picker-name">{current.model}</span>
              <span className="model-picker-provider">{providerLabel(current.provider)}</span>
            </>
          ) : (
            <span className="model-picker-name">Select a model</span>
          )}
        </span>
        <span className="model-picker-caret" aria-hidden="true">
          ▾
        </span>
      </button>

      {open && (
        <div className="model-picker-menu" role="listbox">
          {providers.map((prov) => (
            <div className="model-picker-group" key={prov}>
              <div className="model-picker-group-label">{providerLabel(prov)}</div>
              {pool
                .filter((e) => e.provider === prov)
                .map((e) => {
                  const active =
                    !!current && current.provider === e.provider && current.model === e.model;
                  return (
                    <button
                      key={poolKey(e)}
                      type="button"
                      role="option"
                      aria-selected={active}
                      className={`model-picker-option${active ? " active" : ""}`}
                      onClick={() => {
                        onSelect(e);
                        setOpen(false);
                      }}
                    >
                      <span className="model-picker-option-name">{e.model}</span>
                      {active && (
                        <span className="model-picker-check" aria-hidden="true">
                          ✓
                        </span>
                      )}
                    </button>
                  );
                })}
            </div>
          ))}
        </div>
      )}
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
      title="Session token usage across all API calls from this panel. Cost is an estimate at standard list prices — your provider's console has the authoritative numbers."
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
