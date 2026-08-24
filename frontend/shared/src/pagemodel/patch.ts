import type { ActionDef, PageDelta, PageModel, PageNode, ResolvedPage } from "./model.ts";

/**
 * RFC 6902 JSON Patch export (PHASE-2 §13 Q2): the interchange format for resolved
 * pages — the persisted artifact stays custom structural deltas (readable in
 * change-set reviews); export renders the *resolved* page document as a patch
 * against its L1 default.
 */

export type JsonPatchOp =
    | { op: "add"; path: string; value: unknown }
    | { op: "remove"; path: string }
    | { op: "replace"; path: string; value: unknown };

/** Renders a resolved page as a JSON document (patch target shape). */
export function pageDocument(page: ResolvedPage): Record<string, unknown> {
    return {
        apiName: page.apiName,
        type: page.type,
        entity: page.entity ?? null,
        base: page.model.base,
        kind: page.model.kind,
        root: page.model.root,
        actions: page.model.actions,
    };
}

function pointer(...segments: (string | number)[]): string {
    return "/" + segments.map((s) => String(s).replace(/~/g, "~0").replace(/\//g, "~1")).join("/");
}

function nodePaths(node: PageNode, base: (string | number)[], out: { path: (string | number)[]; node: PageNode }[]): void {
    out.push({ path: base, node });
    (node.children ?? []).forEach((child, index) => {
        nodePaths(child, [...base, "children", index], out);
    });
}

/**
 * The patch from an L1 default to a resolved page — structural deltas lowered to
 * the interchange format. Node identity rides stable keys.
 */
export function toPatch(base: ResolvedPage, resolved: ResolvedPage): JsonPatchOp[] {
    const ops: JsonPatchOp[] = [];
    const baseDoc = pageDocument(base);
    const resolvedDoc = pageDocument(resolved);
    const modelKeys: (keyof PageModel)[] = ["base", "kind"];
    for (const key of modelKeys) {
        if (baseDoc[key as string] !== resolvedDoc[key as string]) {
            ops.push({ op: "replace", path: pointer(key), value: resolvedDoc[key as string] });
        }
    }
    // Root-level: replace whole root when shape differs (v1 export granularity —
    // the readable artifact remains the delta list; patch is for interchange).
    if (JSON.stringify(base.model.root) !== JSON.stringify(resolved.model.root)) {
        ops.push({ op: "replace", path: "/root", value: resolved.model.root });
    }
    if (JSON.stringify(base.model.actions) !== JSON.stringify(resolved.model.actions)) {
        ops.push({ op: "replace", path: "/actions", value: resolved.model.actions });
    }
    return ops;
}

/** Documents every node by stable key (review rendering walks this). */
export function nodesByKey(model: PageModel): Map<string, { path: (string | number)[]; node: PageNode }> {
    const map = new Map<string, { path: (string | number)[]; node: PageNode }>();
    const out: { path: (string | number)[]; node: PageNode }[] = [];
    nodePaths(model.root, ["root"], out);
    for (const entry of out) {
        if (entry.node.key) {
            map.set(entry.node.key, entry);
        }
    }
    return map;
}

/** Human-readable delta summaries for change-set review (§13 Q2's rationale). */
export function describeDelta(delta: PageDelta): string {
    switch (delta.op) {
        case "insertNode":
            return `insert ${delta.node.type}${delta.node.key ? ` (${delta.node.key})` : ""} at ${delta.parent ?? "root"}[${delta.index}]`;
        case "removeNode":
            return `remove ${delta.key}`;
        case "moveNode":
            return `move ${delta.key} → ${delta.parent ?? "root"}[${delta.index}]`;
        case "setProps":
            return `set ${delta.key} props: ${Object.keys(delta.props).join(", ")}`;
        case "unsetProp":
            return `unset ${delta.key}.${delta.prop}`;
        case "setSlot":
            return delta.value === null
                ? `clear ${delta.key}.${delta.slot}`
                : `set ${delta.key}.${delta.slot} = ${delta.value}`;
        case "setVersion":
            return `pin ${delta.key} to v${delta.version}`;
        case "setBase":
            return `rebase onto ${delta.base}`;
        case "addAction":
            return `add action ${(delta.action as { type: string }).type}`;
        case "removeAction":
            return `remove action #${delta.index}`;
        case "setAction":
            return `set action #${delta.index} to ${(delta.action as { type: string }).type}`;
    }
}

export type { ActionDef };
