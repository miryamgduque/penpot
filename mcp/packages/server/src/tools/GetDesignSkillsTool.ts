import { z } from "zod";
import { Tool } from "../Tool";
import type { ToolResponse } from "../ToolResponse";
import { TextResponse } from "../ToolResponse";
import "reflect-metadata";
import { PenpotMcpServer } from "../PenpotMcpServer";
import { ExecuteCodePluginTask } from "../tasks/ExecuteCodePluginTask";
import { BUILTIN_SKILL_SOURCES, parseSkill, resolveCascade, Skill } from "../skills/SkillsCascade";

/**
 * Arguments class for GetDesignSkillsTool
 */
export class GetDesignSkillsArgs {
    static schema = {
        name: z
            .string()
            .optional()
            .describe("Return the full markdown body of this one skill instead of the manifest."),
        include_bodies: z
            .boolean()
            .optional()
            .describe("Include full markdown bodies for all skills (larger response)."),
    };

    name?: string;
    include_bodies?: boolean;
}

/**
 * Tool that returns the design skills governing the currently open Penpot file.
 *
 * Skills are layered (platform → org → project → file) and cascade-resolved;
 * file-scope skills are read live from the design file itself (shared
 * pluginData written by the Penpot Skills plugin), so any agent connecting
 * through any MCP client inherits the same design conventions.
 */
export class GetDesignSkillsTool extends Tool<GetDesignSkillsArgs> {
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, GetDesignSkillsArgs.schema);
    }

    public getToolName(): string {
        return "get_design_skills";
    }

    public getToolDescription(): string {
        return (
            "Returns the design skills (conventions and rules) that govern the currently open Penpot file. " +
            "Call this ONCE at the start of any design session, BEFORE modifying the file.\n" +
            "Skills are defined at four scopes (platform, org, project, file) and merged by a cascade: " +
            "the file is the most specific, but rules marked mandatory at broader scopes cannot be loosened.\n" +
            "Each skill has an enforcement level: 'advisory' (context you should follow), 'triggered' " +
            "(surfaced on relevant actions), or 'enforced' (the Penpot write path REJECTS violating " +
            "operations — e.g. token-only-colors rejects raw hex fills; if a write fails citing a rule, " +
            "adapt instead of retrying).\n" +
            "By default returns a lean manifest (name, scope, enforcement, description). " +
            "Pass name=<skill> for one full body, or include_bodies=true for all."
        );
    }

    protected async executeCore(args: GetDesignSkillsArgs): Promise<ToolResponse> {
        // file-scope skills live in the design file itself; read them through the plugin bridge
        const task = new ExecuteCodePluginTask({
            code: "return penpot.currentFile ? penpot.currentFile.getSharedPluginData('penpot-skills', 'skills') : null;",
        });
        const result = await this.mcpServer.pluginBridge.executePluginTask(task);

        let fileSources: string[] = [];
        const raw = (result.data as { result?: unknown } | undefined)?.result;
        if (typeof raw === "string" && raw.length > 0) {
            try {
                const parsed = JSON.parse(raw);
                if (Array.isArray(parsed)) fileSources = parsed.filter((s) => typeof s === "string");
            } catch {
                // malformed pluginData — treat as no file skills
            }
        }

        const skills: Skill[] = [
            ...BUILTIN_SKILL_SOURCES.map((b) => parseSkill(b.source, b.scope)),
            ...fileSources.map((s) => parseSkill(s, "file")),
        ];
        const effective = resolveCascade(skills);

        if (args.name) {
            const skill = effective.find((s) => s.name === args.name);
            return new TextResponse(
                skill
                    ? JSON.stringify(
                          {
                              name: skill.name,
                              scope: skill.definedAt,
                              enforcement: skill.enforcement,
                              mandatory: skill.mandatory,
                              body: skill.body,
                          },
                          null,
                          2
                      )
                    : `No skill named "${args.name}". Available: ${effective.map((s) => s.name).join(", ")}`
            );
        }

        const manifest = effective.map((s) => ({
            name: s.name,
            scope: s.definedAt,
            enforcement: s.enforcement,
            mandatory: s.mandatory,
            trigger: s.trigger,
            description: s.description,
            ...(args.include_bodies ? { body: s.body } : {}),
            ...(s.enforcementRaisedBy ? { enforcementRaisedBy: s.enforcementRaisedBy } : {}),
        }));

        return new TextResponse(
            JSON.stringify(
                {
                    skills: manifest,
                    note:
                        "Skills with enforcement='enforced' are gated structurally in the Penpot write path. " +
                        "Follow advisory skills as design context. " +
                        (fileSources.length === 0
                            ? "This file defines no file-scope skills yet (open the Penpot Skills plugin once to seed defaults)."
                            : `${fileSources.length} skill(s) come from the design file itself.`),
                },
                null,
                2
            )
        );
    }
}
