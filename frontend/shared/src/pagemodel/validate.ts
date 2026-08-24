import { CATALOG, type CatalogEntry } from "../catalog/schemas.ts";
import { catalogEntry } from "../registry.ts";
import { Expression } from "../expression/expression.ts";
import type { EntityDefinition } from "../metadata.ts";
import type { ActionDef, PageModel, PageNode } from "./model.ts";

/**
 * Save/publish validation (PHASE-2 §4): props validate against the component's
 * JSON Schema at save and publish; a node's `version` pins the catalog component —
 * missing at save resolves to the current stable, missing at publish rejects;
 * where the bound name repeats in widget config (`props.field`,
 * `props.relationship`) a mismatch rejects; expression slots compile against the
 * entity's fields; the action set is closed.
 */

// --- a focused JSON Schema subset (draft 2020-12) the catalog actually uses ---

export interface SchemaIssue {
    path: string;
    message: string;
}

function typeOf(value: unknown): string {
    if (value === null) return "null";
    if (Array.isArray(value)) return "array";
    if (typeof value === "number") return Number.isInteger(value) ? "integer" : "number";
    return typeof value;
}

function typeMatches(value: unknown, type: string): boolean {
    const actual = typeOf(value);
    if (type === "number") return actual === "number" || actual === "integer";
    if (type === "integer") return actual === "integer";
    return actual === type;
}

export function validateSchema(
    value: unknown,
    schema: Record<string, unknown>,
    path = "props",
): SchemaIssue[] {
    const issues: SchemaIssue[] = [];
    if (schema.type !== undefined) {
        const type = schema.type;
        const allowed = Array.isArray(type) ? (type as string[]) : [String(type)];
        if (!allowed.some((t) => typeMatches(value, t))) {
            issues.push({ path, message: `expected ${allowed.join("|")}, got ${typeOf(value)}` });
            return issues;
        }
    }
    if (schema.enum !== undefined) {
        const options = schema.enum as unknown[];
        if (!options.some((option) => JSON.stringify(option) === JSON.stringify(value))) {
            issues.push({ path, message: `must be one of ${JSON.stringify(options)}` });
        }
    }
    if (typeof value === "string") {
        const { minLength, maxLength, pattern } = schema as Record<string, number | string>;
        if (typeof minLength === "number" && value.length < minLength) {
            issues.push({ path, message: `shorter than minLength ${minLength}` });
        }
        if (typeof maxLength === "number" && value.length > maxLength) {
            issues.push({ path, message: `longer than maxLength ${maxLength}` });
        }
        if (typeof pattern === "string" && !new RegExp(pattern).test(value)) {
            issues.push({ path, message: `does not match pattern ${pattern}` });
        }
    }
    if (typeof value === "number") {
        const { minimum, maximum } = schema as Record<string, number>;
        if (typeof minimum === "number" && value < minimum) {
            issues.push({ path, message: `below minimum ${minimum}` });
        }
        if (typeof maximum === "number" && value > maximum) {
            issues.push({ path, message: `above maximum ${maximum}` });
        }
    }
    if (Array.isArray(value)) {
        const { minItems, items } = schema as Record<string, unknown>;
        if (typeof minItems === "number" && value.length < minItems) {
            issues.push({ path, message: `fewer than minItems ${minItems}` });
        }
        if (items && typeof items === "object") {
            value.forEach((item, index) => {
                issues.push(...validateSchema(item, items as Record<string, unknown>, `${path}[${index}]`));
            });
        }
    }
    if (value !== null && typeof value === "object" && !Array.isArray(value)) {
        const properties = schema.properties as Record<string, Record<string, unknown>> | undefined;
        if (properties) {
            for (const [name, sub] of Object.entries(properties)) {
                const present = (value as Record<string, unknown>)[name];
                if (present !== undefined) {
                    issues.push(...validateSchema(present, sub, `${path}.${name}`));
                }
            }
        }
        const additional = schema.additionalProperties;
        if (additional === false && properties) {
            for (const name of Object.keys(value as Record<string, unknown>)) {
                if (!(name in properties)) {
                    issues.push({ path: `${path}.${name}`, message: "unknown property (additionalProperties false)" });
                }
            }
        } else if (additional && typeof additional === "object" && properties) {
            for (const [name, sub] of Object.entries(additional as Record<string, Record<string, unknown>>)) {
                const present = (value as Record<string, unknown>)[name];
                if (present !== undefined && !(name in properties)) {
                    issues.push(...validateSchema(present, sub, `${path}.${name}`));
                }
            }
        }
        const required = schema.required as string[] | undefined;
        if (required) {
            for (const name of required) {
                if ((value as Record<string, unknown>)[name] === undefined) {
                    issues.push({ path, message: `missing required property '${name}'` });
                }
            }
        }
    }
    return issues;
}

// --- page validation ---

/** The closed action ladder (PHASE-2 §4; runFlow activated by PHASE-3 §8). */
const CLOSED_ACTIONS = new Set(["save", "cancel", "delete", "openPage", "runFlow"]);

/** Whether a component consumes a `bind` slot (its catalog contract, §6 item 1). */
export function takesBinding(componentId: string): boolean {
    return componentId.startsWith("novaforge.field-") || componentId === "novaforge.related-list";
}

export interface ValidationContext {
    entity: EntityDefinition;
    /** `save` resolves missing versions to the catalog's current stable; `publish` rejects. */
    mode: "save" | "publish";
}

/** The full verdict: blocking issues plus lifecycle warnings (§6 item 2). */
export interface PageVerdict {
    issues: SchemaIssue[];
    warnings: SchemaIssue[];
}

/**
 * Lifecycle warnings (PHASE-2 §6 item 2): a page pinning a deprecated component
 * still saves — pinning is the compatibility contract — but the deprecation's
 * migration guidance surfaces at save so it is never silent. Draft components
 * warn at publish (they are not stable contracts). Pure over the entries so the
 * suites can drive synthetic lifecycles.
 */
export function lifecycleWarnings(
    nodes: PageNode[],
    entries: readonly CatalogEntry[],
    mode: "save" | "publish",
): SchemaIssue[] {
    const warnings: SchemaIssue[] = [];
    const walk = (node: PageNode, path: string): void => {
        const entry = entries.find((candidate) => candidate.id === node.type);
        if (entry?.status === "deprecated") {
            const guidance = entry.deprecation?.migrateTo
                ? ` — migrate to ${entry.deprecation.migrateTo}`
                : "";
            warnings.push({
                path,
                message: `${node.type} is deprecated: ${entry.deprecation?.reason ?? "superseded"}${guidance}`,
            });
        } else if (entry?.status === "draft" && mode === "publish") {
            warnings.push({
                path,
                message: `${node.type} is still draft — publishing pins an unstable contract`,
            });
        }
        (node.children ?? []).forEach((child, index) => walk(child, `${path}.children[${index}]`));
    };
    nodes.forEach((node) => walk(node, "root"));
    return warnings;
}

export function checkPage(model: PageModel, context: ValidationContext): PageVerdict {
    return {
        issues: validatePage(model, context),
        warnings: lifecycleWarnings([model.root], CATALOG, context.mode),
    };
}

export function validatePage(
    model: PageModel,
    context: ValidationContext,
): SchemaIssue[] {
    const issues: SchemaIssue[] = [];
    const fields = new Set(context.entity.fields.map((field) => field.apiName));
    const relationships = new Set(context.entity.relationships.map((rel) => rel.apiName));

    const walk = (node: PageNode, path: string): void => {
        // Unknown component: builder build error; the runtime renders a safe fallback.
        const known = CATALOG.some((entry) => entry.id === node.type);
        if (!known) {
            issues.push({ path, message: `unknown component '${node.type}'` });
        } else {
            const entry = catalogEntry(node.type);
            if (node.version === undefined) {
                if (context.mode === "publish") {
                    issues.push({
                        path,
                        message: `missing pinned version (publish requires ${node.type}@${entry.version})`,
                    });
                }
            } else if (node.version !== entry.version) {
                issues.push({
                    path,
                    message: `unknown version ${node.type}@${node.version} (catalog serves ${entry.version})`,
                });
            }
            issues.push(...validateSchema(node.props, entry.schema, path));
            // Bind/props repetition rule (§4): where the bound name repeats in widget
            // config, a mismatch rejects at save and publish.
            const repeated = (node.props.field as string | undefined) ?? undefined;
            if (repeated !== undefined && node.bind !== undefined && repeated !== node.bind) {
                issues.push({
                    path,
                    message: `bind '${node.bind}' and props.field '${repeated}' disagree`,
                });
            }
            const rel = node.props.relationship as string | undefined;
            if (rel !== undefined && node.bind !== undefined && rel !== node.bind) {
                issues.push({
                    path,
                    message: `bind '${node.bind}' and props.relationship '${rel}' disagree`,
                });
            }
            if (takesBinding(node.type)) {
                if (node.bind === undefined) {
                    issues.push({ path, message: `${node.type} requires a bind` });
                } else {
                    const bound = node.bind;
                    const head = bound.split(".")[0]!;
                    if (!fields.has(head) && !relationships.has(head)) {
                        issues.push({
                            path,
                            message: `bind '${bound}' resolves to no field or relationship on ${context.entity.apiName}`,
                        });
                    }
                }
            }
        }
        // Expression slots compile against the entity context (§7 — the same
        // compile-check the Metadata Service runs server-side).
        for (const slot of ["visibility", "required", "readonly"] as const) {
            const expression = node[slot];
            if (expression === undefined) continue;
            try {
                Expression.parse(expression).compileCheck({
                    bindings: [...fields, ...relationships, "record", "role", "today", "now"],
                    allowClock: true,
                });
            } catch (error) {
                issues.push({
                    path: `${path}.${slot}`,
                    message: error instanceof Error ? error.message : String(error),
                });
            }
        }
        (node.children ?? []).forEach((child, index) => walk(child, `${path}.children[${index}]`));
    };
    walk(model.root, "root");

    model.actions.forEach((action: ActionDef, index) => {
        if (!CLOSED_ACTIONS.has(action.type)) {
            issues.push({ path: `actions[${index}]`, message: `unknown action type '${action.type}'` });
        }
        if (action.type === "openPage" && !action.props.page) {
            issues.push({ path: `actions[${index}]`, message: "openPage requires props.page" });
        }
        if (action.type === "runFlow" && !action.props.hook) {
            issues.push({ path: `actions[${index}]`, message: "runFlow requires props.hook" });
        }
    });

    return issues;
}
