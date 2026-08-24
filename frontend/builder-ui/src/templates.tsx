import { useEffect, useState, type ReactNode } from "react";
import { PlatformClient } from "@novaforge/shared";

/**
 * The template catalog (PHASE-8 §6): a listing in the builder — name, publisher,
 * version, description (no commerce, §11 Q2) — with install creating a new draft
 * app from the snapshot. Templates never carry tenant data or secret material
 * (§6); the list is read-only.
 */
export interface TemplatesProps {
    client: PlatformClient;
    onInstalled?: (apiName: string) => void;
}

export function Templates({ client, onInstalled }: TemplatesProps): ReactNode {
    const [templates, setTemplates] = useState<Record<string, unknown>[]>([]);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [flash, setFlash] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        client
            .templates()
            .then((rows) => {
                if (!cancelled) setTemplates(rows);
            })
            .catch((caught: unknown) => {
                if (!cancelled) setError(caught instanceof Error ? caught.message : String(caught));
            });
        return () => {
            cancelled = true;
        };
    }, [client]);

    const install = async (templateId: string, name: string): Promise<void> => {
        setBusy(true);
        setError(null);
        try {
            const app = await client.installTemplate(templateId);
            setFlash(`Installed '${name}' as a new draft app (${String((app as { apiName?: string }).apiName ?? "")})`);
            onInstalled?.(String((app as { apiName?: string }).apiName ?? name));
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : String(caught));
        } finally {
            setBusy(false);
        }
    };

    return (
        <section className="nf-b-templates" aria-label="Template catalog" aria-busy={busy}>
            <h2>Templates</h2>
            {error ? <p role="alert">{error}</p> : null}
            {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
            <table className="nf-table">
                <thead>
                    <tr>
                        <th scope="col">Name</th>
                        <th scope="col">Publisher</th>
                        <th scope="col">Version</th>
                        <th scope="col">Description</th>
                        <th scope="col">Install</th>
                    </tr>
                </thead>
                <tbody>
                    {templates.map((template) => (
                        <tr key={String(template.id)}>
                            <td>{String(template.name)}</td>
                            <td>{String(template.publisher ?? "")}</td>
                            <td>{String(template.version ?? "")}</td>
                            <td>{String(template.description ?? "")}</td>
                            <td>
                                <button
                                    type="button"
                                    disabled={busy}
                                    aria-label={`Install ${String(template.name)}`}
                                    onClick={() => void install(String(template.id), String(template.name))}
                                >
                                    Install
                                </button>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
            {templates.length === 0 && !busy ? <p role="status">No templates registered yet.</p> : null}
        </section>
    );
}
