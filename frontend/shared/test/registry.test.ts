import { describe, expect, it } from "vitest";
import { CATALOG } from "../src/catalog/schemas.ts";
import { catalogEntry, resolveComponent } from "../src/registry.ts";

/**
 * The catalog's versioning contract (ADR-009): ids resolve, versions pin (a page
 * against an unserved version rejects loudly — never a silent newer contract), and
 * unknown ids are errors, not fallbacks.
 */
describe("catalog registry", () => {
  it("carries exactly the catalog vocabulary (PHASE-2 §6's 18 page components + the Phase 5/6 additions), each with a props schema", () => {
    expect(CATALOG.map((entry) => entry.id)).toEqual([
      // Phase 5 T5 widgets + Phase 6 upload
      "novaforge.chart-widget",
      "novaforge.kpi-tile",
      "novaforge.report-table",
      "novaforge.dashboard-grid",
      "novaforge.file-upload",
      // PHASE-2 §6 item 3 — the v1 page catalog
      "novaforge.app-shell",
      "novaforge.nav-list",
      "novaforge.form-layout",
      "novaforge.list-layout",
      "novaforge.record-header",
      "novaforge.field-input",
      "novaforge.field-number",
      "novaforge.field-select",
      "novaforge.field-switch",
      "novaforge.field-date",
      "novaforge.field-lookup",
      "novaforge.field-multi-lookup",
      "novaforge.field-rich-text",
      "novaforge.field-json",
      "novaforge.related-list",
      "novaforge.record-actions",
      "novaforge.empty-state",
    ]);
    for (const entry of CATALOG) {
      expect(entry.version).toBe("1.0.0");
      expect((entry.schema as { $schema?: string }).$schema).toContain("2020-12");
    }
  });

  it("resolves the pinned version lazily", () => {
    const component = resolveComponent("novaforge.kpi-tile", "1.0.0");
    expect(component).toBeDefined();
    expect(catalogEntry("novaforge.kpi-tile").schema).toBeDefined();
  });

  it("rejects a page pinned to an unserved version", () => {
    expect(() => resolveComponent("novaforge.kpi-tile", "2.0.0")).toThrow(
      /version mismatch/,
    );
  });

  it("rejects unknown ids", () => {
    expect(() => catalogEntry("novaforge.ghost")).toThrow(/unknown catalog component/);
  });
});
