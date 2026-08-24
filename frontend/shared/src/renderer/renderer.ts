import { Suspense, createElement, type ReactNode } from "react";
import { Expression } from "../expression/expression.ts";
import type { EntityDefinition } from "../metadata.ts";
import { CATALOG } from "../catalog/schemas.ts";
import { resolveComponent } from "../registry.ts";
import { RendererContext, type RendererContextValue } from "./context.ts";
import type { PageNode, ResolvedPage } from "../pagemodel/model.ts";

/**
 * The runtime renderer (PHASE-2 §6 / ADR-009 L3): a recursive interpreter over
 * page JSON. It never branches on entity specifics — only on component types;
 * unknown components or versions render the safe fallback (EmptyState) while the
 * builder treats them as build errors (§4). Visibility/required/readonly evaluate
 * through the shared expression engine — UX sugar only; the Data Runtime enforces
 * server-side (§7 security note).
 */

function EmptyFallback({ reason }: { reason: string }) {
    return createElement(
        "div",
        {
            role: "status",
            className: "nf-fallback",
            "data-testid": "nf-fallback",
        },
        createElement("p", null, reason),
    );
}

function evaluateSlot(expression: string | undefined, context: RendererContextValue): boolean | undefined {
    if (expression === undefined) return undefined;
    try {
        const result = Expression.parse(expression).evaluate(
            { ...(context.record ?? {}), role: context.role },
            context.clock,
        );
        return result === true;
    } catch {
        // A failing binding renders conservatively visible — never blank pages.
        return true;
    }
}

function renderNode(node: PageNode, context: RendererContextValue, keyPrefix: string): ReactNode {
    const visible = evaluateSlot(node.visibility, context);
    if (visible === false) {
        return null;
    }
    let Component: ReturnType<typeof resolveComponent>;
    try {
        const version = node.version ?? currentVersion(node.type);
        Component = resolveComponent(node.type, version);
    } catch {
        return createElement(EmptyFallback, {
            key: keyPrefix,
            reason: `Unknown component ${node.type}${node.version ? `@${node.version}` : ""}`,
        });
    }
    const readonly =
        evaluateSlot(node.readonly, context) === true ||
        context.record === null && node.readonly === "true";
    const required = evaluateSlot(node.required, context) === true || node.required === "true";
    const children = (node.children ?? []).map((child, index) =>
        renderNode(child, context, `${keyPrefix}.${index}`),
    );
    return createElement(
        Component,
        {
            key: keyPrefix,
            ...node.props,
            bind: node.bind,
            nodeKey: node.key,
            readonly,
            required,
        },
        children.length > 0 ? children : undefined,
    );
}

function currentVersion(componentId: string): string {
    // An unpinned node resolves to the catalog's current stable (the builder writes
    // the pin explicitly on save; publish rejects unpinned pages — §4).
    const entry = CATALOG.find((candidate) => candidate.id === componentId);
    if (!entry) throw new Error(`unknown catalog component: ${componentId}`);
    return entry.version;
}

export interface PageRendererProps {
    page: ResolvedPage;
    entity: EntityDefinition;
    context: Omit<RendererContextValue, "fields"> & { fields?: Record<string, EntityDefinition["fields"][number]> };
}

/** Renders a resolved page: one provider + the recursive interpreter. */
export function PageRenderer({ page, entity, context }: PageRendererProps): ReactNode {
    const fields: Record<string, EntityDefinition["fields"][number]> = {};
    for (const field of entity.fields) {
        fields[field.apiName] = field;
    }
    const value: RendererContextValue = {
        ...context,
        entity: entity.apiName,
        fields: context.fields ?? fields,
        pageActions: page.model.actions,
    };
    return createElement(
        RendererContext.Provider,
        { value },
        createElement(
            Suspense,
            { fallback: createElement(EmptyFallback, { reason: "Loading…" }) },
            renderNode(page.model.root, value, "root"),
        ),
    );
}
