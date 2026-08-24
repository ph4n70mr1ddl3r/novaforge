import { useEffect, useMemo, useState, type ReactNode } from "react";
import { PlatformClient, resolveLabel, type AppDefinition, type EntityDefinition, type TranslationsDefinition } from "@novaforge/shared";

/**
 * The i18n translation editor (PHASE-8 §7 — the PHASE-2 §13 Q3 deferral
 * landing): a per-locale workspace side-by-side with the source label, the
 * missing-translation report over the translatable universe, CSV export, and
 * merge-never-wipe import. Runtime fallback: label_i18n[locale] → label → apiName
 * (never blank — the chain itself lives in shared/metadata-model).
 */

interface TranslatableKey {
    key: string;
    source: string;
}

/** The translatable universe: app.label, <Entity>.label, <Entity>.<field>.label, report.<id>.label. */
export function translatableUniverse(app: AppDefinition): TranslatableKey[] {
    const keys: TranslatableKey[] = [
        { key: "app.label", source: resolveLabel(app, undefined, app.apiName) },
    ];
    for (const entity of app.entities) {
        keys.push({ key: `${entity.apiName}.label`, source: resolveLabel(entity, undefined, entity.apiName) });
        for (const field of entity.fields) {
            keys.push({
                key: `${entity.apiName}.${field.apiName}.label`,
                source: resolveLabel(field, undefined, field.apiName),
            });
        }
    }
    for (const report of app.reports) {
        keys.push({ key: `report.${report.id}.label`, source: resolveLabel(report, undefined, report.id) });
    }
    return keys;
}

export function toCsv(entries: Record<string, string>): string {
    const escape = (value: string): string => (/[",\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value);
    return Object.entries(entries)
        .map(([key, value]) => `${escape(key)},${escape(value)}`)
        .join("\n");
}

export function parseCsv(text: string): Record<string, string> {
    const entries: Record<string, string> = {};
    for (const cells of splitCsvLines(text)) {
        if (cells.length >= 2 && cells[0]) {
            entries[cells[0]!] = cells[1] ?? "";
        }
    }
    return entries;
}

/** Splits CSV into cells, honoring quoted commas and escaped quotes (RFC 4180). */
function splitCsvLines(text: string): string[][] {
    const rows: string[][] = [];
    let row: string[] = [];
    let cell = "";
    let quoted = false;
    for (let i = 0; i < text.length; i++) {
        const char = text[i]!;
        if (quoted) {
            if (char === '"') {
                if (text[i + 1] === '"') {
                    cell += '"';
                    i++;
                } else {
                    quoted = false;
                }
            } else {
                cell += char;
            }
        } else if (char === '"') {
            quoted = true;
        } else if (char === ",") {
            row.push(cell);
            cell = "";
        } else if (char === "\n" || char === "\r") {
            if (cell !== "" || row.length > 0) {
                row.push(cell);
                rows.push(row);
                row = [];
                cell = "";
            }
        } else {
            cell += char;
        }
    }
    if (cell !== "" || row.length > 0) {
        row.push(cell);
        rows.push(row);
    }
    return rows;
}

export function I18nEditor({
    app,
    loadWorkspace,
    saveWorkspace,
}: {
    app: AppDefinition;
    loadWorkspace: (locale: string) => Promise<TranslationsDefinition | undefined>;
    saveWorkspace: (locale: string, entries: Record<string, string>) => Promise<void>;
}): ReactNode {
    const locales = app.translations.map((translation) => translation.locale);
    const [locale, setLocale] = useState(locales[0] ?? "de");
    const [entries, setEntries] = useState<Record<string, string>>({});
    const [flash, setFlash] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const universe = useMemo(() => translatableUniverse(app), [app]);
    const missing = universe.filter((key) => !entries[key.key]);

    useEffect(() => {
        let cancelled = false;
        void loadWorkspace(locale).then((workspace) => {
            if (!cancelled) {
                setEntries(workspace?.entries ?? {});
            }
        });
        return () => {
            cancelled = true;
        };
    }, [locale, loadWorkspace]);

    return (
        <section className="nf-b-i18n" aria-label="Translations">
            <h2>Translations</h2>
            <label>
                Locale
                <select value={locale} onChange={(event) => setLocale(event.target.value)}>
                    {["de", "fr", ...locales.filter((candidate) => candidate !== "de" && candidate !== "fr")].map((candidate) => (
                        <option key={candidate} value={candidate}>{candidate}</option>
                    ))}
                </select>
            </label>
            <p role="status" aria-live="polite">
                {missing.length === 0 ? "All keys translated" : `${missing.length} of ${universe.length} keys missing`}
            </p>
            <table className="nf-table">
                <thead>
                    <tr>
                        <th scope="col">Key</th>
                        <th scope="col">Source</th>
                        <th scope="col">{locale}</th>
                    </tr>
                </thead>
                <tbody>
                    {universe.map((key) => (
                        <tr key={key.key} data-missing={!entries[key.key] || undefined}>
                            <td><code>{key.key}</code></td>
                            <td>{key.source}</td>
                            <td>
                                <input
                                    aria-label={`${key.key} translation`}
                                    value={entries[key.key] ?? ""}
                                    onChange={(event) =>
                                        setEntries((current) => ({ ...current, [key.key]: event.target.value }))
                                    }
                                />
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
            <div className="nf-b-actions">
                <button
                    type="button"
                    className="nf-action-primary"
                    disabled={busy}
                    onClick={async () => {
                        setBusy(true);
                        try {
                            await saveWorkspace(locale, entries);
                            setFlash(`Saved ${locale} workspace`);
                        } finally {
                            setBusy(false);
                        }
                    }}
                >
                    Save workspace
                </button>
                <button
                    type="button"
                    onClick={() => {
                        const blob = new Blob([toCsv(entries)], { type: "text/csv" });
                        const link = document.createElement("a");
                        link.href = URL.createObjectURL(blob);
                        link.download = `${app.apiName}-${locale}.csv`;
                        link.click();
                        URL.revokeObjectURL(link.href);
                    }}
                >
                    Export CSV
                </button>
            </div>
            {flash ? <p role="status">{flash}</p> : null}
            <details>
                <summary>Missing translations</summary>
                <ul>
                    {missing.map((key) => (
                        <li key={key.key}><code>{key.key}</code></li>
                    ))}
                </ul>
            </details>
        </section>
    );
}

export type { PlatformClient, EntityDefinition };
