import { describe, expect, it } from "vitest";
import { CATALOG } from "../src/catalog/schemas.ts";
import { catalogEntry, resolveComponent } from "../src/registry.ts";

/**
 * The catalog's versioning contract (ADR-009): ids resolve, versions pin (a page
 * against an unserved version rejects loudly — never a silent newer contract), and
 * unknown ids are errors, not fallbacks.
 */
describe("catalog registry", () => {
  it("carries exactly the catalog vocabulary (Phase 5 widgets + Phase 6 upload), each with a props schema", () => {
    expect(CATALOG.map((entry) => entry.id)).toEqual([
      "novaforge.chart-widget",
      "novaforge.kpi-tile",
      "novaforge.report-table",
      "novaforge.dashboard-grid",
      "novaforge.file-upload",
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
