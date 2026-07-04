import { useEffect } from "react";
import * as bridge from "./bridge";
import type { TriggeredSkillEvent } from "./bridge";

/**
 * Transient notification layer for triggered skills — separate from the chat.
 * Toasts appear while designing, link to the affected shape ("Show" selects it
 * on canvas), can hand the skill to the agent, and dismiss themselves.
 */

export interface SkillNotification extends TriggeredSkillEvent {
  id: number;
  expiresAt: number;
}

export const NOTIFICATION_TTL_MS = 12_000;

export function NotificationStack({
  notifications,
  onDismiss,
  onAskAgent,
}: {
  notifications: SkillNotification[];
  onDismiss: (id: number) => void;
  onAskAgent: (n: SkillNotification) => void;
}) {
  // transient: tick expiry
  useEffect(() => {
    if (notifications.length === 0) return;
    const timer = setInterval(() => {
      const now = Date.now();
      for (const n of notifications) {
        if (n.expiresAt <= now) onDismiss(n.id);
      }
    }, 500);
    return () => clearInterval(timer);
  }, [notifications, onDismiss]);

  if (notifications.length === 0) return null;

  return (
    <div className="toast-stack">
      {notifications.map((n) => (
        <div key={n.id} className={`toast enf-${n.skill.enforcement}`}>
          <div className="toast-head">
            <span className="toast-title">
              {n.skill.enforcement === "enforced" ? "⛔" : "👁"} {n.skill.name}
            </span>
            <button className="toast-close" onClick={() => onDismiss(n.id)}>
              ×
            </button>
          </div>
          <div className="toast-body">{n.reason}</div>
          <div className="toast-actions">
            {n.shape && (
              <button
                onClick={() => {
                  void bridge.call("select-shape", { shapeId: n.shape!.id }).catch(() => {});
                }}
              >
                ⌖ Show {n.shape.name ? `"${n.shape.name}"` : "shape"}
              </button>
            )}
            <button className="primary" onClick={() => onAskAgent(n)}>
              Ask agent
            </button>
          </div>
          <div
            className="toast-progress"
            style={{ animationDuration: `${NOTIFICATION_TTL_MS}ms` }}
          />
        </div>
      ))}
    </div>
  );
}
