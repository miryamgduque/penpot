/**
 * The embedded chat agent: a provider loop against the Anthropic API (BYOK,
 * called directly from the plugin iframe — no server of ours in the middle),
 * with design tools that execute inside Penpot via the plugin bridge.
 *
 * Provider-agnosticism note: skills reach the model as plain tool results and
 * system text; nothing here depends on Anthropic-specific behavior beyond the
 * transport, and the enforced rules are gated in the plugin runtime anyway.
 */

import Anthropic from "@anthropic-ai/sdk";
import type {
  MessageParam,
  Tool,
  ToolResultBlockParam,
  ContentBlock,
} from "@anthropic-ai/sdk/resources/messages";
import * as bridge from "./bridge";
import type { SkillsPayload } from "./bridge";
import { skillManifest } from "../skills/resolve";

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

/** $ per million tokens (standard list price; cache read ≈ 0.1×, write ≈ 1.25×). */
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

export const TOOLS: Tool[] = [
  {
    name: "get_design_context",
    description:
      "Returns the current Penpot file/page name, the selected shapes and the top-level shapes on the page (ids, names, geometry, fills).",
    input_schema: { type: "object", properties: {} },
  },
  {
    name: "get_design_skills",
    description:
      "Returns the effective design skills for this file (four-scope cascade already resolved). By default a lean manifest; pass a name to get one skill's full markdown body.",
    input_schema: {
      type: "object",
      properties: {
        name: { type: "string", description: "Return the full body of this skill only" },
        include_bodies: { type: "boolean", description: "Include full bodies for all skills" },
      },
    },
  },
  {
    name: "get_color_tokens",
    description:
      "Lists this file's color design tokens (name, resolved value, set, active) and library colors. Colors must come from here when the token-only-colors rule is enforced.",
    input_schema: { type: "object", properties: {} },
  },
  {
    name: "set_fill",
    description:
      "Sets a shape's fill. Prefer tokenName (applies the design token). A raw color is validated against the enforced skills and will be REJECTED if it is not a token value.",
    input_schema: {
      type: "object",
      properties: {
        shapeId: { type: "string" },
        tokenName: { type: "string", description: "Name of a color token to apply" },
        color: { type: "string", description: "Raw hex color — subject to enforcement" },
      },
      required: ["shapeId"],
    },
  },
  {
    name: "create_shape",
    description: "Creates a board, rectangle, ellipse or text shape.",
    input_schema: {
      type: "object",
      properties: {
        kind: { type: "string", enum: ["board", "rectangle", "ellipse", "text"] },
        name: { type: "string" },
        x: { type: "number" },
        y: { type: "number" },
        width: { type: "number" },
        height: { type: "number" },
        parentId: { type: "string" },
        text: { type: "string" },
        fontSize: { type: "number" },
        fillTokenName: { type: "string" },
        fillColor: { type: "string", description: "Raw hex — subject to enforcement" },
      },
      required: ["kind"],
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
    name: "rename_shape",
    description: "Renames a shape/layer.",
    input_schema: {
      type: "object",
      properties: { shapeId: { type: "string" }, name: { type: "string" } },
      required: ["shapeId", "name"],
    },
  },
  {
    name: "execute_code",
    description:
      "Executes JavaScript against the Penpot plugin API (variable `penpot`, plus `console`). Use for anything the structured tools don't cover. Writes are gated by the file's enforced skills — a rejected write throws citing the rule. Return a JSON-serializable value. " +
      "API gotchas: shape.width/height are READ-ONLY — use shape.resize(w, h); nest shapes with board.appendChild(shape); components are created with penpot.library.local.createComponent([shapes]); prefer several smaller code blocks over one huge one (each call has a time budget) and re-read state instead of assuming a failed call did nothing.",
    input_schema: {
      type: "object",
      properties: { code: { type: "string" } },
      required: ["code"],
    },
  },
];

const OP_BY_TOOL: Record<string, string> = {
  get_design_context: "get-design-context",
  get_color_tokens: "get-color-tokens",
  set_fill: "set-fill",
  create_shape: "create-shape",
  create_color_token: "create-color-token",
  rename_shape: "rename-shape",
  execute_code: "execute-code",
};

async function executeTool(name: string, input: Record<string, unknown>): Promise<unknown> {
  if (name === "get_design_skills") {
    const skills = await bridge.call<SkillsPayload>("get-skills");
    if (typeof input.name === "string") {
      const skill = skills.effective.find((s) => s.name === input.name);
      return skill ? { name: skill.name, enforcement: skill.enforcement, body: skill.body }
                   : { error: `No skill named ${input.name}` };
    }
    if (input.include_bodies) {
      return skills.effective.map((s) => ({
        name: s.name, scope: s.definedAt, enforcement: s.enforcement, body: s.body,
      }));
    }
    return skillManifest(skills.effective);
  }
  const op = OP_BY_TOOL[name];
  if (!op) throw new Error(`Unknown tool: ${name}`);
  // canvas-building code can legitimately run for minutes; reads stay snappy
  const timeoutMs = name === "execute_code" || name === "create_shape" ? 180_000 : 30_000;
  return bridge.call(op, input, { timeoutMs });
}

export function buildSystemPrompt(skills: SkillsPayload, context: unknown): string {
  // Platform skills (the curated penpot-ai-kit set) are listed as a routing
  // manifest and fetched on demand — inlining their bodies would consume the
  // whole context. Org/project/file skills are small and user-authored, so
  // their advisory bodies ride along in full.
  const platform = skills.effective.filter((s) => s.definedAt === "platform");
  const local = skills.effective.filter((s) => s.definedAt !== "platform");

  const platformIndex = platform
    .map((s) => `- **${s.name}**${s.mandatory ? " (mandatory)" : ""}: ${s.description}`)
    .join("\n");

  const localManifest = skillManifest(local);
  const localAdvisoryBodies = local
    .filter((s) => s.enforcement === "advisory")
    .map((s) => `### ${s.name} (${s.definedAt})\n${s.body}`)
    .join("\n\n");

  return [
    "You are the design agent embedded in Penpot (open-source design tool), working on the user's current file through design tools.",
    "",
    "## Skill routing (platform scope — the curated penpot-ai-kit set)",
    "These skills are your playbooks. Do NOT guess their content: before starting a task that matches one, fetch its full body with get_design_skills({name}) and follow it. Fetch `penpot-plugin-api-gotchas` before your FIRST canvas-mutating execute_code of the session — it prevents the most common API mistakes.",
    platformIndex,
    "",
    "## Operating modes (governance, distilled from penpot-operating-modes)",
    "- Suggest: audits/reviews propose changes as a report; touch nothing.",
    "- Apply-with-review (default for generative work): make the change, then summarize what changed and pause for direction on large next steps.",
    "- Auto-fix without asking ONLY for the safe set: renaming auto-named layers, loss-less raw-value→token swaps, adding documentation/metadata.",
    "- Never without explicit approval: deleting/restructuring components or shared assets, detach() on instances, large destructive geometry changes.",
    "",
    "## Skills local to this org/project/file",
    "```json",
    JSON.stringify(localManifest, null, 2),
    "```",
    "",
    "Advisory local skills (full text, follow them as context):",
    localAdvisoryBodies,
    "",
    "Rules marked `enforced` are gated structurally in Penpot's write path: violating writes are rejected with an error citing the rule, regardless of what you intend. If a write is rejected, read the error, use get_color_tokens / get_design_skills, and self-correct.",
    "Rules marked `triggered` are surfaced by Penpot when the relevant change happens; when the user forwards one, apply it.",
    "",
    "## Current design context",
    "```json",
    JSON.stringify(context, null, 2),
    "```",
    "",
    "Be hands-on: when the user asks you to design or create something, gather context (skills, tokens) and then BUILD it with the write tools in the same turn — boards, shapes, text, token fills — instead of stopping at a plan. If needed tokens don't exist yet, create them first (create_color_token), then use them.",
    "Work in small steps, confirm what you changed, reference shapes by name. Keep replies short — you live in a 400px side panel.",
  ].join("\n");
}

export interface AgentCallbacks {
  onTextDelta: (text: string) => void;
  onToolEvent: (e: ToolEvent) => void;
  onAssistantDone: (fullText: string) => void;
  /** Fired after every API round with that round's token usage. */
  onUsage?: (usage: UsageTotals) => void;
}

export interface AgentSettings {
  apiKey: string;
  model: string;
}

/**
 * Runs one user turn: streams assistant output, executes tool calls via the
 * plugin bridge (where enforcement lives), feeds results back, and repeats
 * until the model stops calling tools. Returns the updated history.
 */
export async function runTurn(
  settings: AgentSettings,
  history: MessageParam[],
  system: string,
  cb: AgentCallbacks,
): Promise<MessageParam[]> {
  const client = new Anthropic({ apiKey: settings.apiKey, dangerouslyAllowBrowser: true });
  const messages = [...history];

  for (let round = 0; round < 32; round++) {
    const stream = client.messages.stream({
      model: settings.model,
      // generous budget: models with adaptive thinking spend a chunk of it
      // reasoning before any visible output — a small budget can be consumed
      // entirely by thinking, yielding an empty (and silent) reply
      max_tokens: 32000,
      // Prompt caching: the system block marker caches tools+system; the
      // top-level marker auto-caches the last message block, so each round of
      // a multi-round turn re-reads the whole prior prefix at ~0.1× price
      // instead of re-paying full input price for the growing history.
      cache_control: { type: "ephemeral" },
      system: [{ type: "text", text: system, cache_control: { type: "ephemeral" } }],
      tools: TOOLS,
      messages,
    });
    let turnText = "";
    stream.on("text", (delta) => {
      turnText += delta;
      cb.onTextDelta(delta);
    });
    const final = await stream.finalMessage();
    cb.onUsage?.({
      inputTokens: final.usage.input_tokens ?? 0,
      outputTokens: final.usage.output_tokens ?? 0,
      cacheReadTokens: final.usage.cache_read_input_tokens ?? 0,
      cacheWriteTokens: final.usage.cache_creation_input_tokens ?? 0,
      requests: 1,
    });
    if (turnText) cb.onAssistantDone(turnText);
    messages.push({ role: "assistant", content: final.content });

    const toolUses = final.content.filter(
      (b: ContentBlock): b is Extract<ContentBlock, { type: "tool_use" }> => b.type === "tool_use",
    );

    // never end a turn silently
    if (!turnText && toolUses.length === 0) {
      cb.onAssistantDone(
        final.stop_reason === "max_tokens"
          ? "⚠️ I ran out of output budget before finishing — please send the request again."
          : "⚠️ The model returned no visible output — try rephrasing the request.",
      );
      break;
    }

    if (final.stop_reason !== "tool_use" || toolUses.length === 0) break;

    const results: ToolResultBlockParam[] = [];
    for (const block of toolUses) {
      cb.onToolEvent({ id: block.id, name: block.name, input: block.input, status: "running" });
      try {
        const result = await executeTool(block.name, (block.input ?? {}) as Record<string, unknown>);
        results.push({
          type: "tool_result",
          tool_use_id: block.id,
          content: JSON.stringify(result ?? null).slice(0, 20_000),
        });
        cb.onToolEvent({ id: block.id, name: block.name, input: block.input, status: "ok" });
      } catch (e) {
        const err = e as Error & { rule?: string };
        results.push({
          type: "tool_result",
          tool_use_id: block.id,
          content: err.message,
          is_error: true,
        });
        cb.onToolEvent({
          id: block.id,
          name: block.name,
          input: block.input,
          status: err.rule ? "rejected" : "error",
          detail: err.message,
          rule: err.rule,
        });
      }
    }
    messages.push({ role: "user", content: results });
  }
  return messages;
}
