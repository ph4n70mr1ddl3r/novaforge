import { createContext, useContext } from "react";
import type { FieldDefinition } from "../metadata.ts";
import type { ActionDef } from "../pagemodel/model.ts";

/**
 * The renderer's runtime surface (ADR-009 L3): catalog components are
 * presentational and behavior-free — data, actions, navigation, and localization
 * arrive through this context, which the runtime shell (TanStack Query against
 * the Phase 1 APIs) and the builder's live preview both implement. The preview
 * runs the *real* renderer (PHASE-2 §8), so there is no second implementation.
 */

export interface QueryFilterLeaf {
    field: string;
    op: "eq" | "ne" | "in" | "gt" | "gte" | "lt" | "lte" | "contains" | "isNull";
    value?: unknown;
}

export interface QueryFilterComposite {
    op: "and" | "or";
    children: QueryFilter[];
}

export type QueryFilter = QueryFilterLeaf | QueryFilterComposite;

export interface ListRequest {
    entity: string;
    filter?: QueryFilter;
    sort?: { field: string; dir: "asc" | "desc" }[];
    size: number;
    offset: number;
}

export interface ListResult {
    rows: Record<string, unknown>[];
    total: number;
}

/** The data service the shell provides (server-side paging — never client slicing). */
export interface RendererDataService {
    list(request: ListRequest): Promise<ListResult>;
    search(target: string, term: string, size?: number): Promise<Record<string, unknown>[]>;
}

export interface RendererUser {
    name: string;
    roles: string[];
    locale?: string;
}

export interface RendererContextValue {
    mode: "runtime" | "preview";
    /** The page's declarative action ladder — layouts render them (§4). */
    pageActions?: ActionDef[];
    entity?: string;
    /** The governing clock for expression evaluation (server time / frozen in suites). */
    clock: string;
    user?: RendererUser;
    role?: string;
    /** Field metadata by apiName (for labels, types, options). */
    fields: Record<string, FieldDefinition>;
    /** The record under edit/view (null on lists). */
    record: Record<string, unknown> | null;
    getValue(path: string): unknown;
    setValue(path: string, value: unknown): void;
    /** Validation errors keyed by field apiName (server problem bodies map here). */
    errors: Record<string, string>;
    busy?: boolean;
    actions: {
        save(): Promise<void>;
        cancel(): Promise<void>;
        deleteRecord(): Promise<void>;
        openPage(page: string, id?: string): Promise<void>;
        transition?(to: string): Promise<void>;
    };
    navigate(entity: string, view: "list" | "form" | "detail", id?: string): void;
    data?: RendererDataService;
    /** State-machine actions available on the current record (PHASE-4 §3). */
    transitions?: { to: string; label?: string; guard?: string }[];
    /**
     * A query-DSL filter the list page consumes natively (PHASE-5 §5): drill-through
     * deep links carry the report row's group filters here — the runtime list page
     * splices it into every paged request, server-side (never client slicing).
     */
    listFilter?: QueryFilter;
}

export const RendererContext = createContext<RendererContextValue | null>(null);

export function useRenderer(): RendererContextValue {
    const context = useContext(RendererContext);
    if (!context) {
        throw new Error("renderer context missing — components render only inside PageRenderer");
    }
    return context;
}

/** Resolves a node's bound value from the record (relationship paths dot-walk). */
export function useBoundValue(bind: string | undefined): unknown {
    const renderer = useRenderer();
    if (!bind) return undefined;
    return renderer.getValue(bind);
}

export function dispatchAction(
    context: RendererContextValue,
    action: ActionDef,
): Promise<void> {
    switch (action.type) {
        case "save":
            return context.actions.save();
        case "cancel":
            return context.actions.cancel();
        case "delete":
            return context.actions.deleteRecord();
        case "openPage": {
            // String-valued props interpolate the record with ${path} templates (§4).
            const id = action.props.id ? interpolate(action.props.id, context.record) : undefined;
            return context.actions.openPage(interpolate(action.props.page, context.record), id);
        }
    }
}

/** `${record.path}` template interpolation — the ADR-008 record-template convention. */
export function interpolate(template: string, record: Record<string, unknown> | null): string {
    return template.replace(/\$\{([^}]+)\}/g, (_, rawPath: string) => {
        const path = rawPath.replace(/^record\./, "");
        const value = resolvePath(record, path);
        return value === null || value === undefined ? "" : String(value);
    });
}

export function resolvePath(record: Record<string, unknown> | null, path: string): unknown {
    let current: unknown = record;
    for (const segment of path.split(".")) {
        if (typeof current === "object" && current !== null && segment in current) {
            current = (current as Record<string, unknown>)[segment];
        } else {
            return undefined;
        }
    }
    return current;
}
