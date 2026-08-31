import { Suspense, createElement, type ReactNode } from "react";
import { Expression } from "../expression/expression.ts";
import { Decimal } from "../expression/decimal.ts";
import { parseDate, parseInstant } from "../expression/values.ts";
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

/**
 * Binds a record for the expression engine: raw JSON values are tagged the way the
 * engine compares them — numbers as exact Decimals, ISO date/datetime strings on
 * date-typed fields as tagged values. Untagged, every numeric and date slot rule
 * threw in the evaluator and fell back (visibility stayed visible, but readonly
 * and required wrongly evaluated TRUE on the fallback — frozen and forced fields
 * the server would accept).
 */
function bindRecord(
    record: Record<string, unknown> | null | undefined,
    fields: Record<string, { type?: string }> | undefined,
): Record<string, unknown> {
    if (!record) return {};
    const dateFields = new Set<string>();
    for (const [apiName, field] of Object.entries(fields ?? {})) {
        if (field?.type === "date" || field?.type === "datetime") {
            dateFields.add(apiName);
        }
    }
    const bound: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(record)) {
        if (typeof value === "number") {
            bound[key] = Decimal.fromNumber(value);
        } else if (typeof value === "string" && dateFields.has(key) && value !== "") {
            try {
                bound[key] = value.includes("T") ? parseInstant(value) : parseDate(value);
            } catch {
                bound[key] = value;   // not a parseable date — ride verbatim
            }
        } else {
            bound[key] = value;
        }
    }
    return bound;
}

function evaluateSlot(expression: string | undefined, context: RendererContextValue): boolean | undefined {
    if (expression === undefined) return undefined;
    try {
        const result = Expression.parse(expression).evaluate(
            { ...bindRecord(context.record, context.fields), role: context.role },
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
            // the file-upload leg rides the renderer context (auth + base): the
            // widget's own props never carried a token, and its uploaded attachment
            // id binds back to the record field through the renderer's setValue —
            // the save persists the reference instead of dropping it
            ...(node.type === "novaforge.file-upload" && context.files
                ? {
                    filesBase: context.files.base,
                    bearerToken: undefined,
                    bearerTokenProvider: context.files.token,
                    onUploaded: (attachmentId: string, virusScan?: string) => {
                        // a quarantined upload never binds: the record would persist
                        // a reference to a file that can never be downloaded
                        if (node.bind && virusScan !== "infected") {
                            context.setValue(node.bind, attachmentId);
                        }
                    },
                }
                : {}),
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
