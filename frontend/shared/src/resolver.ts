import {
    type AppDefinition,
    type EntityDefinition,
    type FieldDefinition,
    type PermissionSet,
    resolveFieldAccess,
    resolveLabel,
    resolveObjectAccess,
} from "./metadata.ts";
import { defaultWidgetFor, type PageModel, type PageNode, type ResolvedPage } from "./pagemodel/model.ts";

/**
 * resolveDefaultPage (PHASE-2 §5) — the L1 layer: a pure function from
 * (entity, role, permissions) to the default PageDefinition. It runs client-side
 * so the builder previews defaults instantly (ADR-009); the same output is
 * golden-tested in CI. Role changes re-resolve defaults — L1 is role-parameterized.
 */

function widgetFor(field: FieldDefinition): PageNode {
    const node: PageNode = {
        type: defaultWidgetFor(field.type),
        key: `field:${field.apiName}`,
        props: { field: field.apiName },
        bind: field.apiName,
    };
    if (field.type === "longText") {
        node.props.multiline = true;
    }
    if (field.type === "uuid") {
        node.readonly = "true";
    }
    if (field.type === "email") {
        node.props.inputType = "email";
    }
    if (field.type === "phone") {
        node.props.inputType = "tel";
    }
    if (field.type === "url") {
        node.props.inputType = "url";
    }
    if (field.type === "enum" && field.values?.length) {
        node.props.options = field.values;
    }
    if (field.type === "lookup") {
        node.props.target = field.target;
        node.props.minChars = 2;
    }
    if (field.type === "m2m") {
        node.props.target = field.target;
    }
    if (field.type === "money" && field.currency) {
        node.props.currency = field.currency;
    }
    if (field.type === "date" || field.type === "datetime" || field.type === "time") {
        node.props.mode = field.type;
    }
    if (field.type === "json") {
        node.readonly = "true";
    }
    if (field.type === "file") {
        // The File Service ships with Phase 6 — the widget renders disabled until bound.
        node.props.stub = true;
    }
    return node;
}

/** Computed fields are server-owned: formulas store at write, roll-ups recompute. */
function computedReadonly(field: FieldDefinition): boolean {
    return field.formula !== undefined || field.rollup !== undefined || field.readonly === true;
}

function visibleFields(entity: EntityDefinition, permissions: PermissionSet, role: string | undefined): FieldDefinition[] {
    return entity.fields.filter(
        (field) => resolveFieldAccess(permissions, role, entity.apiName, field.apiName).visible,
    );
}

export interface ResolveOptions {
    role?: string;
    permissions?: PermissionSet;
    locale?: string;
}

function labelProps(entity: EntityDefinition, field: FieldDefinition, locale?: string): Record<string, unknown> {
    return { label: resolveLabel(field, locale, field.apiName) };
}

function formDefault(entity: EntityDefinition, options: ResolveOptions): PageModel {
    const permissions = options.permissions ?? { roles: [], objectPermissions: [], fieldSecurity: [] };
    const nodes: PageNode[] = [];
    const related: PageNode[] = [];
    for (const field of visibleFields(entity, permissions, options.role)) {
        const access = resolveFieldAccess(permissions, options.role, entity.apiName, field.apiName);
        if (field.type === "child") {
            continue; // children render as RelatedList below the form body
        }
        const node = widgetFor(field);
        node.props = { ...labelProps(entity, field, options.locale), ...node.props };
        if (field.required) {
            node.required = "true";
        }
        if (computedReadonly(field) || access.readonly) {
            node.readonly = "true";
        }
        nodes.push(node);
    }
    for (const relationship of entity.relationships.filter((rel) => rel.type === "child")) {
        related.push({
            type: "novaforge.related-list",
            key: `related:${relationship.apiName}`,
            props: { relationship: relationship.apiName, target: relationship.target, pageSize: 50 },
            bind: relationship.apiName,
        });
    }
    return {
        base: "auto",
        kind: "form",
        root: {
            type: "novaforge.form-layout",
            key: "form",
            props: {
                columns: 2,
                title: resolveLabel(entity, options.locale, entity.apiName),
            },
            children: [...nodes, ...related],
        },
        actions: [
            { type: "save" },
            { type: "cancel" },
        ],
    };
}

function listDefault(entity: EntityDefinition, options: ResolveOptions): PageModel {
    const permissions = options.permissions ?? { roles: [], objectPermissions: [], fieldSecurity: [] };
    const access = resolveObjectAccess(permissions, options.role, entity.apiName);
    const display = entity.displayField ?? entity.fields[0]?.apiName;
    // Display field + next 4 visible-by-role fields (§5).
    const columns = visibleFields(entity, permissions, options.role)
        .filter((field) => field.apiName !== display)
        .slice(0, 4);
    const displayField = entity.fields.find((field) => field.apiName === display);
    const columnNodes: PageNode[] = [];
    if (displayField) {
        const node = widgetFor(displayField);
        node.props = { ...labelProps(entity, displayField, options.locale), ...node.props };
        node.readonly = "true";
        columnNodes.push(node);
    }
    for (const field of columns) {
        const node = widgetFor(field);
        node.props = { ...labelProps(entity, field, options.locale), ...node.props };
        node.readonly = "true";
        columnNodes.push(node);
    }
    const children = [...columnNodes];
    if (access.update || access.delete) {
        children.push({
            type: "novaforge.record-actions",
            key: "actions",
            props: { showEdit: access.update, showDelete: access.delete },
        });
    }
    return {
        base: "auto",
        kind: "list",
        root: {
            type: "novaforge.list-layout",
            key: "list",
            props: {
                title: resolveLabel(entity, options.locale, entity.apiName),
                pageSize: 50,
                sortable: true,
                columns: [display, ...columns.map((field) => field.apiName)].filter(
                    (name): name is string => Boolean(name),
                ),
            },
            children,
        },
        actions: access.create
            ? [{ type: "openPage", props: { page: pageApiName(entity.apiName, "form") } }]
            : [],
    };
}

function detailDefault(entity: EntityDefinition, options: ResolveOptions): PageModel {
    const permissions = options.permissions ?? { roles: [], objectPermissions: [], fieldSecurity: [] };
    // Sections grouped by field `group`; no group → one default section (§5).
    const groups = new Map<string, FieldDefinition[]>();
    for (const field of visibleFields(entity, permissions, options.role)) {
        if (field.type === "child") continue;
        const group = field.group ?? "";
        const bucket = groups.get(group);
        if (bucket) {
            bucket.push(field);
        } else {
            groups.set(group, [field]);
        }
    }
    const sections: PageNode[] = [...groups.entries()].map(([group, fields], index) => ({
        type: "novaforge.form-layout",
        key: `section:${group || index}`,
        props: { columns: 2, section: group || null },
        children: fields.map((field) => {
            const node = widgetFor(field);
            node.props = { ...labelProps(entity, field, options.locale), ...node.props };
            node.readonly = "true"; // detail views render read-only
            return node;
        }),
    }));
    for (const relationship of entity.relationships.filter((rel) => rel.type === "child")) {
        sections.push({
            type: "novaforge.related-list",
            key: `related:${relationship.apiName}`,
            props: { relationship: relationship.apiName, target: relationship.target, pageSize: 50 },
            bind: relationship.apiName,
        });
    }
    const display = entity.displayField ?? entity.fields[0]?.apiName;
    const displayFieldDef = entity.fields.find((field) => field.apiName === display);
    return {
        base: "auto",
        kind: "detail",
        root: {
            type: "novaforge.record-header",
            key: "header",
            props: {
                title: resolveLabel(entity, options.locale, entity.apiName),
                displayField: displayFieldDef
                    ? resolveLabel(displayFieldDef, options.locale, displayFieldDef.apiName)
                    : display,
            },
            children: sections,
        },
        actions: [
            { type: "openPage", props: { page: pageApiName(entity.apiName, "form"), id: "${record.id}" } },
            { type: "cancel" },
        ],
    };
}

/** Page apiName for (entity, kind) — camelCase per schema v0 (e.g. `orderForm`). */
export function pageApiName(entityApiName: string, kind: "form" | "list" | "detail"): string {
    const lowerFirst = entityApiName.charAt(0).toLowerCase() + entityApiName.slice(1);
    const suffix = kind === "form" ? "Form" : kind === "list" ? "List" : "Detail";
    return `${lowerFirst}${suffix}`;
}

/** The L1 default for (entity, kind, role) — pure; golden-tested per §11 item 1. */
export function resolveDefaultPage(
    entity: EntityDefinition,
    kind: "form" | "list" | "detail",
    options: ResolveOptions = {},
): ResolvedPage {
    const model =
        kind === "form"
            ? formDefault(entity, options)
            : kind === "list"
                ? listDefault(entity, options)
                : detailDefault(entity, options);
    return {
        apiName: pageApiName(entity.apiName, kind),
        type: kind === "detail" ? "detail" : kind,
        entity: entity.apiName,
        model,
    };
}

// --- navigation (§5): entities grouped by `module`; no module → a default group ---

export interface NavGroup {
    label: string;
    entities: { apiName: string; label: string }[];
}

export function resolveNav(app: AppDefinition, options: ResolveOptions = {}): NavGroup[] {
    const permissions = options.permissions ?? app.permissionSet;
    const groups = new Map<string, NavGroup>();
    for (const entity of app.entities) {
        const access = resolveObjectAccess(permissions, options.role, entity.apiName);
        if (!access.read) continue;
        const group = entity.module ?? "";
        const bucket = groups.get(group) ?? { label: group || "App", entities: [] };
        bucket.entities.push({
            apiName: entity.apiName,
            label: resolveLabel(entity, options.locale, entity.apiName),
        });
        groups.set(group, bucket);
    }
    return [...groups.values()];
}
