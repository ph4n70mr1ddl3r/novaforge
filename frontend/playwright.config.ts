import { defineConfig } from "@playwright/test";

/**
 * The E2E layer (PHASE-2 §11 items 2–3, the browser-runner leg the ledger owed):
 * journeys drive the real SPAs — same-origin behind the gateway, real PKCE OIDC
 * against the deployed Keycloak realm, real Metadata/Data-Runtime APIs.
 *
 * Prerequisites: the live stack (compose infra + the eleven services; the gateway
 * serving the built bundles — `pnpm package` + the gateway jar's static tree, see
 * IMPLEMENTATION.md Phase 2's same-origin hosting note).
 *
 * Run: cd frontend && pnpm exec playwright test
 */
export default defineConfig({
    testDir: "./e2e",
    timeout: 180_000,
    expect: { timeout: 30_000 },
    fullyParallel: false,
    workers: 1,
    retries: 0,
    reporter: [["list"]],
    use: {
        baseURL: "http://localhost:8080",
        headless: true,
        trace: "retain-on-failure",
        screenshot: "only-on-failure",
    },
});
