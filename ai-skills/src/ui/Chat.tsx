import { useEffect, useRef, useState } from "react";
import type { MessageParam } from "@anthropic-ai/sdk/resources/messages";
import type { Settings } from "./App";
import type { SkillsPayload, DesignContext, TriggeredSkillEvent } from "./bridge";
import { buildSystemPrompt, runTurn, type ToolEvent } from "./agent";
import * as bridge from "./bridge";

interface ChatItem {
  kind: "user" | "assistant" | "tool" | "watch" | "error";
  text: string;
  tool?: ToolEvent;
  watch?: TriggeredSkillEvent;
}

export function Chat({
  settings,
  skills,
  context,
  triggered,
  onTriggeredHandled,
}: {
  settings: Settings;
  skills: SkillsPayload | null;
  context: DesignContext | null;
  triggered: TriggeredSkillEvent | null;
  onTriggeredHandled: () => void;
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

  // Triggered skills arriving from the change watcher
  useEffect(() => {
    if (!triggered) return;
    const text = `Triggered skill "${triggered.skill.name}": ${triggered.reason}`;
    setItems((prev) => [...prev, { kind: "watch", text, watch: triggered }]);
    onTriggeredHandled();
    if (settings.autoApplyTriggered && !busyRef.current && settings.apiKey) {
      void send(
        `[Penpot change watcher] ${text}\nApply this skill now if appropriate; otherwise explain briefly.`,
        false,
      );
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [triggered]);

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
              Chat with your design. The agent inherits{" "}
              <b>{skills?.effective.length ?? 0} skills</b> from this file
              {context?.fileName ? ` (${context.fileName})` : ""} — conventions as context, rules
              enforced at the write path.
            </p>
            <p className="hint">Try: “Style the selected frame using our design system.”</p>
          </div>
        )}
        {items.map((item, i) => (
          <Item key={i} item={item} onAsk={(t) => void send(t)} />
        ))}
        {streamText && <div className="msg assistant">{streamText}</div>}
        {busy && !streamText && <div className="msg assistant thinking">…</div>}
      </div>
      <form className="composer" onSubmit={onSubmit}>
        <textarea
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
        <button className="primary" disabled={busy || !input.trim()} type="submit">
          ➤
        </button>
      </form>
    </div>
  );
}

function Item({ item, onAsk }: { item: ChatItem; onAsk: (text: string) => void }) {
  if (item.kind === "tool" && item.tool) {
    const t = item.tool;
    const cls =
      t.status === "rejected" ? "rejected" : t.status === "error" ? "error" : t.status;
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
  if (item.kind === "watch" && item.watch) {
    return (
      <div className="watchchip">
        <span>
          👁 <b>{item.watch.skill.name}</b> · {item.watch.reason}
        </span>
        <button
          onClick={() =>
            onAsk(
              `[Penpot change watcher] Triggered skill "${item.watch!.skill.name}": ${item.watch!.reason}\nApply this skill now.`,
            )
          }
        >
          Ask agent to apply
        </button>
      </div>
    );
  }
  return <div className={`msg ${item.kind}`}>{item.text}</div>;
}
