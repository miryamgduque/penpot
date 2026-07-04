import { useState } from "react";
import * as bridge from "./bridge";
import type { SkillsPayload } from "./bridge";
import type { EffectiveSkill } from "../skills/types";

export function SkillsPanel({
  skills,
  onSkillsChanged,
}: {
  skills: SkillsPayload;
  onSkillsChanged: (s: SkillsPayload) => void;
}) {
  const [editing, setEditing] = useState(false);

  return (
    <div className="skills-panel">
      <div className="skills-head">
        <span className="hint">
          platform → org → project → file, cascade-resolved. File skills live in this design file.
        </span>
        <button onClick={() => setEditing(!editing)}>{editing ? "Done" : "Edit file skills"}</button>
      </div>
      {editing ? (
        <FileSkillsEditor skills={skills} onSkillsChanged={onSkillsChanged} />
      ) : (
        skills.effective.map((s) => <SkillCard key={s.name} skill={s} />)
      )}
    </div>
  );
}

function SkillCard({ skill }: { skill: EffectiveSkill }) {
  const [open, setOpen] = useState(false);
  return (
    <div className={`skill-card enforcement-${skill.enforcement}`}>
      <button className="skill-summary" onClick={() => setOpen(!open)}>
        <span className="skill-name">{skill.name}</span>
        <span className="badges">
          <span className={`badge scope-${skill.definedAt}`}>{skill.definedAt}</span>
          <span className={`badge enf-${skill.enforcement}`}>
            {skill.enforcement === "enforced" && "⛔ "}
            {skill.enforcement === "triggered" && "👁 "}
            {skill.enforcement}
          </span>
          {skill.mandatory && <span className="badge mandatory">🔒 mandatory</span>}
        </span>
      </button>
      <div className="skill-desc">{skill.description}</div>
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

function FileSkillsEditor({
  skills,
  onSkillsChanged,
}: {
  skills: SkillsPayload;
  onSkillsChanged: (s: SkillsPayload) => void;
}) {
  const [sources, setSources] = useState<string[]>(skills.fileSkillSources);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const save = async () => {
    setSaving(true);
    setError("");
    try {
      const next = await bridge.call<SkillsPayload>("save-file-skills", {
        sources: sources.filter((s) => s.trim()),
      });
      onSkillsChanged(next);
      setSources(next.fileSkillSources);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="skills-editor">
      {sources.map((src, i) => (
        <div className="skill-edit" key={i}>
          <textarea
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
          onClick={() =>
            setSources([
              ...sources,
              "---\nname: my-skill\nscope: file\nenforcement: advisory\ndescription: …\n---\n\nWrite the convention here.",
            ])
          }
        >
          + Add skill
        </button>
        <button className="primary" disabled={saving} onClick={() => void save()}>
          {saving ? "Saving…" : "Save to file"}
        </button>
      </div>
      {error && <div className="msg error">{error}</div>}
    </div>
  );
}
