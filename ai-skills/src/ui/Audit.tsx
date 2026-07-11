import { useState } from "react";
import * as bridge from "./bridge";
import type { Violation } from "./bridge";

/**
 * The audit view: rule violations collected by the change watcher (and full
 * scans) accumulate here instead of only flashing as toasts. From here they
 * are fixed in batch — handed to the chat agent per rule, or one by one
 * manually (select the shape, fix, watch the entry clear itself).
 */
export function AuditPanel({
  violations,
  onViolationsChanged,
  onFixViaChat,
}: {
  violations: Violation[];
  onViolationsChanged: (v: Violation[]) => void;
  onFixViaChat: (prompt: string) => void;
}) {
  const [scanning, setScanning] = useState(false);
  const [scanned, setScanned] = useState<number | null>(null);
  const [error, setError] = useState("");

  const scan = async () => {
    setScanning(true);
    setError("");
    try {
      const r = await bridge.call<{ scanned: number; violations: Violation[] }>("audit-file");
      onViolationsChanged(r.violations);
      setScanned(r.scanned);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setScanning(false);
    }
  };

  const dismiss = async (id: string) => {
    try {
      const r = await bridge.call<{ violations: Violation[] }>("dismiss-violation", { id });
      onViolationsChanged(r.violations);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const select = (shapeId: string) => {
    void bridge.call("select-shape", { shapeId }).catch(() => {});
  };

  const byRule = new Map<string, Violation[]>();
  for (const v of violations) {
    byRule.set(v.rule, [...(byRule.get(v.rule) ?? []), v]);
  }

  const fixPrompt = (rule: string, list: Violation[]) =>
    [
      `[Audit] Fix all "${rule}" violations in this file:`,
      ...list.map((v) => `- "${v.shapeName}" (id ${v.shapeId}): ${v.reason}`),
      "",
      `Fetch the rule body with get_design_skills({name: "${rule}"}) if you need it. ` +
        "Fix shape by shape with minimal changes, then run audit_file to confirm the list is clean.",
    ].join("\n");

  return (
    <div className="audit-panel">
      <div className="audit-head">
        <span className="hint">
          Violations of this file's rules, watched while you design. Fix them in batch via the
          agent, or manually — entries clear themselves when the shape passes.
        </span>
        <button data-appearance="secondary" disabled={scanning} onClick={() => void scan()}>
          {scanning ? "Scanning…" : "⟳ Re-scan file"}
        </button>
        {scanned !== null && !scanning && (
          <span className="hint">scanned {scanned} shapes</span>
        )}
      </div>
      {error && <div className="msg error">{error}</div>}

      {violations.length === 0 ? (
        <div className="empty">
          <p>No violations recorded. Run a scan, or keep designing — the watcher reports here.</p>
        </div>
      ) : (
        [...byRule.entries()].map(([rule, list]) => (
          <div className="audit-group" key={rule}>
            <div className="audit-group-head">
              <span className="skill-name">
                {rule} <span className="badge">{list.length}</span>
              </span>
              <button
                data-appearance="primary"
                onClick={() => onFixViaChat(fixPrompt(rule, list))}
                title="Send these violations to the chat agent as one batch fix"
              >
                ✦ Fix via chat
              </button>
            </div>
            {list.map((v) => (
              <div className="audit-item" key={v.id}>
                <div className="audit-item-main">
                  <span className="audit-shape">{v.shapeName}</span>
                  <span className="audit-reason">{v.reason}</span>
                </div>
                <button className="icon" title="Select shape on canvas" onClick={() => select(v.shapeId)}>
                  ⌖
                </button>
                <button className="icon" title="Dismiss" onClick={() => void dismiss(v.id)}>
                  ✕
                </button>
              </div>
            ))}
          </div>
        ))
      )}
    </div>
  );
}
