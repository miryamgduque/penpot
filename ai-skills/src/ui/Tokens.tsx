import { useEffect, useState } from "react";
import * as bridge from "./bridge";
import type { PaletteEntry } from "./bridge";

interface FileToken {
  name: string;
  value?: string;
  set: string;
  active: boolean;
}

interface TokensData {
  tokens: FileToken[];
  libraryColors: { name?: string; value?: string }[];
}

/**
 * Token management panel: color tokens in this file (CRUD), plus an
 * org-level palette (cross-file store) that can be synced into the file.
 */
export function TokensPanel() {
  const [data, setData] = useState<TokensData | null>(null);
  const [palette, setPalette] = useState<PaletteEntry[]>([]);
  const [error, setError] = useState("");
  const [newToken, setNewToken] = useState({ name: "", value: "#", set: "core" });
  const [newPalette, setNewPalette] = useState({ name: "", value: "#" });

  const refresh = async () => {
    try {
      const [t, p] = await Promise.all([
        bridge.call<TokensData>("get-color-tokens"),
        bridge.call<{ palette: PaletteEntry[] }>("get-org-palette"),
      ]);
      setData(t);
      setPalette(p.palette);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  useEffect(() => {
    void refresh();
  }, []);

  const run = (fn: () => Promise<unknown>) => {
    setError("");
    void fn()
      .then(refresh)
      .catch((e) => setError((e as Error).message));
  };

  return (
    <div className="tokens-panel">
      <section>
        <h3>File color tokens</h3>
        <p className="hint">
          Live in this file's token catalog — what the enforced token-only-colors rule allows.
        </p>
        {data?.tokens.length === 0 && <p className="hint">No color tokens yet.</p>}
        {data?.tokens.map((t) => (
          <div className="token-row" key={`${t.set}/${t.name}`}>
            <span className="swatch" style={{ background: t.value }} />
            <span className="token-name">{t.name}</span>
            <input
              className="token-value"
              defaultValue={t.value}
              onBlur={(e) => {
                if (e.target.value !== t.value)
                  run(() =>
                    bridge.call("update-color-token", {
                      set: t.set,
                      name: t.name,
                      value: e.target.value,
                    }),
                  );
              }}
            />
            <span className="badge">{t.set}</span>
            <button
              className="danger"
              onClick={() => run(() => bridge.call("delete-color-token", { set: t.set, name: t.name }))}
            >
              ✕
            </button>
          </div>
        ))}
        <div className="token-row new">
          <input
            placeholder="color.brand.primary"
            value={newToken.name}
            onChange={(e) => setNewToken({ ...newToken, name: e.target.value })}
          />
          <input
            className="token-value"
            value={newToken.value}
            onChange={(e) => setNewToken({ ...newToken, value: e.target.value })}
          />
          <input
            className="token-set"
            value={newToken.set}
            onChange={(e) => setNewToken({ ...newToken, set: e.target.value })}
          />
          <button
            className="primary"
            disabled={!newToken.name || !/^#[0-9a-fA-F]{3,6}$/.test(newToken.value)}
            onClick={() =>
              run(async () => {
                await bridge.call("create-color-token", newToken);
                setNewToken({ name: "", value: "#", set: newToken.set });
              })
            }
          >
            Add
          </button>
        </div>
      </section>

      <section>
        <h3>Org palette</h3>
        <p className="hint">
          Shared across files (org/user-level store in this prototype — backend org storage in the
          full version). Sync creates or updates tokens in this file's "org" set.
        </p>
        {palette.map((p, i) => (
          <div className="token-row" key={i}>
            <span className="swatch" style={{ background: p.value }} />
            <span className="token-name">{p.name}</span>
            <span className="token-value-static">{p.value}</span>
            <button
              className="danger"
              onClick={() =>
                run(() =>
                  bridge.call("save-org-palette", { palette: palette.filter((_, j) => j !== i) }),
                )
              }
            >
              ✕
            </button>
          </div>
        ))}
        <div className="token-row new">
          <input
            placeholder="color.org.surface"
            value={newPalette.name}
            onChange={(e) => setNewPalette({ ...newPalette, name: e.target.value })}
          />
          <input
            className="token-value"
            value={newPalette.value}
            onChange={(e) => setNewPalette({ ...newPalette, value: e.target.value })}
          />
          <button
            className="primary"
            disabled={!newPalette.name || !/^#[0-9a-fA-F]{3,6}$/.test(newPalette.value)}
            onClick={() =>
              run(async () => {
                await bridge.call("save-org-palette", { palette: [...palette, newPalette] });
                setNewPalette({ name: "", value: "#" });
              })
            }
          >
            Add
          </button>
        </div>
        <button
          disabled={palette.length === 0}
          onClick={() => run(() => bridge.call("apply-org-palette", {}))}
        >
          ⇩ Sync palette into this file ({palette.length})
        </button>
      </section>

      {error && <div className="msg error">{error}</div>}
    </div>
  );
}
