import type { FieldType, PageType } from "../metadata.ts";

/**
 * Page model v0 (PHASE-2 §4): a page is a component tree plus a declarative action
 * ladder; the persisted artifact stores **structural deltas against the L1 default**
 * (§13 Q2, resolved — custom deltas for readable change-set reviews; JSON Patch is
 * the export/interchange format, see `patch.ts`). The renderer is a recursive
 * interpreter over the resolved tree and never branches on entity specifics (ADR-009).
 */

export interface PageNode {
    /** Catalog component id (`novaforge.<component>`). */
    type: string;
    /** Pins the catalog component version — required at publish, resolved at save. */
    version?: string;
    /** Stable address for delta application: L1 keys are deterministic, builder inserts carry `n:<id>`. */
    key?: string;
    props: Record<string, unknown>;
    children?: PageNode[];
    /** Data binding — the record field (or relationship path for collection widgets). */
    bind?: string;
    /** Expression-DSL bindings (§7) overriding field-metadata defaults. */
    visibility?: string;
    required?: string;
    readonly?: string;
}

export type ActionDef =
    | { type: "save" }
    | { type: "cancel" }
    | { type: "delete" }
    | { type: "openPage"; props: { page: string; id?: string } };

/** The concrete page model carried in PageDefinition.layout. */
export interface PageModel {
    /** Which generated default this page overlays (`auto`) or another saved page. */
    base: "auto" | string;
    /** Finer-grained than the wire type: form | list | detail (the L1 shapes). */
    kind: "form" | "list" | "detail";
    root: PageNode;
    actions: ActionDef[];
}

export interface ResolvedPage {
    apiName: string;
    type: PageType;
    entity?: string;
    model: PageModel;
}

// --- structural deltas (§13 Q2) ---

export type PageDelta =
    | { op: "insertNode"; parent: string | null; index: number; node: PageNode }
    | { op: "removeNode"; key: string }
    | { op: "moveNode"; key: string; parent: string | null; index: number }
    | { op: "setProps"; key: string; props: Record<string, unknown> }
    | { op: "unsetProp"; key: string; prop: string }
    | { op: "setSlot"; key: string; slot: "visibility" | "required" | "readonly" | "bind"; value: string | null }
    | { op: "setVersion"; key: string; version: string }
    | { op: "setBase"; base: "auto" | string }
    | { op: "addAction"; index?: number; action: ActionDef }
    | { op: "removeAction"; index: number };

/** A delta that no longer applies after the entity (or base) changed — §11 item 4. */
export interface StaleDelta {
    delta: PageDelta;
    reason: string;
}

function cloneNode(node: PageNode): PageNode {
    return {
        ...node,
        props: { ...node.props },
        children: node.children ? node.children.map(cloneNode) : undefined,
    };
}

function clonePage(page: ResolvedPage): ResolvedPage {
    return {
        ...page,
        model: {
            ...page.model,
            root: cloneNode(page.model.root),
            actions: [...page.model.actions],
        },
    };
}

interface ParentRef {
    children: PageNode[];
}

function locate(tree: PageNode, key: string): { parent: PageNode | null; index: number; node: PageNode } | undefined {
    if (tree.key === key) {
        return { parent: null, index: -1, node: tree };
    }
    const search = (parent: PageNode): { parent: PageNode; index: number; node: PageNode } | undefined => {
        const children = parent.children ?? [];
        for (let i = 0; i < children.length; i++) {
            const child = children[i]!;
            if (child.key === key) {
                return { parent, index: i, node: child };
            }
            const nested = search(child);
            if (nested) return nested;
        }
        return undefined;
    };
    return search(tree);
}

function childrenOf(root: PageNode, parentKey: string | null): PageNode[] | undefined {
    if (parentKey === null) {
        return undefined; // caller handles root replacement
    }
    const found = locate(root, parentKey);
    if (!found) return undefined;
    if (!found.node.children) {
        found.node.children = [];
    }
    return found.node.children;
}

/**
 * Applies structural deltas to a resolved page. Un-overridden parts follow the
 * entity (ADR-009 L2): every delta addresses the L1 default's stable node keys;
 * when the entity changed shape, unmatched deltas report as stale instead of
 * silently corrupting the resolved tree (§11 item 4).
 */
export function applyDeltas(
    base: ResolvedPage,
    deltas: readonly PageDelta[],
): { page: ResolvedPage; stale: StaleDelta[] } {
    const page = clonePage(base);
    const stale: StaleDelta[] = [];
    const model = page.model;

    for (const delta of deltas) {
        switch (delta.op) {
            case "setBase":
                model.base = delta.base;
                break;
            case "insertNode": {
                const siblings = childrenOf(model.root, delta.parent);
                if (!siblings) {
                    stale.push({ delta, reason: `parent '${delta.parent}' not found` });
                    break;
                }
                siblings.splice(Math.min(delta.index, siblings.length), 0, cloneNode(delta.node));
                break;
            }
            case "removeNode": {
                const found = locate(model.root, delta.key);
                if (!found || !found.parent) {
                    stale.push({ delta, reason: `node '${delta.key}' not found` });
                    break;
                }
                (found.parent.children as PageNode[]).splice(found.index, 1);
                break;
            }
            case "moveNode": {
                const found = locate(model.root, delta.key);
                if (!found || !found.parent) {
                    stale.push({ delta, reason: `node '${delta.key}' not found` });
                    break;
                }
                const [node] = (found.parent.children as PageNode[]).splice(found.index, 1);
                const siblings = childrenOf(model.root, delta.parent);
                if (!siblings) {
                    // restore before reporting stale
                    (found.parent.children as PageNode[]).splice(found.index, 0, node!);
                    stale.push({ delta, reason: `parent '${delta.parent}' not found` });
                    break;
                }
                siblings.splice(Math.min(delta.index, siblings.length), 0, node!);
                break;
            }
            case "setProps": {
                const found = locate(model.root, delta.key);
                if (!found) {
                    stale.push({ delta, reason: `node '${delta.key}' not found` });
                    break;
                }
                Object.assign(found.node.props, delta.props);
                break;
            }
            case "unsetProp": {
                const found = locate(model.root, delta.key);
                if (!found) {
                    stale.push({ delta, reason: `node '${delta.key}' not found` });
                    break;
                }
                delete found.node.props[delta.prop];
                break;
            }
            case "setSlot": {
                const found = locate(model.root, delta.key);
                if (!found) {
                    stale.push({ delta, reason: `node '${delta.key}' not found` });
                    break;
                }
                const { slot, value } = delta;
                if (value === null) {
                    delete found.node[slot];
                } else {
                    found.node[slot] = value;
                }
                break;
            }
            case "setVersion": {
                const found = locate(model.root, delta.key);
                if (!found) {
                    stale.push({ delta, reason: `node '${delta.key}' not found` });
                    break;
                }
                found.node.version = delta.version;
                break;
            }
            case "addAction":
                model.actions.splice(
                    delta.index ?? model.actions.length,
                    0,
                    delta.action,
                );
                break;
            case "removeAction":
                if (delta.index < 0 || delta.index >= model.actions.length) {
                    stale.push({ delta, reason: `action index ${delta.index} out of range` });
                    break;
                }
                model.actions.splice(delta.index, 1);
                break;
        }
    }

    return { page, stale };
}

// --- diffing (undo/redo rides full snapshots; diff drives readable change sets) ---

/** Structural deltas that transform `from` into `to` (key-matched; reorder-aware). */
export function diffPages(from: ResolvedPage, to: ResolvedPage): PageDelta[] {
    const deltas: PageDelta[] = [];
    if (from.model.base !== to.model.base) {
        deltas.push({ op: "setBase", base: to.model.base });
    }
    const walk = (a: PageNode, b: PageNode): void => {
        const key = b.key ?? a.key;
        if (!key) return;
        if (a.type !== b.type) {
            deltas.push({ op: "setProps", key, props: b.props });
            if (b.version !== undefined) {
                deltas.push({ op: "setVersion", key, version: b.version });
            }
        } else if (a.version !== b.version && b.version !== undefined) {
            deltas.push({ op: "setVersion", key, version: b.version });
        }
        const propKeys = new Set([...Object.keys(a.props), ...Object.keys(b.props)]);
        for (const prop of propKeys) {
            if (JSON.stringify(a.props[prop]) !== JSON.stringify(b.props[prop])) {
                if (b.props[prop] === undefined) {
                    deltas.push({ op: "unsetProp", key, prop });
                } else {
                    deltas.push({ op: "setProps", key, props: { [prop]: b.props[prop] } });
                }
            }
        }
        for (const slot of ["visibility", "required", "readonly", "bind"] as const) {
            if (a[slot] !== b[slot]) {
                deltas.push({ op: "setSlot", key, slot, value: b[slot] ?? null });
            }
        }
        diffChildren(a, b, key);
    };
    const diffChildren = (a: PageNode, b: PageNode, parentKey: string): void => {
        const working = [...(a.children ?? [])];
        const bChildren = b.children ?? [];
        // removes: keys present in a but not b
        const bKeys = new Set(bChildren.map((child) => child.key));
        for (let i = working.length - 1; i >= 0; i--) {
            const child = working[i]!;
            if (child.key && !bKeys.has(child.key)) {
                working.splice(i, 1);
                deltas.push({ op: "removeNode", key: child.key });
            }
        }
        // inserts: keys new in b, at their authored index
        const workingKeys = new Set(working.map((child) => child.key));
        for (let i = bChildren.length - 1; i >= 0; i--) {
            const child = bChildren[i]!;
            if (child.key && !workingKeys.has(child.key)) {
                working.splice(i, 0, child);
                deltas.push({ op: "insertNode", parent: parentKey, index: i, node: child });
            }
        }
        // reorder: transform the working order into b's order via moves
        for (let i = 0; i < bChildren.length; i++) {
            const desired = bChildren[i]!;
            const current = working[i];
            if (current?.key !== undefined && desired.key !== undefined && current.key !== desired.key) {
                const from = working.findIndex((child) => child.key === desired.key);
                if (from >= 0) {
                    const [moved] = working.splice(from, 1);
                    working.splice(i, 0, moved!);
                    deltas.push({ op: "moveNode", key: desired.key, parent: parentKey, index: i });
                }
            }
        }
        // prop/slot updates on the matched pairs
        for (const child of bChildren) {
            if (!child.key) continue;
            const pair = working.find((candidate) => candidate.key === child.key)
                ?? (a.children ?? []).find((candidate) => candidate.key === child.key);
            if (pair) {
                walk(pair, child);
            }
        }
    };
    walk(from.model.root, to.model.root);
    if (JSON.stringify(from.model.actions) !== JSON.stringify(to.model.actions)) {
        // v1 diff granularity: full action-list replacement (minimal per-index diffs
        // add no review value for ≤4-entry ladders)
        for (let i = from.model.actions.length - 1; i >= 0; i--) {
            deltas.push({ op: "removeAction", index: i });
        }
        to.model.actions.forEach((action) => deltas.push({ op: "addAction", action }));
    }
    return deltas;
}

/** Field type → the L1 default widget's catalog id (§5's mapping table). */
export function defaultWidgetFor(type: FieldType): string {
    switch (type) {
        case "text":
        case "email":
        case "phone":
        case "url":
        case "uuid":
        case "longText":
            return "novaforge.field-input";
        case "richText":
            return "novaforge.field-rich-text";
        case "enum":
            return "novaforge.field-select";
        case "boolean":
            return "novaforge.field-switch";
        case "int":
        case "long":
        case "decimal":
        case "money":
            return "novaforge.field-number";
        case "date":
        case "datetime":
        case "time":
            return "novaforge.field-date";
        case "lookup":
            return "novaforge.field-lookup";
        case "m2m":
            return "novaforge.field-multi-lookup";
        case "child":
            return "novaforge.related-list";
        case "json":
            return "novaforge.field-json";
        case "file":
            return "novaforge.file-upload";
    }
}

export type { ParentRef };
