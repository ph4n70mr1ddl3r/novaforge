import { useId, useRef, useState, type ReactNode } from "react";
import { Decimal } from "../expression/decimal.ts";
import { useBoundValue, useRenderer } from "../renderer/context.ts";
import { resolveLabel } from "../metadata.ts";

/**
 * The v1 field widgets (PHASE-2 §6 item 3): typed inputs driven by field metadata
 * through the renderer context. Money renders locale-aware with the authored
 * currency; decimals stay exact strings in transit (never binary floats — the
 * PLAN.md §1 money rule); labels tie to inputs (WCAG 2.2 AA).
 */

export interface FieldWidgetProps {
    field: string;
    label?: string;
    bind?: string;
    readonly?: boolean;
    required?: boolean;
    placeholder?: string;
    [key: string]: unknown;
}

function useFieldSlot(props: FieldWidgetProps) {
    const renderer = useRenderer();
    const field = renderer.fields[props.field];
    const value = useBoundValue(props.bind ?? props.field);
    const error = renderer.errors[props.field];
    const label = props.label ?? resolveLabel(field, renderer.user?.locale, props.field);
    const readonly = props.readonly === true || field?.readonly === true;
    const required = props.required === true || field?.required === true;
    return { renderer, field, value, error, label, readonly, required };
}

function FieldError({ error }: { error?: string }) {
    if (!error) return null;
    return <p className="nf-field-error" role="alert">{error}</p>;
}

export function FieldInput(props: FieldWidgetProps): ReactNode {
    const { renderer, value, error, label, readonly, required } = useFieldSlot(props);
    const id = useId();
    const describedBy = error ? `${id}-error` : undefined;
    return (
        <div className="nf-field">
            <label htmlFor={id}>
                {label}
                {required ? <span className="nf-required" aria-hidden="true"> *</span> : null}
            </label>
            {props.multiline ? (
                <textarea
                    id={id}
                    name={props.field}
                    value={value == null ? "" : String(value)}
                    placeholder={props.placeholder}
                    readOnly={readonly}
                    aria-required={required || undefined}
                    aria-invalid={error ? true : undefined}
                    aria-describedby={describedBy}
                    rows={4}
                    onChange={(event) => renderer.setValue(props.field, event.target.value)}
                />
            ) : (
                <input
                    id={id}
                    name={props.field}
                    type={String(props.inputType ?? "text")}
                    value={value == null ? "" : String(value)}
                    placeholder={props.placeholder}
                    readOnly={readonly}
                    aria-required={required || undefined}
                    aria-invalid={error ? true : undefined}
                    aria-describedby={describedBy}
                    onChange={(event) => renderer.setValue(props.field, event.target.value)}
                />
            )}
            <FieldError error={error ? `${error}` : undefined} />
        </div>
    );
}

/** Exact decimals: values travel as strings; the client never coerces through float. */
export function FieldNumber(props: FieldWidgetProps): ReactNode {
    const { renderer, value, error, label, readonly, required } = useFieldSlot(props);
    const id = useId();
    const text = value == null || value === "" ? "" : String(value);
    // total validity: intermediate typing states ("12.", ".", "1e5") are invalid but
    // never throw — Decimal.parse rejects them and parse used to be called as if it
    // returned undefined, crashing the whole tree on the first "."
    const valid = text === "" || Decimal.tryParse(text) !== undefined;
    return (
        <div className="nf-field">
            <label htmlFor={id}>
                {label}
                {props.currency ? <span className="nf-currency"> ({String(props.currency)})</span> : null}
                {required ? <span className="nf-required" aria-hidden="true"> *</span> : null}
            </label>
            <input
                id={id}
                name={props.field}
                type="text"
                inputMode="decimal"
                value={text}
                readOnly={readonly}
                aria-required={required || undefined}
                aria-invalid={error ? true : undefined}
                onChange={(event) => renderer.setValue(props.field, event.target.value)}
                onBlur={(event) => {
                    // Canonicalize on blur through the exact decimal (34-digit context).
                    const raw = event.target.value;
                    if (raw !== "" && raw !== "-") {
                        try {
                            renderer.setValue(props.field, Decimal.parse(raw).toString());
                        } catch {
                            renderer.setValue(props.field, raw);
                        }
                    }
                }}
                data-invalid={valid ? undefined : "true"}
            />
            <FieldError error={error ? `${error}` : undefined} />
        </div>
    );
}

export function FieldSelect(props: FieldWidgetProps): ReactNode {
    const { renderer, field, value, error, label, readonly, required } = useFieldSlot(props);
    const id = useId();
    const options = (props.options as string[] | undefined) ?? field?.values ?? [];
    return (
        <div className="nf-field">
            <label htmlFor={id}>
                {label}
                {required ? <span className="nf-required" aria-hidden="true"> *</span> : null}
            </label>
            <select
                id={id}
                name={props.field}
                value={value == null ? "" : String(value)}
                disabled={readonly}
                aria-required={required || undefined}
                onChange={(event) => renderer.setValue(props.field, event.target.value || null)}
            >
                <option value="">—</option>
                {options.map((option) => (
                    <option key={option} value={option}>{option}</option>
                ))}
            </select>
            <FieldError error={error ? `${error}` : undefined} />
        </div>
    );
}

export function FieldSwitch(props: FieldWidgetProps): ReactNode {
    const { renderer, value, label, readonly, required, error } = useFieldSlot(props);
    const id = useId();
    return (
        <div className="nf-field nf-field-switch">
            <label htmlFor={id}>{label}</label>
            <input
                id={id}
                name={props.field}
                type="checkbox"
                role="switch"
                checked={value === true}
                disabled={readonly}
                aria-required={required || undefined}
                onChange={(event) => renderer.setValue(props.field, event.target.checked)}
            />
            <FieldError error={error ? `${error}` : undefined} />
        </div>
    );
}

/** One component, three modes (§5) — date/datetime/time as props. */
export function FieldDate(props: FieldWidgetProps): ReactNode {
    const { renderer, value, error, label, readonly, required } = useFieldSlot(props);
    const id = useId();
    const mode = String(props.mode ?? "date");
    const type = mode === "time" ? "time" : mode === "datetime" ? "datetime-local" : "date";
    return (
        <div className="nf-field">
            <label htmlFor={id}>
                {label}
                {required ? <span className="nf-required" aria-hidden="true"> *</span> : null}
            </label>
            <input
                id={id}
                name={props.field}
                type={type}
                value={value == null ? "" : String(value)}
                readOnly={readonly}
                aria-required={required || undefined}
                onChange={(event) => renderer.setValue(props.field, event.target.value || null)}
            />
            <FieldError error={error ? `${error}` : undefined} />
        </div>
    );
}

/** Search-as-you-type via the query DSL, min 2 characters (§5). */
export function FieldLookup(props: FieldWidgetProps): ReactNode {
    const { renderer, value, error, label, readonly, required } = useFieldSlot(props);
    const id = useId();
    const [term, setTerm] = useState("");
    const [results, setResults] = useState<Record<string, unknown>[]>([]);
    const [open, setOpen] = useState(false);
    const minChars = Number(props.minChars ?? 2);
    const target = String(props.target ?? "");
    // the display field of the TARGET entity — the current entity's field map
    // never contains it (target is an entity name), so every option rendered the
    // raw id before
    const displayField = renderer.data?.displayFieldOf?.(target);
    // only the LATEST search's response commits: out-of-order responses used to
    // overwrite the newer term's results with the older term's
    const seqRef = useRef(0);
    const search = async (next: string): Promise<void> => {
        setTerm(next);
        if (next.length < minChars || !renderer.data || !target) {
            seqRef.current++;
            setResults([]);
            return;
        }
        const seq = ++seqRef.current;
        try {
            const rows = await renderer.data.search(target, next);
            if (seq === seqRef.current) {
                setResults(rows);
                setOpen(true);
            }
        } catch {
            if (seq === seqRef.current) {
                setResults([]);
            }
        }
    };
    return (
        <div className="nf-field nf-field-lookup">
            <label htmlFor={id}>
                {label}
                {required ? <span className="nf-required" aria-hidden="true"> *</span> : null}
            </label>
            <input
                id={id}
                name={props.field}
                type="text"
                role="combobox"
                aria-expanded={open}
                aria-controls={`${id}-listbox`}
                autoComplete="off"
                value={open ? term : value == null ? "" : String(value)}
                readOnly={readonly}
                onChange={(event) => void search(event.target.value)}
                onBlur={() => {
                    // blur only closes — the old handler also wrote the raw search
                    // text as the field's value (a typed "Acme" became the FK), and
                    // closing here unmounted the listbox before an option's click
                    // could land (blur fires between mousedown and click in
                    // Chrome/Firefox/Edge)
                    setOpen(false);
                }}
            />
            {open && results.length > 0 ? (
                <ul id={`${id}-listbox`} role="listbox" className="nf-lookup-options">
                    {results.map((row) => (
                        <li key={String(row.id)} role="option" aria-selected={false}>
                            <button
                                type="button"
                                // select on MOUSEDOWN (prevented): it fires before the
                                // input's blur can unmount the listbox — and only ids
                                // are ever written, never the typed term
                                onMouseDown={(event) => {
                                    event.preventDefault();
                                    renderer.setValue(props.field, row.id ?? null);
                                    setOpen(false);
                                }}
                                onClick={() => {
                                    renderer.setValue(props.field, row.id ?? null);
                                    setOpen(false);
                                }}
                            >
                                {String(
                                    (displayField && row[displayField] != null
                                        ? row[displayField]
                                        : row.id) ?? "",
                                )}
                            </button>
                        </li>
                    ))}
                </ul>
            ) : null}
            <FieldError error={error ? `${error}` : undefined} />
        </div>
    );
}

/** Multi-select over a target entity's rows (m2m). */
export function FieldMultiLookup(props: FieldWidgetProps): ReactNode {
    const { renderer, value, error, label, readonly, required } = useFieldSlot(props);
    const id = useId();
    const selected: string[] = Array.isArray(value) ? value.map(String) : [];
    const [term, setTerm] = useState("");
    const [results, setResults] = useState<Record<string, unknown>[]>([]);
    const minChars = 2;
    const target = String(props.target ?? "");
    const displayField = renderer.data?.displayFieldOf?.(target);
    const seqRef = useRef(0);
    const search = async (next: string): Promise<void> => {
        setTerm(next);
        if (next.length < minChars || !renderer.data || !target) {
            seqRef.current++;
            setResults([]);
            return;
        }
        const seq = ++seqRef.current;
        try {
            const rows = await renderer.data.search(target, next);
            if (seq === seqRef.current) {
                setResults(rows);
            }
        } catch {
            if (seq === seqRef.current) {
                setResults([]);
            }
        }
    };
    const labelOf = (row: Record<string, unknown>): string =>
        String((displayField && row[displayField] != null ? row[displayField] : row.id) ?? "");
    return (
        <div className="nf-field nf-field-multilookup">
            <label htmlFor={id}>
                {label}
                {required ? <span className="nf-required" aria-hidden="true"> *</span> : null}
            </label>
            {selected.length > 0 ? (
                <ul className="nf-chips" aria-label={`${label} selected`}>
                    {selected.map((id_) => (
                        <li key={id_} className="nf-chip">
                            {id_}
                            {!readonly ? (
                                <button
                                    type="button"
                                    aria-label={`Remove ${id_}`}
                                    onClick={() =>
                                        renderer.setValue(
                                            props.field,
                                            selected.filter((entry) => entry !== id_),
                                        )
                                    }
                                >
                                    ×
                                </button>
                            ) : null}
                        </li>
                    ))}
                </ul>
            ) : null}
            {!readonly ? (
                <input
                    id={id}
                    name={props.field}
                    type="text"
                    role="combobox"
                    aria-expanded={results.length > 0}
                    autoComplete="off"
                    value={term}
                    onChange={(event) => void search(event.target.value)}
                    placeholder="Search…"
                />
            ) : null}
            {results.length > 0 ? (
                <ul role="listbox" className="nf-lookup-options">
                    {results.map((row) => (
                        <li key={String(row.id)} role="option" aria-selected={false}>
                            <button
                                type="button"
                                onClick={() => {
                                    const id_ = String(row.id ?? "");
                                    if (!selected.includes(id_)) {
                                        renderer.setValue(props.field, [...selected, id_]);
                                    }
                                    setTerm("");
                                    setResults([]);
                                }}
                            >
                                {labelOf(row)}
                            </button>
                        </li>
                    ))}
                </ul>
            ) : null}
            <FieldError error={error ? `${error}` : undefined} />
        </div>
    );
}

/** v1 rich text: a multiline source editor (formatting joins with the catalog's versioning). */
export function FieldRichText(props: FieldWidgetProps): ReactNode {
    return <FieldInput {...props} multiline />;
}

/** JSON renders read-only in Phase 2 (§5) — a code viewer, never an editor. */
export function FieldJson(props: FieldWidgetProps): ReactNode {
    const { value, label } = useFieldSlot(props);
    const id = useId();
    const text = value == null ? "" : typeof value === "string" ? value : JSON.stringify(value, null, 2);
    return (
        <div className="nf-field">
            <label htmlFor={id}>{label}</label>
            <pre id={id} className="nf-json" tabIndex={0}>{text}</pre>
        </div>
    );
}
