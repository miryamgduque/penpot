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
      "Executes JavaScript against the Penpot plugin API (variable `penpot`, plus `console`). Use for anything the structured tools don't cover. Writes are gated by the file's enforced skills — a rejected write throws citing the rule. Return a JSON-serializable value.",
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
  return bridge.call(op, input);
}

export function buildSystemPrompt(skills: SkillsPayload, context: unknown): string {
  const manifest = skillManifest(skills.effective);
  const advisoryBodies = skills.effective
    .filter((s) => s.enforcement === "advisory")
    .map((s) => `### ${s.name} (${s.definedAt})\n${s.body}`)
    .join("\n\n");

  return [
    "You are the design agent embedded in Penpot (open-source design tool), working on the user's current file through design tools.",
    "",
    "## Design skills for this file",
    "This file carries a layered skill set (platform → org → project → file, cascade-resolved). It is your single source of design conventions — you inherited it from the file, not from any vendor:",
    "```json",
    JSON.stringify(manifest, null, 2),
    "```",
    "",
    "Advisory skills (full text, follow them as context):",
    advisoryBodies,
    "",
    "Rules marked `enforced` are gated structurally in Penpot's write path: violating writes are rejected with an error citing the rule, regardless of what you intend. If a write is rejected, read the error, use get_color_tokens / get_design_skills, and self-correct.",
    "Rules marked `triggered` are surfaced by Penpot when the relevant change happens; when the user forwards one, apply it.",
    "",
    "## Current design context",
    "```json",
    JSON.stringify(context, null, 2),
    "```",
    "",
    "Work in small steps, confirm what you changed, reference shapes by name. Keep replies short — you live in a 400px side panel.",
  ].join("\n");
}

export interface AgentCallbacks {
  onTextDelta: (text: string) => void;
  onToolEvent: (e: ToolEvent) => void;
  onAssistantDone: (fullText: string) => void;
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

  for (let round = 0; round < 16; round++) {
    const stream = client.messages.stream({
      model: settings.model,
      max_tokens: 4096,
      system,
      tools: TOOLS,
      messages,
    });
    let turnText = "";
    stream.on("text", (delta) => {
      turnText += delta;
      cb.onTextDelta(delta);
    });
    const final = await stream.finalMessage();
    if (turnText) cb.onAssistantDone(turnText);
    messages.push({ role: "assistant", content: final.content });

    const toolUses = final.content.filter(
      (b: ContentBlock): b is Extract<ContentBlock, { type: "tool_use" }> => b.type === "tool_use",
    );
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
