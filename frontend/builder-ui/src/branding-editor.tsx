import { useState, type ReactNode } from "react";
import type { AppDefinition, BrandingDefinition } from "@novaforge/shared";

/**
 * The tenant-branding editor (ADR-009 §5): the app's token overrides, authored
 * here and honored by the runtime shell — it sets --nf-color-accent /
 * --nf-color-accent-contrast on its root, so every surface (buttons, nav state,
 * KPI charts) re-themes while light/dark keeps working through the token
 * re-mapping underneath. Values are any CSS color; accentContrast exists so a
 * light accent can stay WCAG-readable on primary surfaces. Clearing both fields
 * saves an empty branch — the patch replaces the whole branding, dropping the
 * tenant back to the platform palette.
 */

export interface BrandingEditorProps {
    app: AppDefinition;
    busy?: boolean;
    onSave: (mutate: (current: BrandingDefinition | undefined) => BrandingDefinition) => Promise<void>;
}

export function BrandingEditor({ app, busy, onSave }: BrandingEditorProps): ReactNode {
    // local drafts seeded from the app doc; the shell keys this editor by app id,
    // so a switch between apps re-seeds rather than bleeding one tenant's palette
    // into another's form
    const [accent, setAccent] = useState(app.branding?.accent ?? "");
    const [accentContrast, setAccentContrast] = useState(app.branding?.accentContrast ?? "");
    const [error, setError] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);

    const inFlight = busy || saving;
    const nextBranding = (): BrandingDefinition => ({
        accent: accent.trim() || undefined,
        accentContrast: accentContrast.trim() || undefined,
    });

    const submit = async (): Promise<void> => {
        setSaving(true);
        setError(null);
        try {
            await onSave(() => nextBranding());
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : String(caught));
        } finally {
            setSaving(false);
        }
    };

    // the live preview consumes exactly what the runtime shell will consume —
    // the same two variables, falling back to the platform tokens the same way
    const previewStyle = {
        background: accent.trim() || "var(--nf-color-accent)",
        color: accentContrast.trim() || "var(--nf-color-accent-contrast)",
        borderColor: accent.trim() || "var(--nf-color-accent)",
    };

    return (
        <section aria-label="Tenant branding">
            <h2>Tenant branding</h2>
            <p className="nf-hint">
                Accent overrides ride the design tokens: the runtime shell applies them app-wide,
                in light and dark mode both. Leave a field empty to keep the platform value.
            </p>
            <form
                className="nf-form"
                onSubmit={(event) => {
                    event.preventDefault();
                    void submit();
                }}
            >
                <label className="nf-field">
                    Accent
                    <input
                        type="text"
                        value={accent}
                        placeholder="#2f6fed"
                        onChange={(event) => setAccent(event.target.value)}
                    />
                </label>
                <label className="nf-field">
                    Accent contrast (text on accent surfaces)
                    <input
                        type="text"
                        value={accentContrast}
                        placeholder="keeps the platform value"
                        onChange={(event) => setAccentContrast(event.target.value)}
                    />
                </label>
                <div className="nf-field" aria-hidden="true">
                    Preview
                    <span className="nf-branding-preview" style={previewStyle}>
                        Primary action
                    </span>
                </div>
                <div className="nf-b-actions nf-full">
                    <button type="submit" className="nf-action-primary" disabled={inFlight}>
                        Save branding
                    </button>
                </div>
                {error ? (
                    <p role="alert" className="nf-error nf-full">
                        {error}
                    </p>
                ) : null}
            </form>
        </section>
    );
}
