import type { EntityDefinition, PageDefinition, PermissionSet } from "../metadata.ts";
import { applyDeltas, diffPages, type PageDelta, type PageModel, type ResolvedPage, type StaleDelta } from "./model.ts";
import { resolveDefaultPage, type ResolveOptions } from "../resolver.ts";

/**
 * Page resolution at render time (PHASE-2 §4/ADR-009 L2): the persisted artifact
 * stores structural deltas against the L1 default (`base: "auto"`); resolution
 * regenerates the default for the *current* entity + role and replays the deltas —
 * un-overridden parts follow the entity, and stale deltas (the entity changed
 * shape) report rather than corrupt.
 */

/** The persisted layout: deltas against the L1 default (canonical, §13 Q2). */
export interface PersistedLayout {
    base: "auto" | string;
    kind: "form" | "list" | "detail";
    deltas?: PageDelta[];
}

/**
 * A fully-authored page tree (the export/interchange encoding; the builder's
 * "start from resolved" path writes this back as deltas on save).
 */
export interface AuthoredLayout {
    base: "auto" | string;
    kind: "form" | "list" | "detail";
    root: PageModel["root"];
    actions: PageModel["actions"];
}

export type AnyLayout = PersistedLayout | AuthoredLayout | Record<string, unknown>;

export function isAuthoredLayout(layout: AnyLayout): layout is AuthoredLayout {
    return typeof layout === "object" && layout !== null && "root" in layout;
}

/**
 * Resolves the effective page for rendering: saved deltas over the role-shaped
 * L1 default; a saved page with no deltas is the pure default (§1 exit #2 —
 * zero page definitions still render complete pages).
 */
export function resolvePage(
    saved: PageDefinition | undefined,
    entity: EntityDefinition,
    options: ResolveOptions & { kind?: "form" | "list" | "detail" } = {},
): { page: ResolvedPage; stale: StaleDelta[] } {
    const layout = (saved?.layout ?? {}) as AnyLayout;
    const kind = (layout.kind as "form" | "list" | "detail")
        ?? options.kind
        ?? (saved?.type === "list" ? "list" : saved?.type === "detail" ? "detail" : "form");
    const base = resolveDefaultPage(entity, kind, options);
    if (!layout || typeof layout !== "object") {
        return { page: base, stale: [] };
    }
    if (isAuthoredLayout(layout)) {
        return {
            page: {
                apiName: saved?.apiName ?? base.apiName,
                type: saved?.type ?? base.type,
                entity: entity.apiName,
                model: {
                    base: layout.base ?? "auto",
                    kind,
                    root: layout.root,
                    actions: layout.actions ?? [],
                },
            },
            stale: [],
        };
    }
    const deltas = ((layout as PersistedLayout).deltas ?? []) as PageDelta[];
    const { page, stale } = applyDeltas(base, deltas);
    return {
        page: {
            ...page,
            apiName: saved?.apiName ?? page.apiName,
            type: saved?.type ?? page.type,
        },
        stale,
    };
}

/** The canonical persisted encoding of an edited page (deltas vs its L1 default). */
export function toPersistedLayout(
    edited: ResolvedPage,
    l1Default: ResolvedPage,
): PersistedLayout {
    return {
        base: "auto",
        kind: edited.model.kind,
        deltas: diffPages(l1Default, edited),
    };
}
