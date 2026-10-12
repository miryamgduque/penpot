/**
 * What a turn did with recipes: looked for one, found one, built one, or
 * built by hand. Kept per turn so the hit rate can be worked out: of the
 * conversations that found a recipe, how many built one.
 *
 * Counts and recipe names only: nothing of what the user wrote.
 */
import { appendFile, mkdir } from "node:fs/promises";
import { dirname } from "node:path";

// the tools that build structure from primitives
const BY_HAND = new Set(["build_tree", "create_shape", "create_text"]);

function parsed(content) {
  try {
    const value = JSON.parse(content);
    return value !== null && typeof value === "object" ? value : {};
  } catch {
    return {};
  }
}

export function recipeUse() {
  const use = { searched: 0, found: 0, built: 0, byHand: 0, recipes: [], instanced: 0, fromLibrary: 0 };
  return {
    /** Takes note of one tool call and its result. */
    note(call, result) {
      if (result?.isError) return;
      if (call.name === "find_recipes") {
        use.searched += 1;
        if (Array.isArray(parsed(result.content).matches) && parsed(result.content).matches.length > 0) use.found += 1;
      } else if (call.name === "build_recipe") {
        // a build that stopped part-way answers with what exists, and an error
        if (parsed(result.content).error === undefined) {
          use.built += 1;
          use.recipes.push(String(call.input?.recipe));
        }
      } else if (call.name === "create_instance") {
        // a copy of a component that exists: of a linked library when the call names its file
        use.instanced += 1;
        if (typeof call.input?.fileId === "string" && call.input.fileId !== "") use.fromLibrary += 1;
      } else if (BY_HAND.has(call.name)) {
        use.byHand += 1;
      }
    },
    /** → the counts, or undefined when the turn neither looked for nor built anything. */
    summary() {
      return use.searched + use.built + use.byHand + use.instanced > 0 ? { ...use } : undefined;
    },
  };
}

/** Appends one turn's counts to the log, one JSON object per line. Never fails a turn. */
export async function recordRecipeUse(file, entry) {
  if (!file) return;
  try {
    await mkdir(dirname(file), { recursive: true });
    await appendFile(file, `${JSON.stringify(entry)}\n`, "utf8");
  } catch {
    // a log that cannot be written is not worth a failed turn
  }
}

/**
 * The hit rate of a log's entries: of the conversations that found a recipe,
 * the share that built one. By conversation, as a recipe found in one turn
 * may be built in the next.
 */
export function hitRate(entries) {
  const conversations = new Map();
  for (const entry of entries) {
    const key = entry.sessionId ?? entry.turnId ?? conversations.size;
    const so = conversations.get(key) ?? { found: 0, built: 0 };
    conversations.set(key, { found: so.found + (entry.found ?? 0), built: so.built + (entry.built ?? 0) });
  }
  const found = [...conversations.values()].filter((entry) => entry.found > 0);
  return found.length === 0 ? undefined : found.filter((entry) => entry.built > 0).length / found.length;
}

/**
 * The library reuse rate of a log's entries: of the ready-made parts placed
 * — a recipe built, or a copy of a linked library's component — the share
 * that came from a library. Undefined when none was placed.
 */
export function libraryReuse(entries) {
  let placed = 0;
  let fromLibrary = 0;
  for (const entry of entries) {
    placed += (entry.built ?? 0) + (entry.fromLibrary ?? 0);
    fromLibrary += entry.fromLibrary ?? 0;
  }
  return placed === 0 ? undefined : fromLibrary / placed;
}
