/**
 * The bundle-base gate (the twenty-eighth pass): the SPAs deploy same-origin
 * BEHIND the gateway — /builder/** and /runtime/** (PHASE-2 §13 Q5) — so every
 * asset URL in a built shell must carry its own prefix. Vite's default
 * `base: "/"` emits bare `/assets/...` URLs, and `/assets/**` is no gateway
 * route: the SPA shell loads and then every module request 401s — a blank
 * page, found live when a plain `pnpm -r build` bundle was installed into the
 * gateway's static tree. The build scripts pin the prefix (--base=/builder/,
 * /runtime/); this gate fails the package when a shell ships without it.
 */
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

let failed = false;
for (const ui of ["builder-ui", "runtime-ui"]) {
    const prefix = `/${ui === "builder-ui" ? "builder" : "runtime"}/`;
    const shell = readFileSync(join(ui, "dist", "index.html"), "utf8");
    for (const match of shell.matchAll(/(?:src|href)="(\/[^"]+)"/g)) {
        const url = match[1];
        if (!url.startsWith(prefix)) {
            console.error(`${ui}: asset URL escapes its hosting prefix: ${url} (expected ${prefix}…)`);
            failed = true;
        }
    }
    console.log(`${ui}: asset URLs carry ${prefix} (vite --base)`);
}
if (failed) {
    console.error("bundle-base gate FAILED — build with the --base prefix (see IMPLEMENTATION.md Phase 2 hosting)");
    process.exit(1);
}
