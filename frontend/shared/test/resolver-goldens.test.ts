import { describe, expect, it } from "vitest";
import { resolveDefaultPage, resolveNav } from "../src/resolver.ts";
import type { AppDefinition, EntityDefinition, PermissionSet } from "../src/metadata.ts";

/**
 * Golden files (PHASE-2 §11 item 1): resolveDefaultPage snapshots per (entity,
 * role) fixture — fail loudly on behavior change; intentional changes update
 * goldens in the same PR.
 */

const customer: EntityDefinition = {
    apiName: "Customer",
    label: "Customer",
    displayField: "name",
    module: "Sales",
    fields: [
        { apiName: "name", type: "text", required: true },
        { apiName: "email", type: "email" },
        { apiName: "region", type: "enum", values: ["EU", "US", "APAC"] },
        { apiName: "active", type: "boolean" },
        { apiName: "creditLimit", type: "money", currency: "USD" },
        { apiName: "since", type: "date", group: "Details" },
    ],
    relationships: [],
    validations: [],
    hooks: [],
    indexes: [],
};

const order: EntityDefinition = {
    apiName: "Order",
    label: "Sales Order",
    displayField: "reference",
    module: "Sales",
    fields: [
        { apiName: "reference", type: "text", required: true },
        { apiName: "status", type: "enum", values: ["DRAFT", "POSTED"] },
        { apiName: "total", type: "money", currency: "EUR", formula: "lines.value" },
        { apiName: "customer", type: "lookup", target: "Customer" },
        { apiName: "memo", type: "longText" },
        { apiName: "createdAt", type: "datetime" },
        { apiName: "tags", type: "json" },
    ],
    relationships: [{ apiName: "lines", type: "child", target: "OrderLine", cascadeDelete: true }],
    validations: [],
    hooks: [],
    indexes: [],
};

const inventory: EntityDefinition = {
    apiName: "Item",
    label: "Inventory Item",
    displayField: "sku",
    fields: [
        { apiName: "sku", type: "text", required: true, uniqueness: true },
        { apiName: "qtyOnHand", type: "decimal", readonly: true, rollup: "SUM(lines.qty)" },
    ],
    relationships: [],
    validations: [],
    hooks: [],
    indexes: [],
};

const clerkPermissions: PermissionSet = {
    roles: [
        { name: "arClerk", description: "AR clerk" },
        { name: "controller", description: "Controller" },
    ],
    objectPermissions: [
        { role: "arClerk", entity: "Order", create: true, read: true, update: true, delete: false },
        { role: "arClerk", entity: "Customer", create: true, read: true, update: true },
        { role: "controller", entity: "Order", create: true, read: true, update: true, delete: true },
        { role: "controller", entity: "Item", read: true },
    ],
    fieldSecurity: [
        { role: "arClerk", entity: "Order", field: "total", access: "hidden" },
        { role: "arClerk", entity: "Order", field: "memo", access: "readonly" },
    ],
};

describe("resolveDefaultPage goldens (L1, §5)", () => {
    it("form default: field-type widget mapping + roll-up/formula readonly + related lists", () => {
        const form = resolveDefaultPage(order, "form");
        expect(form).toMatchSnapshot();
        const keys = form.model.root.children!.map((child) => child.key);
        expect(keys).toEqual([
            "field:reference",
            "field:status",
            "field:total",
            "field:customer",
            "field:memo",
            "field:createdAt",
            "field:tags",
            "related:lines",
        ]);
        const total = form.model.root.children!.find((child) => child.key === "field:total")!;
        expect(total.type).toBe("novaforge.field-number");
        expect(total.readonly).toBe("true"); // formula fields are server-owned
        const tags = form.model.root.children!.find((child) => child.key === "field:tags")!;
        expect(tags.type).toBe("novaforge.field-json");
        const related = form.model.root.children!.find((child) => child.key === "related:lines")!;
        expect(related.type).toBe("novaforge.related-list");
    });

    it("list default: display field + next 4 visible fields, role-shaped actions", () => {
        const list = resolveDefaultPage(order, "list");
        expect(list.model.root.props.columns).toEqual(["reference", "status", "total", "customer", "memo"]);
        // no role → no object permissions → no actions column, no create action
        expect(list.model.root.children!.some((child) => child.key === "actions")).toBe(false);
        expect(list.model.actions).toEqual([]);
        const asClerk = resolveDefaultPage(order, "list", {
            role: "arClerk",
            permissions: clerkPermissions,
        });
        expect(asClerk.model.root.children!.some((child) => child.key === "actions")).toBe(true);
        expect((asClerk.model.root.children!.find((child) => child.key === "actions")!.props as Record<string, unknown>)).toEqual({
            showEdit: true,
            showDelete: false,
        });
        // hidden fields drop from the clerk's columns (role re-resolution)
        expect(asClerk.model.root.props.columns).not.toContain("total");
    });

    it("detail default: sections grouped by field group, everything readonly", () => {
        const detail = resolveDefaultPage(customer, "detail");
        expect(detail).toMatchSnapshot();
        const sections = detail.model.root.children!;
        expect(sections[0]!.type).toBe("novaforge.form-layout");
        expect(sections[0]!.props.section).toBeNull();
        const details = sections.find((section) => section.key === "section:Details");
        expect(details?.children?.map((child) => child.key)).toEqual(["field:since"]);
        // every field readonly on detail
        for (const section of sections) {
            for (const child of section.children ?? []) {
                expect(child.readonly).toBe("true");
            }
        }
    });

    it("field security shapes forms: hidden fields omit, readonly locks", () => {
        const asClerk = resolveDefaultPage(order, "form", {
            role: "arClerk",
            permissions: clerkPermissions,
        });
        const keys = asClerk.model.root.children!.map((child) => child.key);
        expect(keys).not.toContain("field:total");
        expect(asClerk.model.root.children!.find((child) => child.key === "field:memo")!.readonly).toBe("true");
    });

    it("nav groups by module, default group for module-less entities, read-gated", () => {
        const app: AppDefinition = {
            apiName: "erp",
            entities: [customer, order, inventory],
            pages: [],
            permissionSet: clerkPermissions,
            stateMachines: [],
            reports: [],
            dashboards: [],
            translations: [],
        };
        const nav = resolveNav(app, { role: "arClerk" });
        expect(nav.map((group) => group.label)).toEqual(["Sales"]); // Item: no read grant
        expect(nav[0]!.entities.map((entity) => entity.apiName)).toEqual(["Customer", "Order"]);
        const full = resolveNav(app, { role: "controller" });
        // controller reads Orders but not Customers → Sales keeps Order; Item lands in the default group
        expect(full.map((group) => group.label).sort()).toEqual(["App", "Sales"]);
    });
});
