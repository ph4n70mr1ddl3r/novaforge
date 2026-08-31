import { useMemo, useState, type ReactNode } from "react";
import {
    ApiError,
    CATALOG,
    PageRenderer,
    applyDeltas,
    catalogEntry,
    checkPage,
    diffPages,
    pageApiName,
    resolveDefaultPage,
    resolvePage,
    toPersistedLayout,
    type ActionDef,
    type AppDefinition,
    type EntityDefinition,
    type PageDelta,
    type PageNode,
    type ResolvedPage,
} from "@novaforge/shared";

/**
 * The page builder (PHASE-2 §8): palette from the catalog, a structural canvas
 * over the page tree, a property panel auto-generated from the component's props
 * JSON Schema, and a live preview that IS the runtime renderer (preview mode —
 * no separate implementation). Undo/redo rides full-document snapshots (§8's v1
 * call); saves persist structural deltas against the L1 default with versions
 * pinned; a concurrent-edit 409 prompts a rebase (§8/T8's acceptance).
 */

export interface PageBuilderProps {
    app: AppDefinition;
    savePage: (page: Record<string, unknown>) => Promise<unknown>;
    role?: string;
}

type BuilderState = {
    /** Which page this state edits — state from another entity/kind never bleeds in. */
    key: string;
    page: ResolvedPage;
    /** The L1 default — the persisted layout's delta baseline (the server contract). */
    base: ResolvedPage;
    /** The page as loaded/saved — dirty diffs against this, not the default. */
    loaded: ResolvedPage;
    revision: number | null;
};

export function PageBuilder({ app, savePage, role }: PageBuilderProps): ReactNode {
    const entities = app.entities;
    const [entityApiName, setEntityApiName] = useState(entities[0]?.apiName ?? "");
    const [kind, setKind] = useState<"form" | "list" | "detail">("form");
    const entity = entities.find((candidate) => candidate.apiName === entityApiName) as EntityDefinition;
    const base = useMemo(
        () => resolveDefaultPage(entity, kind, { role, permissions: app.permissionSet }),
        [entity, kind, role, app.permissionSet],
    );
    const [state, setState] = useState<BuilderState | null>(null);
    const [undoStack, setUndoStack] = useState<BuilderState[]>([]);
    const [redoStack, setRedoStack] = useState<BuilderState[]>([]);
    const [selectedKey, setSelectedKey] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);
    /** Lifecycle warnings (§6 item 2): deprecations surface at save, never block it. */
    const [warnings, setWarnings] = useState<string[]>([]);
    const [conflict, setConflict] = useState<{
        message: string;
        serverPage: ResolvedPage;
        serverRevision: number | null;
    } | null>(null);
    const [flash, setFlash] = useState<string | null>(null);

    // Anti-regression (2026-08-31, thirteenth pass): the editor seeded from the L1
    // default and never read app.pages — a second edit session showed no
    // customizations and its first save overwrote them (a silent wipe), and its
    // revision counter was local fiction. The seed resolves the SAVED page (deltas
    // or authored layout) and carries the server's revision; state belongs to one
    // page key, so switching entity/kind never edits the old tree under a new name.
    const seed = useMemo<BuilderState | null>(() => {
        if (!entity) return null;
        const key = pageApiName(entity.apiName, kind);
        const saved = app.pages.find((candidate) => candidate.apiName === key);
        const { page } = resolvePage(saved, entity, {
            role,
            permissions: app.permissionSet,
            kind,
        });
        return { key, page, base, loaded: page, revision: saved?.revision ?? null };
    }, [entity, kind, app.pages, app.permissionSet, role, base]);
    const current = state?.key === seed?.key ? state : seed;
    const dirty = current
        ? JSON.stringify(diffPages(current.loaded, current.page)) !== "[]"
        : false;

    const edit = (updater: (page: ResolvedPage) => ResolvedPage): void => {
        if (!current) return;
        setUndoStack((stack) => [...stack, current]);
        setRedoStack([]);
        setState({ ...current, page: updater(structuredClone(current.page)) });
    };

    const apply = (deltas: PageDelta[]): void => {
        edit((page) => applyDeltas(page, deltas).page);
    };

    /** Applies deltas, surfacing a stale rejection — a silently dropped insert grew the undo stack while the canvas never changed. */
    const applyAudibly = (deltas: PageDelta[]): void => {
        edit((page) => {
            const verdict = applyDeltas(page, deltas);
            if (verdict.stale) {
                setError("That edit found no home (the target node is not on this page) — select a node on the canvas and retry");
            }
            return verdict.page;
        });
    };

    const undo = (): void => {
        setUndoStack((stack) => {
            if (!stack.length || !current) return stack;
            setRedoStack((redo) => [current, ...redo]);
            setState(stack[stack.length - 1]!);
            return stack.slice(0, -1);
        });
    };

    const redo = (): void => {
        setRedoStack((stack) => {
            if (!stack.length || !current) return stack;
            setUndoStack((undo) => [...undo, current]);
            setState(stack[0]!);
            return stack.slice(1);
        });
    };

    const save = async (): Promise<void> => {
        if (!current || !entity) return;
        // The builder writes version pins explicitly on save (§4); publish-mode
        // validation rejects unpinned pages.
        const pinned = structuredClone(current.page);
        const stamp = (node: PageNode): void => {
            if (!node.version) {
                node.version = catalogEntry(node.type).version;
            }
            node.children?.forEach(stamp);
        };
        stamp(pinned.model.root);
        const verdict = checkPage(pinned.model, { entity, mode: "publish" });
        if (verdict.issues.length > 0) {
            setError(`Save rejected (${verdict.issues.length} issue(s)): ${verdict.issues[0]!.message}`);
            return;
        }
        setWarnings(verdict.warnings.map((warning) => `${warning.path}: ${warning.message}`));
        const layout = toPersistedLayout(pinned, current.base);
        try {
            const savedApp = (await savePage({
                apiName: pageApiName(entity.apiName, kind),
                label: `${entity.label ?? entity.apiName} ${kind}`,
                type: kind,
                entity: entity.apiName,
                layout,
                ...(current.revision !== null ? { revision: current.revision } : {}),
            })) as { pages?: { apiName: string; revision?: number | null }[] } | null;
            setError(null);
            setConflict(null);
            setFlash("Page saved");
            // the server's own revision from its response — a locally computed
            // current+1 was fiction that drifted from the CAS base on any
            // intermediate save
            const savedRevision = savedApp?.pages?.find(
                (candidate) => candidate.apiName === pageApiName(entity.apiName, kind))?.revision;
            const nextRevision = savedRevision ?? (current.revision === null ? 1 : current.revision + 1);
            setState({ ...current, page: pinned, loaded: pinned, revision: nextRevision });
            setUndoStack([]);
            setRedoStack([]);
        } catch (caught) {
            if (caught instanceof ApiError && caught.status === 409) {
                // Rebase prompt (§8): the server won the race. The shell's savePage
                // refetched the app and pinned the FRESH saved page onto the thrown
                // error — the `app` prop captured at click time is stale (setApp had
                // only scheduled a re-render), so reading it here resolved the
                // "server page" to the same tree the editor already had, and
                // rebasing silently discarded the winning editor's work.
                const fresh = (caught as ApiError & { freshSavedPage?: unknown }).freshSavedPage as
                    | { apiName: string; revision?: number | null }
                    | undefined;
                const saved = fresh ?? (entity
                    ? app.pages.find((candidate) =>
                        candidate.apiName === pageApiName(entity.apiName, kind))
                    : undefined);
                const serverPage = entity
                    ? resolvePage(saved as AppDefinition["pages"][number] | undefined, entity, { role, permissions: app.permissionSet, kind }).page
                    : base;
                setConflict({
                    message: caught.message,
                    serverPage,
                    serverRevision: saved?.revision ?? null,
                });
            } else {
                setError(caught instanceof Error ? caught.message : String(caught));
            }
        }
    };

    if (!entity) {
        return <p>Create an entity first — pages overlay entities.</p>;
    }

    const selected = selectedKey ? findNode(current!.page.model.root, selectedKey) : null;
    // runFlow targets: the bound entity's named flow hooks (PHASE-3 §8) — script
    // hooks stay write-path caller-context and never surface here.
    const runFlowTargets = entity.hooks.filter((hook) => hook.flow && hook.name);

    return (
        <section className="nf-b-page" aria-label="Page builder">
            <div className="nf-b-toolbar">
                <label>
                    Entity
                    <select value={entityApiName} onChange={(event) => setEntityApiName(event.target.value)}>
                        {entities.map((candidate) => (
                            <option key={candidate.apiName} value={candidate.apiName}>
                                {candidate.label ?? candidate.apiName}
                            </option>
                        ))}
                    </select>
                </label>
                <label>
                    Kind
                    <select value={kind} onChange={(event) => setKind(event.target.value as typeof kind)}>
                        <option value="form">form</option>
                        <option value="list">list</option>
                        <option value="detail">detail</option>
                    </select>
                </label>
                <button type="button" onClick={undo} disabled={undoStack.length === 0}>Undo</button>
                <button type="button" onClick={redo} disabled={redoStack.length === 0}>Redo</button>
                <button type="button" onClick={() => void save()} className="nf-action-primary" data-testid="save-page">
                    Save page{dirty ? " •" : ""}
                </button>
            </div>
            {error ? <p role="alert">{error}</p> : null}
            {warnings.length > 0 ? (
                <ul className="nf-warnings" role="status" aria-live="polite">
                    {warnings.map((warning) => (
                        <li key={warning}>{warning}</li>
                    ))}
                </ul>
            ) : null}
            {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
            {conflict ? (
                <div role="alertdialog" aria-label="Concurrent edit" data-testid="rebase-prompt">
                    <p>{conflict.message}</p>
                    <button
                        type="button"
                        onClick={() => {
                            setConflict(null);
                            // rebase onto the SERVER's page (customizations included),
                            // CASing from the server's own revision — the fabricated
                            // local+1 could mismatch the server's counter and 409 the
                            // very next save
                            setState({
                                key: current!.key,
                                page: conflict.serverPage,
                                base,
                                loaded: conflict.serverPage,
                                revision: conflict.serverRevision
                                    ?? (current!.revision === null ? null : current!.revision + 1),
                            });
                            setUndoStack([]);
                            setRedoStack([]);
                            setFlash("Rebased onto the server's page — reapply your edits and save");
                        }}
                    >
                        Rebase
                    </button>
                </div>
            ) : null}
            <div className="nf-b-columns">
                <div className="nf-b-palette" aria-label="Component palette">
                    <h3>Palette</h3>
                    {CATALOG.filter((entry) => !entry.id.startsWith("novaforge.report") && entry.id !== "novaforge.chart-widget" && entry.id !== "novaforge.kpi-tile" && entry.id !== "novaforge.dashboard-grid")
                        .map((entry) => (
                            <button
                                key={entry.id}
                                type="button"
                                draggable
                                aria-label={`${entry.id}${entry.status === "deprecated" ? " (deprecated)" : ""}`}
                                onDragStart={(event) => event.dataTransfer.setData("text/novaforge-component", entry.id)}
                                onClick={() => {
                                    // The fallback parent is THIS page's root key —
                                    // the hardcoded "form" made every palette insert
                                    // on a list or detail page (root keys "list"/
                                    // "header") a silent no-op: the delta reported
                                    // stale, applyDeltas dropped it, and the undo
                                    // stack grew anyway.
                                    const parent = selected?.parentKey ?? current!.page.model.root.key ?? null;
                                    const parentNode = selected?.parentKey != null
                                        ? findNode(current!.page.model.root, selected.parentKey)?.node
                                        : current!.page.model.root;
                                    applyAudibly([{
                                        op: "insertNode",
                                        parent,
                                        index: parentNode?.children?.length ?? 0,
                                        node: newNode(entry.id),
                                    }]);
                                }}
                            >
                                {entry.id.replace("novaforge.", "")}
                                {entry.status === "deprecated" ? " (deprecated)" : ""}
                                {entry.status === "draft" ? " (draft)" : ""}
                            </button>
                        ))}
                </div>
                <div className="nf-b-canvas" aria-label="Page canvas">
                    <h3>Canvas</h3>
                    <NodeTree
                        node={current!.page.model.root}
                        selectedKey={selectedKey}
                        onSelect={(key) => setSelectedKey(key)}
                        onMove={(key, direction) => {
                            const found = findNode(current!.page.model.root, key);
                            if (!found || found.parentKey === null) return;
                            const parent = findNode(current!.page.model.root, found.parentKey);
                            const siblings = parent?.node.children ?? current!.page.model.root.children ?? [];
                            const target = Math.max(0, Math.min(siblings.length - 1, found.index + direction));
                            apply([{ op: "moveNode", key, parent: found.parentKey, index: target }]);
                        }}
                        onRemove={(key) => apply([{ op: "removeNode", key }])}
                    />
                </div>
                <div className="nf-b-actions" aria-label="Page actions">
                    <h3>Actions</h3>
                    {/* The declarative action ladder (§4) — runFlow activates with
                        PHASE-3 §8: a named flow hook on the bound entity. */}
                    <ul>
                        {current!.page.model.actions.map((action, index) => (
                            <li key={`${action.type}:${index}`}>
                                <code>{action.type}</code>
                                {action.type === "openPage" ? ` → ${action.props.page}` : ""}
                                {action.type === "runFlow" ? ` → ${action.props.hook}` : ""}
                                <button
                                    type="button"
                                    aria-label={`Remove ${action.type} action`}
                                    onClick={() => apply([{ op: "removeAction", index }])}
                                >
                                    ×
                                </button>
                            </li>
                        ))}
                    </ul>
                    <label>
                        Add action
                        <select
                            value=""
                            onChange={(event) => {
                                const type = event.target.value as ActionDef["type"];
                                if (!type) return;
                                if (type === "openPage") {
                                    apply([{ op: "addAction", action: { type: "openPage", props: { page: pageApiName(entity.apiName, "detail") } } }]);
                                } else if (type === "runFlow") {
                                    const firstFlow = entity.hooks.find((hook) => hook.flow && hook.name);
                                    apply([{ op: "addAction", action: { type: "runFlow", props: { hook: firstFlow?.name ?? "" } } }]);
                                } else {
                                    apply([{ op: "addAction", action: { type } as ActionDef }]);
                                }
                            }}
                        >
                            <option value="">— pick —</option>
                            <option value="save">save</option>
                            <option value="cancel">cancel</option>
                            <option value="delete">delete</option>
                            <option value="openPage">openPage</option>
                            {entity.hooks.some((hook) => hook.flow && hook.name) ? (
                                <option value="runFlow">runFlow</option>
                            ) : null}
                        </select>
                    </label>
                    {runFlowTargets.length > 0 ? (
                        <label>
                            Flow hook
                            <select
                                aria-label="runFlow hook"
                                value={current!.page.model.actions.find((action) => action.type === "runFlow")?.props.hook ?? ""}
                                onChange={(event) => {
                                    const index = current!.page.model.actions.findIndex((action) => action.type === "runFlow");
                                    if (index >= 0) {
                                        apply([{ op: "setAction", index, action: { type: "runFlow", props: { hook: event.target.value } } }]);
                                    }
                                }}
                            >
                                {runFlowTargets.map((hook) => (
                                    <option key={hook.name} value={hook.name}>{hook.name}</option>
                                ))}
                            </select>
                        </label>
                    ) : null}
                </div>
                <div className="nf-b-props" aria-label="Property panel">
                    <h3>Properties</h3>
                    {selected ? (
                        <PropertyPanel
                            node={selected.node}
                            onApply={(deltas) => apply(deltas)}
                        />
                    ) : (
                        <p>Select a node.</p>
                    )}
                </div>
                <div className="nf-b-preview" aria-label="Live preview">
                    <h3>Preview</h3>
                    <PageRenderer
                        page={current!.page}
                        entity={entity}
                        context={{
                            mode: "preview",
                            clock: "2026-08-24T10:00:00.000Z",
                            record: null,
                            errors: {},
                            getValue: () => undefined,
                            setValue: () => undefined,
                            actions: {
                                save: async () => {},
                                cancel: async () => {},
                                deleteRecord: async () => {},
                                openPage: async () => {},
                                runFlow: async () => {},
                            },
                            navigate: () => {},
                        }}
                    />
                </div>
            </div>
        </section>
    );
}

interface FoundNode {
    node: PageNode;
    parentKey: string | null;
    index: number;
}

function findNode(root: PageNode, key: string): FoundNode | null {
    const search = (node: PageNode, parentKey: string | null): FoundNode | null => {
        if (node.key === key) return { node, parentKey, index: -1 };
        for (const [index, child] of (node.children ?? []).entries()) {
            const direct = child.key === key ? { node: child, parentKey: node.key ?? parentKey, index } : null;
            if (direct) return direct;
            const nested = search(child, node.key ?? parentKey);
            if (nested) return nested;
        }
        return null;
    };
    return search(root, null);
}

function newNode(componentId: string): PageNode {
    const entry = catalogEntry(componentId);
    const props: Record<string, unknown> = {};
    for (const required of (entry.schema.required as string[] | undefined) ?? []) {
        props[required] = "";
    }
    return {
        type: componentId,
        version: entry.version, // the builder writes the pin explicitly on save (§4)
        key: `n:${crypto.randomUUID()}`,
        props,
    };
}

function NodeTree(props: {
    node: PageNode;
    selectedKey: string | null;
    onSelect: (key: string) => void;
    onMove: (key: string, direction: 1 | -1) => void;
    onRemove: (key: string) => void;
}): ReactNode {
    return (
        <ul className="nf-tree" role="tree">
            <TreeRow {...props} isRoot />
        </ul>
    );
}

function TreeRow({
    node,
    selectedKey,
    onSelect,
    onMove,
    onRemove,
    isRoot,
}: {
    node: PageNode;
    selectedKey: string | null;
    onSelect: (key: string) => void;
    onMove: (key: string, direction: 1 | -1) => void;
    onRemove: (key: string) => void;
    isRoot?: boolean;
}): ReactNode {
    return (
        <li role="none">
            <span
                className="nf-tree-row"
                role="treeitem"
                aria-selected={node.key === selectedKey}
                tabIndex={0}
                onKeyDown={(event) => {
                    if (node.key && (event.key === "Enter" || event.key === " ")) {
                        event.preventDefault();
                        onSelect(node.key);
                    }
                }}
                onClick={() => node.key && onSelect(node.key)}
            >
                {node.type.replace("novaforge.", "")}
                {node.bind ? <em> · {node.bind}</em> : null}
            </span>
            {!isRoot && node.key ? (
                <>
                    <button type="button" aria-label={`Move ${node.key} up`} onClick={() => onMove(node.key!, -1)}>↑</button>
                    <button type="button" aria-label={`Move ${node.key} down`} onClick={() => onMove(node.key!, 1)}>↓</button>
                    <button type="button" aria-label={`Remove ${node.key}`} onClick={() => onRemove(node.key!)}>×</button>
                </>
            ) : null}
            {node.children?.length ? (
                <ul role="group">
                    {node.children.map((child, index) => (
                        <TreeRow
                            key={child.key ?? index}
                            node={child}
                            selectedKey={selectedKey}
                            onSelect={onSelect}
                            onMove={onMove}
                            onRemove={onRemove}
                            isRoot={false}
                        />
                    ))}
                </ul>
            ) : null}
        </li>
    );
}

/** Auto-generated from the component's props JSON Schema (§8). */
function PropertyPanel({
    node,
    onApply,
}: {
    node: PageNode;
    onApply: (deltas: PageDelta[]) => void;
}): ReactNode {
    const entry = catalogEntry(node.type);
    const properties = (entry.schema.properties ?? {}) as Record<string, Record<string, unknown>>;
    const key = node.key ?? "";
    return (
        <div className="nf-props">
            <p className="nf-props-id">{node.type}@{node.version ?? "(unpinned → resolved)"}</p>
            {Object.entries(properties).map(([prop, schema]) => (
                <label key={prop}>
                    {prop}
                    {schema.type === "boolean" ? (
                        <input
                            type="checkbox"
                            checked={node.props[prop] === true}
                            onChange={(event) =>
                                onApply([{ op: event.target.checked ? "setProps" : "unsetProp", key, ...(event.target.checked ? { props: { [prop]: true } } : { prop }) } as PageDelta])
                            }
                        />
                    ) : schema.enum ? (
                        <select
                            value={String(node.props[prop] ?? "")}
                            onChange={(event) => onApply([{ op: "setProps", key, props: { [prop]: event.target.value } }])}
                        >
                            <option value="">—</option>
                            {(schema.enum as string[]).map((option) => (
                                <option key={option} value={option}>{option}</option>
                            ))}
                        </select>
                    ) : schema.type === "integer" ? (
                        <input
                            type="number"
                            value={String(node.props[prop] ?? "")}
                            onChange={(event) =>
                                onApply([{ op: "setProps", key, props: { [prop]: Number(event.target.value) } }])
                            }
                        />
                    ) : schema.type === "array" ? (
                        <input
                            value={Array.isArray(node.props[prop]) ? (node.props[prop] as unknown[]).join(",") : ""}
                            onChange={(event) =>
                                onApply([{
                                    op: "setProps",
                                    key,
                                    props: { [prop]: event.target.value.split(",").map((v) => v.trim()).filter(Boolean) },
                                }])
                            }
                        />
                    ) : (
                        <input
                            value={String(node.props[prop] ?? "")}
                            onChange={(event) => onApply([{ op: "setProps", key, props: { [prop]: event.target.value } }])}
                        />
                    )}
                </label>
            ))}
            {(["visibility", "required", "readonly", "bind"] as const).map((slot) => (
                <label key={slot}>
                    {slot} (expression DSL)
                    <input
                        value={node[slot] ?? ""}
                        placeholder={`e.g. status != 'POSTED'`}
                        onChange={(event) => onApply([{ op: "setSlot", key, slot, value: event.target.value || null }])}
                    />
                </label>
            ))}
            <label>
                Version pin
                <select
                    value={node.version ?? ""}
                    onChange={(event) => onApply([{ op: "setVersion", key, version: event.target.value }])}
                >
                    <option value="">(resolved)</option>
                    <option value={entry.version}>{entry.version}</option>
                </select>
            </label>
        </div>
    );
}

export type { ActionDef };
