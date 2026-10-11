// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC Sucursal en España SL

import { Context, Service } from "@deepseek-ai/cordis";
import { AgentRegistry } from "@deepseek-ai/dsh-agent";
import { AgentLoop } from "@deepseek-ai/dsh-agent-loop";
import AttachmentLocal from "@deepseek-ai/dsh-attachment-local";
import CompactionBasic from "@deepseek-ai/dsh-compaction-basic";
import { LlmRuntime, createUserMessage } from "@deepseek-ai/dsh-llm";
import * as LlmPiAi from "@deepseek-ai/dsh-llm-pi-ai";
import { SessionId, SessionStore } from "@deepseek-ai/dsh-session";
import SessionPersistenceJsonl from "@deepseek-ai/dsh-session-persistence-jsonl";
import { SessionProjectionRegistry } from "@deepseek-ai/dsh-session-projection";
import SkillRegistry from "@deepseek-ai/dsh-skill";
import { SystemPrompt } from "@deepseek-ai/dsh-system-prompt";
import * as SkillTool from "@deepseek-ai/dsh-tool-skill";
import TokenMeter from "@deepseek-ai/dsh-token-meter";
import { ToolRuntime } from "@deepseek-ai/dsh-tools";

import { TurnCredentials, keyRef } from "./credentials.js";

/**
 * Penpot provider id → the harness provider route that serves it.
 */
export const PROVIDER_ROUTES = {
  anthropic: "anthropic",
  openai: "openai",
  zhipu: "zai",
  moonshot: "moonshotai",
};

const MAX_TOKENS = 32000;

// A conversation nobody has spoken in for this long is dropped from memory.
// With session storage configured it is reopened from disk on its next turn.
const IDLE_MS = 15 * 60 * 1000;

/**
 * The harness session id of a Penpot conversation. The owner is part of the
 * id, so a conversation id presented by anyone else names a different — and
 * nonexistent — session rather than reaching the owner's.
 */
export function sessionIdOf(profileId, conversationId) {
  return `penpot-${profileId}-${conversationId}`;
}

function providersConfig(overrides) {
  const providers = {};
  for (const [provider, route] of Object.entries(PROVIDER_ROUTES)) {
    providers[route] = { apiKeyEnv: keyRef(provider), ...overrides?.[route] };
  }
  return providers;
}

/**
 * The harness's skill tool asks where a conversation's project directory is,
 * to find skills on disk. Penpot's skills come from Penpot, not from files,
 * so the answer is always "nowhere".
 */
class NoWorkingDirectory extends Service {
  constructor(ctx) {
    super(ctx, "workingDirectory");
  }

  async ensure() {
    return undefined;
  }
}

/**
 * One harness for the whole process: the agent loop, the model routes and the
 * tool runtime, with no built-in tools. Everything a conversation owns — its
 * tools, its system prompt, its key — is registered on that conversation's
 * own scope, never on the shared context.
 */
export async function createHarness(options = {}) {
  const ctx = new Context();
  await ctx.plugin(LlmRuntime);
  await ctx.plugin(SessionStore);
  await ctx.plugin(SessionProjectionRegistry);
  await ctx.plugin(SystemPrompt, {
    includeHarnessIdentity: false,
    includeRuntimeContext: false,
  });
  await ctx.plugin(ToolRuntime);
  await ctx.plugin(AgentRegistry);
  if (options.attachmentsHome) {
    // images (attached by the user, or rendered by a tool) are stored here
    await ctx.plugin(AttachmentLocal, { dshHome: options.attachmentsHome });
  }
  if (options.sessionsRoot) {
    await ctx.plugin(SessionPersistenceJsonl, { root: options.sessionsRoot, compression: "none" });
  }
  await ctx.plugin(TurnCredentials);
  await ctx.plugin(NoWorkingDirectory);
  await ctx.plugin(SkillRegistry);
  await ctx.plugin(SkillTool);
  await ctx.plugin(AgentLoop, { agents: [] });
  await ctx.plugin(LlmPiAi, { providers: providersConfig(options.providers) });
  // condenses a long conversation before it outgrows the model's window
  await ctx.plugin(TokenMeter);
  await ctx.plugin(CompactionBasic, { auto: true, ...options.compaction });

  /**
   * Stores images and returns the blocks that carry them to the model. With
   * no image store configured, says so in words instead of dropping them.
   */
  async function imageBlocks(images, where) {
    if (!Array.isArray(images) || images.length === 0) return [];
    const store = ctx.get("attachments");
    if (store === undefined) {
      return [{ type: "text", text: `[${images.length} image(s) ${where} were not delivered: no image store is configured.]` }];
    }
    const blocks = [];
    for (const image of images) {
      const attachment = await store.saveImage({
        data: Buffer.from(image.data, "base64"),
        mediaType: image.mediaType,
      });
      blocks.push({ type: "image", attachment });
    }
    return blocks;
  }

  /** sessionId → conversation */
  const conversations = new Map();

  function toolDefinition(conversation, spec) {
    return {
      name: spec.name,
      description: spec.description,
      parameters: spec.inputSchema ?? { type: "object", properties: {} },
      output: {
        schema: {},
        render: (_args, value) => [{ type: "text", text: String(value?.content ?? "") }],
      },
      // images ride beside the text: `execute` returns plain JSON, so the
      // stored image references travel in it and become blocks here
      projectContent: (_exec, result) =>
        !result.isError && Array.isArray(result.value?.images) && result.value.images.length > 0
          ? [...result.content, ...result.value.images]
          : undefined,
      async execute(args, exec) {
        const turn = conversation.turn;
        if (turn === undefined) throw new Error("no browser is connected to run this tool");
        const call = { callId: String(exec.callId), name: spec.name, input: args ?? {} };
        // the backend runs the tools it implements; a connected workspace
        // runs the rest
        const result = turn.serverTools?.has(spec.name)
          ? await turn.callServerTool(call, exec.signal)
          : await turn.callTool(call, exec.signal);
        if (result.isError) throw new Error(result.content || "tool error");
        const images = await imageBlocks(result.images, "returned by this tool");
        return images.length > 0 ? { content: result.content ?? "", images } : { content: result.content ?? "" };
      },
    };
  }

  async function isStored(id) {
    const persistence = ctx.get("sessionPersistence");
    if (persistence === undefined) return false;
    try {
      return (await persistence.stat(SessionId(id))) !== undefined;
    } catch {
      return false;
    }
  }

  async function openConversation(id, { provider, model, system, tools, skills = [] }) {
    const conversation = { id, provider, model, turn: undefined, idleTimer: undefined };
    const resumed = await isStored(id);
    const agentOptions = {
      provider: PROVIDER_ROUTES[provider],
      model,
      maxTokens: options.maxTokens ?? MAX_TOKENS,
    };
    const open = resumed
      ? (rest) => ctx.agents.resume({ resumeSessionId: SessionId(id), ...rest })
      : (rest) => ctx.agents.create({ sessionId: SessionId(id), ...rest });
    conversation.resumed = resumed;
    conversation.handle = await open({
      agentOptions,
      setup: (agentCtx) => {
        agentCtx.systemPrompt.section({
          name: "penpot-design-agent",
          order: 0,
          text: system,
          interpolate: false,
          complete: true,
        });
        for (const spec of tools) agentCtx.tools.register(toolDefinition(conversation, spec));
        // registered on this conversation's scope: one user's enabled skills
        // are invisible to every other conversation
        if (skills.length === 0) {
          // no skills, no loader tool: a tool that can only fail is noise
          agentCtx.tools.restrict({ deny: ["skill"] });
        } else {
          agentCtx.inject(["skills"], (skillCtx) => {
            for (const skill of skills) {
              skillCtx.skills.register({
                name: skill.name,
                description: skill.description,
                content: skill.body,
                source: "custom",
                invocation: { modelInvocable: true, userInvocable: false },
              });
            }
          });
        }
        agentCtx.on("agent/assistant-stream", ({ frame }) => {
          if (frame.type !== "chunk") return;
          const chunk = frame.chunk;
          const emit = conversation.turn?.emit;
          if (emit === undefined) return;
          if (chunk.type === "text-delta") emit({ type: "text-delta", text: chunk.text });
          else if (chunk.type === "reasoning-delta") emit({ type: "thinking-delta", text: chunk.text });
          else if (chunk.type === "usage") {
            emit({
              type: "usage",
              usage: {
                input: chunk.usage.inputTokens ?? 0,
                output: chunk.usage.outputTokens ?? 0,
                cacheRead: chunk.usage.cacheReadTokens ?? 0,
                cacheWrite: chunk.usage.cacheWriteTokens ?? 0,
              },
            });
          }
        });
        agentCtx.on("session/event", (_session, event) => {
          if (event.type === "turn/end") conversation.turn?.ended(event.data.reason);
        });
      },
    });
    conversations.set(id, conversation);
    return conversation;
  }

  async function closeConversation(conversation) {
    clearTimeout(conversation.idleTimer);
    conversations.delete(conversation.id);
    await conversation.handle.dispose();
  }

  /**
   * Runs one user turn. `emit` receives the turn's events; `callTool` runs a
   * tool in the connected workspace and resolves with `{content, isError}`;
   * `callServerTool` does the same for the tools named in `serverTools`, which
   * the backend runs. Resolves when the turn is over; `signal` cancels it.
   */
  async function runTurn(request, { emit, callTool, callServerTool, serverTools, signal }) {
    const { sessionId, profileId, provider, apiKey, text } = request;
    if (PROVIDER_ROUTES[provider] === undefined) throw new Error(`unknown provider: ${provider}`);

    const id = sessionIdOf(profileId, sessionId);
    let conversation = conversations.get(id);
    if (conversation !== undefined && conversation.turn !== undefined) {
      throw Object.assign(new Error("a turn is already running in this conversation"), { status: 409 });
    }
    conversation ??= await openConversation(id, request);
    clearTimeout(conversation.idleTimer);

    ctx.credentials.setKeys(id, { [keyRef(provider)]: apiKey });

    let endReason;
    conversation.turn = {
      emit,
      callTool,
      callServerTool,
      serverTools: callServerTool ? serverTools : undefined,
      ended: (reason) => (endReason = reason),
    };
    const agent = conversation.handle.agent;
    const onAbort = () => agent.cancel({ kind: "user" });
    signal?.addEventListener("abort", onAbort, { once: true });
    try {
      const attached = await imageBlocks(request.images, "attached to this message");
      agent.followup(
        createUserMessage({ content: [...attached, { type: "text", text }], source: { kind: "user" } }),
      );
      await agent.whenIdle();
      if (endReason?.kind === "error") {
        const failure = endReason.error;
        throw new Error(failure?.message ?? failure?.code ?? "the model request failed");
      }
      return { reason: endReason?.kind ?? "completed", resumed: conversation.resumed };
    } finally {
      signal?.removeEventListener("abort", onAbort);
      conversation.turn = undefined;
      ctx.credentials.clearKeys(id);
      conversation.idleTimer = setTimeout(() => {
        if (conversation.turn === undefined) closeConversation(conversation).catch(() => {});
      }, options.idleMs ?? IDLE_MS);
      conversation.idleTimer.unref?.();
    }
  }

  async function dispose() {
    for (const conversation of [...conversations.values()]) await closeConversation(conversation);
    await ctx.fiber.dispose();
  }

  return { ctx, runTurn, dispose, conversations };
}
