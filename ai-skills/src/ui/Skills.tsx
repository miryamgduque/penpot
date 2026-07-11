import { useState } from "react";
import * as bridge from "./bridge";
import type { SkillsPayload } from "./bridge";
import type { EffectiveSkill, Scope } from "@penpot/skills-core";

const EDITABLE_SCOPES: Scope[] = ["org", "project", "file"];

export function SkillsPanel({
  skills,
  onSkillsChanged,
}: {
  skills: SkillsPayload;
  onSkillsChanged: (s: SkillsPayload) => void;
}) {
  const [editingScope, setEditingScope] = useState<Scope | null>(null);
  const [error, setError] = useState("");

  const toggle = async (skill: EffectiveSkill, enabled: boolean) => {
    setError("");
    try {
      const next = await bridge.call<SkillsPayload>("set-skill-enabled", {
        scope: skill.definedAt,
        name: skill.name,
        enabled,
      });
      onSkillsChanged(next);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const managed = new Set(skills.managed ?? []);

  return (
    <div className="skills-panel">
      <div className="skills-head">
        <span className="hint">
          {managed.size > 0
            ? "app → team → file, cascade-resolved. App and team scopes are managed in the " +
              "dashboard (Team → Skills & Rules); file skills live in this design file."
            : "platform → org → project → file, cascade-resolved. Skills are knowledge for the " +
              "agent; rules are constraints that can be watched and enforced. Toggle any " +
              "non-mandatory entry off at the scope that defines it."}
        </span>
      </div>
      <div className="scope-tabs">
        <button
          data-appearance={!editingScope ? "primary" : "secondary"}
          className={!editingScope ? "active" : ""}
          onClick={() => setEditingScope(null)}
        >
          Effective
        </button>
        {(["platform", "org", "project", "file"] as Scope[]).map((s) => (
          <button
            key={s}
            data-appearance={editingScope === s ? "primary" : "secondary"}
            className={`scope-${s} ${editingScope === s ? "active" : ""}`}
            onClick={() => setEditingScope(s)}
          >
            {s} ({skills.scopes[s].length}){s === "platform" ? " 🔒" : ""}
          </button>
        ))}
      </div>
      {error && <div className="msg error">{error}</div>}
      {editingScope === null ? (
        skills.effective.map((s) => (
          <SkillCard
            key={s.name}
            skill={s}
            managed={managed.has(s.definedAt)}
            onToggle={(enabled) => void toggle(s, enabled)}
          />
        ))
      ) : (
        <ScopeEditor
          scope={editingScope}
          sources={skills.scopes[editingScope]}
          disabled={skills.disabled[editingScope]}
          readOnly={!EDITABLE_SCOPES.includes(editingScope) || managed.has(editingScope)}
          managed={managed.has(editingScope)}
          onSkillsChanged={onSkillsChanged}
        />
      )}
    </div>
  );
}

function SkillCard({
  skill,
  managed,
  onToggle,
}: {
  skill: EffectiveSkill;
  managed?: boolean;
  onToggle: (enabled: boolean) => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div
      className={`skill-card enforcement-${skill.enforcement}${skill.disabled ? " disabled" : ""}`}
    >
      <div className="skill-row">
        <button className="skill-summary" onClick={() => setOpen(!open)}>
          <span className="skill-name">{skill.name}</span>
          <span className="badges">
            <span className={`badge kind-${skill.kind}`}>{skill.kind}</span>
            <span className={`badge scope-${skill.definedAt}`}>{skill.definedAt}</span>
            <span className={`badge enf-${skill.enforcement}`}>
              {skill.enforcement === "enforced" && "⛔ "}
              {skill.enforcement === "triggered" && "👁 "}
              {skill.enforcement}
            </span>
            {skill.mandatory && <span className="badge mandatory">🔒 mandatory</span>}
          </span>
        </button>
        <label
          className="skill-toggle"
          title={
            managed
              ? "Managed in the dashboard (Team → Skills & Rules)"
              : skill.mandatory
                ? "Mandatory — cannot be disabled"
                : skill.disabled
                  ? `Enable (currently off at ${skill.definedAt} scope)`
                  : `Disable at ${skill.definedAt} scope`
          }
        >
          <input
            type="checkbox"
            checked={!skill.disabled}
            disabled={skill.mandatory || managed}
            onChange={(e) => onToggle(e.target.checked)}
          />
        </label>
      </div>
      <div className="skill-desc">{skill.description}</div>
      {skill.disabled && <div className="skill-note">disabled — ignored by agents and enforcement</div>}
      {skill.enforcementRaisedBy && (
        <div className="skill-note">enforcement raised by {skill.enforcementRaisedBy} scope</div>
      )}
      {skill.overrides.length > 0 && (
        <div className="skill-note">overrides {skill.overrides.join(", ")} definition</div>
      )}
      {open && <pre className="skill-body">{skill.body}</pre>}
    </div>
  );
}

function ScopeEditor({
  scope,
  sources: initial,
  disabled,
  readOnly,
  managed,
  onSkillsChanged,
}: {
  scope: Scope;
  sources: string[];
  disabled: string[];
  readOnly: boolean;
  managed?: boolean;
  onSkillsChanged: (s: SkillsPayload) => void;
}) {
  const [sources, setSources] = useState<string[]>(initial);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const save = async () => {
    setSaving(true);
    setError("");
    try {
      const next = await bridge.call<SkillsPayload>("save-scope-skills", {
        scope,
        sources: sources.filter((s) => s.trim()),
      });
      onSkillsChanged(next);
      setSources(next.scopes[scope]);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  if (readOnly) {
    return (
      <div className="skills-editor">
        <p className="hint">
          {managed
            ? "This scope is managed natively in the dashboard (Team → Skills & Rules) — read-only here."
            : "Platform skills are curated centrally (approval process in the full vision) — " +
              "read-only here, but each can be switched off in the Effective view."}
          {disabled.length > 0 && ` Currently disabled: ${disabled.join(", ")}.`}
        </p>
        {sources.map((src, i) => (
          <pre className="skill-body" key={i}>
            {src}
          </pre>
        ))}
      </div>
    );
  }

  return (
    <div className="skills-editor">
      <p className="hint">
        {scope === "file"
          ? "Stored inside this design file — versioned and shared with it."
          : `Stored at ${scope} level (cross-file store in this prototype).`}
        {disabled.length > 0 && ` Currently disabled: ${disabled.join(", ")}.`}
      </p>
      {sources.map((src, i) => (
        <div className="skill-edit" key={i}>
          <textarea
            className="input"
            value={src}
            rows={10}
            onChange={(e) => setSources(sources.map((s, j) => (j === i ? e.target.value : s)))}
          />
          <button className="danger" onClick={() => setSources(sources.filter((_, j) => j !== i))}>
            Remove
          </button>
        </div>
      ))}
      <div className="skills-editor-actions">
        <button
          data-appearance="secondary"
          onClick={() =>
            setSources([
              ...sources,
              `---\nname: my-skill\nscope: ${scope}\nkind: skill\nenforcement: advisory\ndescription: …\n---\n\nWrite the convention here.`,
            ])
          }
        >
          + Add skill
        </button>
        <button
          data-appearance="secondary"
          onClick={() =>
            setSources([
              ...sources,
              `---\nname: my-rule\nscope: ${scope}\nkind: rule\nenforcement: triggered\ndescription: …\n---\n\nDescribe the constraint here.`,
            ])
          }
        >
          + Add rule
        </button>
        <button data-appearance="primary" disabled={saving} onClick={() => void save()}>
          {saving ? "Saving…" : `Save ${scope} skills`}
        </button>
      </div>
      {error && <div className="msg error">{error}</div>}
    </div>
  );
}
