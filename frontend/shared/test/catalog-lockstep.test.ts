import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { CATALOG } from "../src/catalog/schemas.ts";

// @vitest-environment node
// (the manifest read is plain fs — jsdom's URL global rewrites the
// `new URL(<literal>, import.meta.url)` asset pattern to an http URL)

/**
 * Catalog lockstep (PHASE-2 §4/§6): the server-side page gate validates pages
 * against the canonical manifest
 * (`services/metadata-service/src/main/resources/catalog/component-catalog.json`)
 * — the same id/version/props-schema contract this module's `CATALOG` serves the
 * builder and renderer. Neither side may drift: an entry that exists on one side
 * only, a version bumped on one side only, or a schema changed on one side only
 * would make the builder's save/publish gate disagree with the API path's gate.
 * This suite pins the two copies entry-for-entry, deep-equal, in the same order
 * — the expr/v1 conformance-corpus pattern applied to the catalog manifest.
 */

const MANIFEST_URL = new URL(
    "../../../services/metadata-service/src/main/resources/catalog/component-catalog.json",
    import.meta.url,
);

interface ManifestEntry {
    id: string;
    version: string;
    status?: "draft" | "stable" | "deprecated";
    deprecation?: { reason: string; migrateTo?: string };
    schema: Record<string, unknown>;
}

const manifest: ManifestEntry[] = JSON.parse(readFileSync(fileURLToPath(MANIFEST_URL), "utf8"));

function byId(entries: readonly ManifestEntry[]): Map<string, ManifestEntry> {
    return new Map(entries.map((entry) => [entry.id, entry]));
}

describe("catalog lockstep with the server-side manifest", () => {
    it("serves exactly the manifest's ids, in the same order", () => {
        expect(CATALOG.map((entry) => entry.id)).toEqual(manifest.map((entry) => entry.id));
    });

    it("matches the manifest entry-for-entry (version, lifecycle, schema)", () => {
        const server = byId(manifest);
        for (const entry of CATALOG) {
            const twin = server.get(entry.id);
            expect(twin, `manifest entry for ${entry.id}`).toBeDefined();
            expect(entry.version, `${entry.id} version`).toEqual(twin!.version);
            expect(entry.status, `${entry.id} lifecycle status`).toEqual(twin!.status);
            expect(entry.deprecation, `${entry.id} deprecation guidance`).toEqual(twin!.deprecation);
            expect(entry.schema, `${entry.id} props schema`).toEqual(twin!.schema);
        }
    });

    it("carries no entry the manifest lacks", () => {
        const builder = new Set(CATALOG.map((entry) => entry.id));
        for (const entry of manifest) {
            expect(builder.has(entry.id), `builder entry for ${entry.id}`).toBe(true);
        }
    });
});
