import { useEffect, useRef, useState } from "react";
import type { MessageParam } from "@anthropic-ai/sdk/resources/messages";
import type { Settings } from "./App";
import type { SkillsPayload, DesignContext } from "./bridge";
import { buildSystemPrompt, runTurn, type ToolEvent } from "./agent";
import * as bridge from "./bridge";

interface ChatItem {
  kind: "user" | "assistant" | "tool" | "error";
  text: string;
  tool?: ToolEvent;
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
  const historyRef = useRef<MessageParam[]>([]);
  const scrollRef = useRef<HTMLDivElement>(null);
  const busyRef = useRef(false);

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
      <form className="composer" onSubmit={onSubmit}>
        <textarea
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
        <button data-appearance="primary" disabled={busy || !input.trim()} type="submit">
          ➤
        </button>
      </form>
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
