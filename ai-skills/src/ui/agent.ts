/**
 * The embedded chat agent, routed through the Penpot backend proxy (one
 * buffered request per provider round — see bridge.aiRound): provider keys
 * live with the user's profile on the server and never reach the browser.
 * Design tools execute inside Penpot via the plugin bridge.
 *
 * The conversation history is stored in ONE canonical format and encoded
 * into the wire form of whichever provider the user has currently selected
 * (Anthropic Messages for Claude models; OpenAI chat.completions for
 * OpenAI/Zhipu/Moonshot models). That is what lets the user switch models
 * mid-conversation — across providers — and carry the whole history,
 * including tool calls, to a provider that never produced it.
 */

import * as bridge from "./bridge";
import type { SkillsPayload } from "./bridge";
import { skillManifest } from "@penpot/skills-core";

/* ------------------------------------------------------------------ */
/* Canonical conversation model                                        */
/* ------------------------------------------------------------------ */

export interface CanonicalToolCall {
  id: string;
  name: string;
  input: Record<string, unknown>;
}

export interface CanonicalToolResult {
  id: string;
  content: string;
  isError: boolean;
}

export type CanonicalMessage =
  | { role: "user"; text: string }
  | { role: "assistant"; text: string; toolCalls: CanonicalToolCall[] }
  | { role: "tool_results"; results: CanonicalToolResult[] };

export interface ToolEvent {
  id: string;
  name: string;
  input: unknown;
  status: "running" | "ok" | "rejected" | "error";
  detail?: string;
  rule?: string;
}

export interface UsageTotals {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  requests: number;
}

export const EMPTY_USAGE: UsageTotals = {
  inputTokens: 0,
  outputTokens: 0,
  cacheReadTokens: 0,
  cacheWriteTokens: 0,
  requests: 0,
};

/** $ per million tokens (standard list price; cache read ≈ 0.1×, write ≈ 1.25×).
 * Only maintained for Claude models — the meter hides cost otherwise. */
const PRICING: Record<string, { input: number; output: number }> = {
  "claude-sonnet-5": { input: 3, output: 15 },
  "claude-opus-4-8": { input: 5, output: 25 },
  "claude-haiku-4-5-20251001": { input: 1, output: 5 },
};

export function estimateCostUSD(model: string, u: UsageTotals): number | null {
  const p = PRICING[model];
  if (!p) return null;
  return (
    (u.inputTokens * p.input +
      u.cacheReadTokens * p.input * 0.1 +
      u.cacheWriteTokens * p.input * 1.25 +
      u.outputTokens * p.output) /
    1_000_000
  );
}

/* ------------------------------------------------------------------ */
/* Tools                                                               */
/* ------------------------------------------------------------------ */

/**
 * Deliberately few, task-scoped tools (the MCP server keeps the atomic
 * surface for external agents): one orientation read, skill bodies on
 * demand, a batch token applicator, an audit runner, and execute_code for
 * everything generative. Fewer calls per task = fewer round trips and less
 * context burned on tool results.
 */
export interface ToolSpec {
  name: string;
  description: string;
  input_schema: Record<string, unknown>;
}

export const TOOLS: ToolSpec[] = [
  {
    name: "read_design",
    description:
      "One-call orientation: current file/page/selection and top-level shapes, the color tokens + library colors, the effective skills/rules manifest, and how many audit violations are open. Call this FIRST each task instead of separate context/token/skill reads.",
    input_schema: { type: "object", properties: {} },
  },
  {
    name: "get_design_skills",
    description:
      "Returns the effective design skills for this file (four-scope cascade already resolved, disabled entries excluded). By default a lean manifest; pass a name to get one skill's full markdown body.",
    input_schema: {
      type: "object",
      properties: {
        name: { type: "string", description: "Return the full body of this skill only" },
        include_bodies: { type: "boolean", description: "Include full bodies for all skills" },
      },
    },
  },
  {
    name: "audit_file",
    description:
      "Scans the current page against the file's active rules (token-only-colors, layer-naming, …) and returns the open violations (rule, shape, reason). Use it to ground a fix-up task and to verify your fixes cleared the list.",
    input_schema: { type: "object", properties: {} },
  },
  {
    name: "apply_tokens",
    description:
      "Applies color design tokens to shapes in batch — the safe path for coloring (never rejected by token-only-colors). Each application names a shape, a token, and optionally the properties ('fill' and/or 'stroke', default fill). Token application is asynchronous in Penpot: verify with audit_file or read_design afterwards, not in the same call.",
    input_schema: {
      type: "object",
      properties: {
        applications: {
          type: "array",
          items: {
            type: "object",
            properties: {
              shapeId: { type: "string" },
              tokenName: { type: "string", description: "e.g. color.brand.primary" },
              properties: {
                type: "array",
                items: { type: "string", enum: ["fill", "stroke"] },
                description: "Which properties to bind (default: fill)",
              },
            },
            required: ["shapeId", "tokenName"],
          },
        },
      },
      required: ["applications"],
    },
  },
  {
    name: "create_color_token",
    description: "Creates a color design token in the file library (set defaults to 'core').",
    input_schema: {
      type: "object",
      properties: {
        name: { type: "string", description: "e.g. color.brand.primary" },
        value: { type: "string", description: "hex value, e.g. #6366f1" },
        set: { type: "string" },
      },
      required: ["name", "value"],
    },
  },
  {
    name: "execute_code",
    description:
      "Executes JavaScript against the Penpot plugin API (variable `penpot`, plus `console`). The workhorse for creating and modifying shapes, boards, text and layout. Writes are gated by the file's enforced rules — a rejected write throws citing the rule. Return a JSON-serializable value. " +
      "API gotchas: shape.width/height are READ-ONLY — use shape.resize(w, h); nest shapes with board.appendChild(shape); components are created with penpot.library.local.createComponent([shapes]); prefer several smaller code blocks over one huge one (each call has a time budget) and re-read state instead of assuming a failed call did nothing.",
    input_schema: {
      type: "object",
      properties: { code: { type: "string" } },
      required: ["code"],
    },
  },
];

const OP_BY_TOOL: Record<string, string> = {
  read_design: "read-design",
  audit_file: "audit-file",
  apply_tokens: "apply-tokens",
  create_color_token: "create-color-token",
  execute_code: "execute-code",
};

async function executeTool(name: string, input: Record<string, unknown>): Promise<unknown> {
  if (name === "get_design_skills") {
    const skills = await bridge.call<SkillsPayload>("get-skills");
    const enabled = skills.effective.filter((s) => !s.disabled);
    if (typeof input.name === "string") {
      const skill = enabled.find((s) => s.name === input.name);
      return skill ? { name: skill.name, enforcement: skill.enforcement, body: skill.body }
                   : { error: `No skill named ${input.name}` };
    }
    if (input.include_bodies) {
      return enabled.map((s) => ({
        name: s.name, kind: s.kind, scope: s.definedAt, enforcement: s.enforcement, body: s.body,
      }));
    }
    return skillManifest(skills.effective);
  }
  const op = OP_BY_TOOL[name];
  if (!op) throw new Error(`Unknown tool: ${name}`);
  // canvas-building code can legitimately run for minutes; reads stay snappy
  const timeoutMs = name === "execute_code" || name === "apply_tokens" ? 180_000 : 30_000;
  return bridge.call(op, input, { timeoutMs });
}

/* ------------------------------------------------------------------ */
/* System prompt                                                       */
/* ------------------------------------------------------------------ */

export function buildSystemPrompt(skills: SkillsPayload, context: unknown): string {
  // Skills are knowledge (playbooks/conventions); rules are constraints about
  // the artifact. Platform skills (the curated penpot-ai-kit set) are listed
  // as a routing manifest and fetched on demand — inlining their bodies would
  // consume the whole context. Local skills are small and ride along in full.
  const enabled = skills.effective.filter((s) => !s.disabled);
  const platformSkills = enabled.filter((s) => s.definedAt === "platform" && s.kind === "skill");
  const rules = enabled.filter((s) => s.kind === "rule");
  const localSkills = enabled.filter((s) => s.definedAt !== "platform" && s.kind === "skill");

  const platformIndex = platformSkills
    .map((s) => `- **${s.name}**${s.mandatory ? " (mandatory)" : ""}: ${s.description}`)
    .join("\n");

  const rulesManifest = rules.map((s) => ({
    name: s.name,
    enforcement: s.enforcement,
    mandatory: s.mandatory,
    scope: s.definedAt,
    description: s.description,
  }));

  const localSkillBodies = localSkills
    .map((s) => `### ${s.name} (${s.definedAt})\n${s.body}`)
    .join("\n\n");

  return [
    "You are the design agent embedded in Penpot (open-source design tool), working on the user's current file through design tools.",
    "",
    "Start each task with read_design (one call: context, tokens, skills, open violation count).",
    "",
    "## Skill routing (platform scope — the curated penpot-ai-kit set)",
    "These skills are your playbooks. Do NOT guess their content: before starting a task that matches one, fetch its full body with get_design_skills({name}) and follow it. Fetch `penpot-plugin-api-gotchas` before your FIRST canvas-mutating execute_code of the session — it prevents the most common API mistakes.",
    platformIndex,
    "",
    "## Rules governing this file",
    "Rules are constraints about the artifact, separate from skills:",
    "- `enforced` rules are gated structurally in Penpot's write path — violating writes are REJECTED with an error citing the rule. Read the error and self-correct (apply_tokens is the safe path for colors).",
    "- `triggered` and `advisory` rules are watched: violations accumulate in the file's audit ledger. Use audit_file to see them; when asked to fix them, fix shape by shape and re-run audit_file to confirm.",
    "```json",
    JSON.stringify(rulesManifest, null, 2),
    "```",
    "",
    "## Skills local to this org/project/file (full text, follow as context)",
    localSkillBodies || "(none)",
    "",
    "## Operating modes (governance, distilled from penpot-operating-modes)",
    "- Suggest: audits/reviews propose changes as a report; touch nothing.",
    "- Apply-with-review (default for generative work): make the change, then summarize what changed and pause for direction on large next steps.",
    "- Auto-fix without asking ONLY for the safe set: renaming auto-named layers, loss-less raw-value→token swaps, adding documentation/metadata.",
    "- Never without explicit approval: deleting/restructuring components or shared assets, detach() on instances, large destructive geometry changes.",
    "",
    "## Current design context",
    "```json",
    JSON.stringify(context, null, 2),
    "```",
    "",
    "Be hands-on: when the user asks you to design or create something, orient with read_design and then BUILD it in the same turn — execute_code for structure, apply_tokens for color — instead of stopping at a plan. If needed tokens don't exist yet, create them first (create_color_token), then use them.",
    "Work in small steps, confirm what you changed, reference shapes by name. Keep replies short — you live in a 400px side panel.",
  ].join("\n");
}

/* ------------------------------------------------------------------ */
/* History management                                                  */
/* ------------------------------------------------------------------ */

/**
 * Context memory management: design-state tool results (read_design, audits,
 * execute_code dumps) go stale the moment the canvas changes, yet they are the
 * bulk of the history. Before each turn, results outside the recent window are
 * replaced with a stub — the model re-reads live state cheaply when it needs
 * it. The recent window stays intact so multi-step work keeps its grounding.
 */
const PRUNE_KEEP_RECENT_MESSAGES = 8;
const PRUNE_STUB =
  "[stale tool result elided to conserve context — call the tool again if current state is needed]";

function pruneStaleToolResults(messages: CanonicalMessage[]): void {
  const cutoff = messages.length - PRUNE_KEEP_RECENT_MESSAGES;
  for (let i = 0; i < cutoff; i++) {
    const m = messages[i];
    if (m.role !== "tool_results") continue;
    for (const r of m.results) {
      if (r.content.length > 400) r.content = PRUNE_STUB;
    }
  }
}

/* ------------------------------------------------------------------ */
/* Wire encoding/decoding                                              */
/* ------------------------------------------------------------------ */

/** Providers that speak the Anthropic Messages API; the rest are
 * OpenAI-compatible (chat.completions). */
function isAnthropic(provider: string): boolean {
  return provider === "anthropic";
}

function encodeAnthropic(messages: CanonicalMessage[]): unknown[] {
  const out: unknown[] = [];
  for (const m of messages) {
    if (m.role === "user") {
      out.push({ role: "user", content: m.text });
    } else if (m.role === "assistant") {
      const blocks: unknown[] = [];
      if (m.text) blocks.push({ type: "text", text: m.text });
      for (const c of m.toolCalls) {
        blocks.push({ type: "tool_use", id: c.id, name: c.name, input: c.input });
      }
      // an assistant message must carry at least one block
      out.push({ role: "assistant", content: blocks.length ? blocks : [{ type: "text", text: "…" }] });
    } else {
      out.push({
        role: "user",
        content: m.results.map((r) => ({
          type: "tool_result",
          tool_use_id: r.id,
          content: r.content,
          is_error: r.isError || undefined,
        })),
      });
    }
  }
  return out;
}

function encodeOpenAI(system: string, messages: CanonicalMessage[]): unknown[] {
  const out: unknown[] = [{ role: "system", content: system }];
  for (const m of messages) {
    if (m.role === "user") {
      out.push({ role: "user", content: m.text });
    } else if (m.role === "assistant") {
      out.push({
        role: "assistant",
        content: m.text || null,
        ...(m.toolCalls.length > 0
          ? {
              tool_calls: m.toolCalls.map((c) => ({
                id: c.id,
                type: "function",
                function: { name: c.name, arguments: JSON.stringify(c.input ?? {}) },
              })),
            }
          : {}),
      });
    } else {
      for (const r of m.results) {
        out.push({ role: "tool", tool_call_id: r.id, content: r.content });
      }
    }
  }
  return out;
}

function openAITools() {
  return TOOLS.map((t) => ({
    type: "function",
    function: { name: t.name, description: t.description, parameters: t.input_schema },
  }));
}

/** Parses a provider response body, throwing a readable error on failure. */
function parseRound(round: { status: number; body: string }): Record<string, any> {
  let parsed: Record<string, any>;
  try {
    parsed = JSON.parse(round.body);
  } catch {
    throw new Error(`The provider returned a non-JSON response (status ${round.status}).`);
  }
  if (round.status < 200 || round.status >= 300) {
    const message =
      parsed?.error?.message ?? parsed?.message ?? `provider error (status ${round.status})`;
    throw new Error(String(message));
  }
  return parsed;
}

interface RoundOutcome {
  text: string;
  toolCalls: CanonicalToolCall[];
  stoppedForLength: boolean;
  usage: UsageTotals;
}

function decodeAnthropic(parsed: Record<string, any>): RoundOutcome {
  const content: { type: string; text?: string; id?: string; name?: string; input?: unknown }[] =
    parsed.content ?? [];
  return {
    text: content.filter((b) => b.type === "text").map((b) => b.text ?? "").join(""),
    toolCalls: content
      .filter((b) => b.type === "tool_use")
      .map((b) => ({
        id: b.id as string,
        name: b.name as string,
        input: (b.input ?? {}) as Record<string, unknown>,
      })),
    stoppedForLength: parsed.stop_reason === "max_tokens",
    usage: {
      inputTokens: parsed.usage?.input_tokens ?? 0,
      outputTokens: parsed.usage?.output_tokens ?? 0,
      cacheReadTokens: parsed.usage?.cache_read_input_tokens ?? 0,
      cacheWriteTokens: parsed.usage?.cache_creation_input_tokens ?? 0,
      requests: 1,
    },
  };
}

function decodeOpenAI(parsed: Record<string, any>): RoundOutcome {
  const choice = parsed.choices?.[0];
  const message = choice?.message ?? {};
  const toolCalls: { id: string; function: { name: string; arguments: string } }[] =
    message.tool_calls ?? [];
  return {
    text: typeof message.content === "string" ? message.content : "",
    toolCalls: toolCalls.map((c) => {
      let input: Record<string, unknown> = {};
      try {
        input = JSON.parse(c.function.arguments || "{}");
      } catch {
        /* malformed arguments — run the tool with none and let it complain */
      }
      return { id: c.id, name: c.function.name, input };
    }),
    stoppedForLength: choice?.finish_reason === "length",
    usage: {
      inputTokens: parsed.usage?.prompt_tokens ?? 0,
      outputTokens: parsed.usage?.completion_tokens ?? 0,
      cacheReadTokens: parsed.usage?.prompt_tokens_details?.cached_tokens ?? 0,
      cacheWriteTokens: 0,
      requests: 1,
    },
  };
}

function buildRoundBody(
  settings: AgentSettings,
  messages: CanonicalMessage[],
  system: string,
): string {
  if (isAnthropic(settings.provider)) {
    return JSON.stringify({
      model: settings.model,
      // generous budget: models with adaptive thinking spend a chunk of it
      // reasoning before any visible output — a small budget can be consumed
      // entirely by thinking, yielding an empty (and silent) reply
      max_tokens: 32000,
      // the system block marker caches tools+system across rounds at ~0.1×
      system: [{ type: "text", text: system, cache_control: { type: "ephemeral" } }],
      tools: TOOLS,
      messages: encodeAnthropic(messages),
    });
  }
  const body: Record<string, unknown> = {
    model: settings.model,
    messages: encodeOpenAI(system, messages),
    tools: openAITools(),
  };
  // OpenAI deprecated max_tokens on newer models; the compat providers
  // still expect it
  if (settings.provider === "openai") body.max_completion_tokens = 16000;
  else body.max_tokens = 16000;
  return JSON.stringify(body);
}

/* ------------------------------------------------------------------ */
/* Turn loop                                                           */
/* ------------------------------------------------------------------ */

export interface AgentCallbacks {
  /** Full assistant text of one round (buffered — no token streaming). */
  onAssistantDone: (fullText: string) => void;
  onToolEvent: (e: ToolEvent) => void;
  /** Fired after every API round with that round's token usage. */
  onUsage?: (usage: UsageTotals) => void;
}

export interface AgentSettings {
  provider: string;
  model: string;
}

const MAX_ROUNDS = 32;

/**
 * Runs one user turn: sends buffered rounds through the backend proxy,
 * executes tool calls via the plugin bridge (where enforcement lives), feeds
 * results back, and repeats until the model stops calling tools. Returns the
 * updated canonical history.
 */
export async function runTurn(
  settings: AgentSettings,
  history: CanonicalMessage[],
  system: string,
  cb: AgentCallbacks,
): Promise<CanonicalMessage[]> {
  const messages = [...history];
  pruneStaleToolResults(messages);

  for (let round = 0; round < MAX_ROUNDS; round++) {
    const roundResult = await bridge.aiRound(
      settings.provider,
      buildRoundBody(settings, messages, system),
    );
    const parsed = parseRound(roundResult);
    const outcome = isAnthropic(settings.provider)
      ? decodeAnthropic(parsed)
      : decodeOpenAI(parsed);

    cb.onUsage?.(outcome.usage);
    if (outcome.text) cb.onAssistantDone(outcome.text);
    messages.push({ role: "assistant", text: outcome.text, toolCalls: outcome.toolCalls });

    // never end a turn silently
    if (!outcome.text && outcome.toolCalls.length === 0) {
      cb.onAssistantDone(
        outcome.stoppedForLength
          ? "⚠️ I ran out of output budget before finishing — please send the request again."
          : "⚠️ The model returned no visible output — try rephrasing the request.",
      );
      break;
    }
    if (outcome.toolCalls.length === 0) break;

    const results: CanonicalToolResult[] = [];
    for (const call of outcome.toolCalls) {
      cb.onToolEvent({ id: call.id, name: call.name, input: call.input, status: "running" });
      try {
        const result = await executeTool(call.name, call.input);
        results.push({
          id: call.id,
          content: JSON.stringify(result ?? null).slice(0, 20_000),
          isError: false,
        });
        cb.onToolEvent({ id: call.id, name: call.name, input: call.input, status: "ok" });
      } catch (e) {
        const err = e as Error & { rule?: string };
        results.push({ id: call.id, content: err.message, isError: true });
        cb.onToolEvent({
          id: call.id,
          name: call.name,
          input: call.input,
          status: err.rule ? "rejected" : "error",
          detail: err.message,
          rule: err.rule,
        });
      }
    }
    messages.push({ role: "tool_results", results });
  }
  return messages;
}
