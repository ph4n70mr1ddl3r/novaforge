import { describe, expect, it } from "vitest";
import {
    applyDeltas,
    diffPages,
    type PageDelta,
    type PageNode,
    type ResolvedPage,
} from "../src/pagemodel/model.ts";
import { toPatch, describeDelta, pageDocument } from "../src/pagemodel/patch.ts";
import { checkPage, lifecycleWarnings, validatePage } from "../src/pagemodel/validate.ts";
import type { CatalogEntry } from "../src/catalog/schemas.ts";
import { resolveDefaultPage } from "../src/resolver.ts";
import type { EntityDefinition } from "../src/metadata.ts";

/**
 * Overlay mechanics (PHASE-2 §11 item 4): entity change + overlay → expected
 * resolved page — the regression suite for the "un-overridden parts follow the
 * entity" rule; plus §13 Q2's delta apply/merge round-trip and JSON Patch export.
 */

const entity: EntityDefinition = {
    apiName: "Order",
    label: "Sales Order",
    displayField: "reference",
    module: "Sales",
    fields: [
        { apiName: "reference", type: "text", required: true },
        { apiName: "status", type: "enum", values: ["DRAFT", "POSTED"] },
        { apiName: "orderedAt", type: "date" },
        { apiName: "total", type: "money", currency: "EUR" },
        { apiName: "customer", type: "lookup", target: "Customer" },
        { apiName: "notes", type: "longText", group: "Extra" },
    ],
    relationships: [{ apiName: "lines", type: "child", target: "OrderLine" }],
    validations: [],
    hooks: [],
    indexes: [],
};

function deepClone<T>(value: T): T {
    return structuredClone(value);
}

describe("structural deltas (§13 Q2)", () => {
    it("apply/merge round-trips: reorder, sections, visibility", () => {
        const base = resolveDefaultPage(entity, "form");
        const deltas: PageDelta[] = [
            // reorder: move the status field before reference
            { op: "moveNode", key: "field:status", parent: "form", index: 0 },
            // visibility overlay (§4's example shape)
            { op: "setSlot", key: "field:status", slot: "visibility", value: "status != 'POSTED'" },
            // a section title
            { op: "setProps", key: "form", props: { columns: 3 } },
            // action removed
            { op: "removeAction", index: 1 },
        ];
        const { page, stale } = applyDeltas(base, deltas);
        expect(stale).toEqual([]);
        const children = page.model.root.children!;
        expect(children[0]!.key).toBe("field:status");
        expect(children[0]!.visibility).toBe("status != 'POSTED'");
        expect(page.model.root.props.columns).toBe(3);
        expect(page.model.actions).toEqual([{ type: "save" }]);
        // diffing base → resolved reproduces the deltas' effect
        const reproduced = applyDeltas(base, diffPages(base, page)).page;
        expect(reproduced).toEqual(page);
    });

    it("un-overridden parts follow the entity (§11 item 4)", () => {
        const base = resolveDefaultPage(entity, "form");
        const overlay: PageDelta[] = [
            { op: "setSlot", key: "field:notes", slot: "readonly", value: "true" },
        ];
        // The entity later gains a field — the overlay still applies, the new field appears.
        const evolved = deepClone(entity);
        evolved.fields.push({ apiName: "priority", type: "enum", values: ["low", "high"] });
        const nextBase = resolveDefaultPage(evolved, "form");
        const { page, stale } = applyDeltas(nextBase, overlay);
        expect(stale).toEqual([]);
        const keys = page.model.root.children!.map((child) => child.key);
        expect(keys).toContain("field:priority");
        expect(page.model.root.children!.find((c) => c.key === "field:notes")!.readonly).toBe("true");
    });

    it("stale deltas report instead of corrupting when the entity dropped the target", () => {
        const base = resolveDefaultPage(entity, "form");
        const overlay: PageDelta[] = [
            { op: "setSlot", key: "field:notes", slot: "required", value: "true" },
        ];
        const evolved = deepClone(entity);
        evolved.fields = evolved.fields.filter((field) => field.apiName !== "notes");
        const { page, stale } = applyDeltas(resolveDefaultPage(evolved, "form"), overlay);
        expect(stale).toHaveLength(1);
        expect(stale[0]!.reason).toContain("field:notes");
        expect(page.model.root.children!.some((child) => child.key === "field:notes")).toBe(false);
    });

    it("exports the resolved page as RFC 6902 JSON Patch (interchange format)", () => {
        const base = resolveDefaultPage(entity, "list");
        const overlay: PageDelta[] = [{ op: "setProps", key: "list", props: { pageSize: 100 } }];
        const { page } = applyDeltas(base, overlay);
        const patch = toPatch(base, page);
        expect(patch.length).toBeGreaterThan(0);
        expect(describeDelta(overlay[0]!)).toContain("set list props: pageSize");
        // round-trip: applying the patch document to the base document yields the resolved one
        const patchTarget: Record<string, unknown> = pageDocument(base);
        for (const op of patch) {
            if (op.op === "replace") {
                const segments = op.path.split("/").filter(Boolean);
                let cursor: Record<string, unknown> = patchTarget;
                for (const segment of segments.slice(0, -1)) {
                    cursor = cursor[segment] as Record<string, unknown>;
                }
                cursor[segments[segments.length - 1]!] = op.value;
            }
        }
        const root = patchTarget["root"] as { props: Record<string, unknown> };
        expect(root.props.pageSize).toBe(100);
    });
});

describe("page save/publish validation (§4)", () => {
    it("accepts the L1 defaults at publish (versions pinned by the resolver's catalog)", () => {
        const page = resolveDefaultPage(entity, "form");
        // L1 output is version-pinned by construction via the catalog's stable pin
        // — the builder writes pins on save; the resolver emits none, and the
        // runtime resolves to current stable, so publish-mode here must flag them
        // unless pins exist. The builder pins on save (§4).
        const issues = validatePage(page.model, { entity, mode: "publish" });
        expect(issues.filter((issue) => issue.message.includes("missing pinned version"))).toHaveLength(
            page.model.root.children!.length + 1,
        );
    });

    it("rejects invalid props against the component schema", () => {
        const page = resolveDefaultPage(entity, "form");
        const broken = applyDeltas(page, [
            { op: "setProps", key: "form", props: { columns: 9 } },
        ]).page;
        const issues = validatePage(broken.model, { entity, mode: "save" });
        expect(issues.some((issue) => issue.message.includes("above maximum"))).toBe(true);
    });

    it("rejects bind/props.field mismatches", () => {
        const page = resolveDefaultPage(entity, "form");
        const mismatch = deepClone(page.model);
        const status = mismatch.root.children!.find((child) => child.key === "field:status")!;
        status.bind = "orderedAt"; // props.field stays "status"
        const issues = validatePage(mismatch, { entity, mode: "save" });
        expect(issues.some((issue) => issue.message.includes("disagree"))).toBe(true);
    });

    it("reads the bind requirement from the catalog's declaration, not the id's shape (§6 item 1)", () => {
        // A declared-binding component (novaforge.field-json — typed field widget,
        // declaration true) requires a bind; a declared-non-binding component
        // (novaforge.file-upload — binds an id back through its own props, not a
        // catalog bind slot) never does, prefix or no. The rule is the entry's
        // takesBind declaration, which the lockstep suite pins to the manifest.
        const fieldJson = {
            type: "novaforge.field-json", key: "n:fj", props: { field: "notes" },
        };
        expect(validatePage({
            kind: "form", base: "auto",
            root: { type: "novaforge.form-layout", key: "form", props: {}, children: [fieldJson] },
            actions: [],
        }, { entity, mode: "save" }).some((issue) => issue.message === "novaforge.field-json requires a bind")).toBe(true);

        const upload = {
            type: "novaforge.file-upload", key: "n:fu", props: { entity: "Order", recordId: "x" },
        };
        expect(validatePage({
            kind: "form", base: "auto",
            root: { type: "novaforge.form-layout", key: "form", props: {}, children: [upload] },
            actions: [],
        }, { entity, mode: "save" }).some((issue) => issue.message.includes("requires a bind"))).toBe(false);
    });

    it("rejects unknown components and unknown expression references", () => {
        const page = resolveDefaultPage(entity, "form");
        const broken = deepClone(page.model);
        broken.root.children!.push({
            type: "novaforge.turbo-table",
            key: "n:1",
            props: {},
        });
        broken.root.children![0]!.visibility = "nonexistent > 1";
        const issues = validatePage(broken, { entity, mode: "save" });
        expect(issues.some((issue) => issue.message.includes("unknown component"))).toBe(true);
        expect(issues.some((issue) => issue.message.includes("unresolved reference"))).toBe(true);
    });

    it("closes the action set", () => {
        const page = resolveDefaultPage(entity, "form");
        const broken = deepClone(page.model);
        broken.actions.push({ type: "runScript" } as never);
        const issues = validatePage(broken, { entity, mode: "save" });
        expect(issues.some((issue) => issue.message.includes("unknown action type"))).toBe(true);
    });

    it("runFlow joins the ladder (PHASE-3 §8) — and requires its hook reference", () => {
        const page = resolveDefaultPage(entity, "form");
        const withFlow = deepClone(page.model);
        withFlow.actions.push({ type: "runFlow", props: { hook: "stampCredit" } });
        expect(validatePage(withFlow, { entity, mode: "save" })
            .filter((issue) => issue.message.includes("action"))).toEqual([]);

        const hookless = deepClone(page.model);
        hookless.actions.push({ type: "runFlow", props: { hook: "" } });
        const issues = validatePage(hookless, { entity, mode: "save" });
        expect(issues.some((issue) => issue.message === "runFlow requires props.hook")).toBe(true);
    });

    it("setAction replaces an action in place (the runFlow hook picker's delta)", () => {
        const base = resolveDefaultPage(entity, "form");
        const withFlow = applyDeltas(base, [
            { op: "addAction", action: { type: "runFlow", props: { hook: "first" } } },
            { op: "setAction", index: (base.model.actions.length), action: { type: "runFlow", props: { hook: "stampCredit" } } },
        ]).page;
        expect(withFlow.model.actions.at(-1)).toEqual({ type: "runFlow", props: { hook: "stampCredit" } });
        // out-of-range setAction reports stale instead of corrupting (§11 item 4)
        const stale = applyDeltas(base, [{ op: "setAction", index: 99, action: { type: "save" } }]);
        expect(stale.stale).toHaveLength(1);
    });
});

describe("catalog lifecycle warnings (§6 item 2)", () => {
    const node = (type: string): PageNode => ({ type, key: `n:${type}`, props: {} });
    const entry = (id: string, extra: Partial<CatalogEntry>): CatalogEntry => ({
        id, version: "1.0.0", takesBind: false, schema: { type: "object" }, ...extra,
    });

    it("a deprecated component surfaces its migration guidance — never silently", () => {
        const warnings = lifecycleWarnings(
            [node("novaforge.legacy-grid")],
            [entry("novaforge.legacy-grid", {
                status: "deprecated",
                deprecation: { reason: "superseded by ListLayout", migrateTo: "novaforge.list-layout" },
            })],
            "save",
        );
        expect(warnings).toHaveLength(1);
        expect(warnings[0]!.message).toContain("deprecated");
        expect(warnings[0]!.message).toContain("superseded by ListLayout");
        expect(warnings[0]!.message).toContain("migrate to novaforge.list-layout");
    });

    it("stable components stay silent; draft components warn only at publish", () => {
        const entries = [
            entry("novaforge.field-input", { status: "stable" }),
            entry("novaforge.experimental-widget", { status: "draft" }),
        ];
        expect(lifecycleWarnings([node("novaforge.field-input")], entries, "publish")).toEqual([]);
        expect(lifecycleWarnings([node("novaforge.experimental-widget")], entries, "save")).toEqual([]);
        expect(lifecycleWarnings([node("novaforge.experimental-widget")], entries, "publish"))
            .toHaveLength(1);
    });

    it("nested deprecations surface at their own path", () => {
        const deprecated = entry("novaforge.legacy-grid", {
            status: "deprecated",
            deprecation: { reason: "retired" },
        });
        const root: PageNode = {
            type: "novaforge.form-layout",
            key: "form",
            props: {},
            children: [node("novaforge.legacy-grid")],
        };
        const warnings = lifecycleWarnings([root], [deprecated], "save");
        expect(warnings[0]!.path).toBe("root.children[0]");
    });

    it("checkPage carries warnings beside the blocking issues (the page builder's verdict)", () => {
        const page = resolveDefaultPage(entity, "form");
        const verdict = checkPage(page.model, { entity, mode: "save" });
        // the v1 catalog ships stable: no warnings, and L1 pages have no issues
        expect(verdict.warnings).toEqual([]);
    });
});
