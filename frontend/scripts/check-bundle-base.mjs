/**
 * The bundle-base gate (the twenty-eighth pass): the SPAs deploy same-origin
 * BEHIND the gateway — /builder/** and /runtime/** (PHASE-2 §13 Q5) — so every
 * asset URL in a built shell must carry its own prefix. Vite's default
 * `base: "/"` emits bare `/assets/...` URLs, and `/assets/**` is no gateway
 * route: the SPA shell loads and then every module request 401s — a blank
 * page, found live when a plain `pnpm -r build` bundle was installed into the
 * gateway's static tree. The build scripts pin the prefix (--base=/builder/,
 * /runtime/); this gate fails the package when a shell ships without it.
 *
 * The twenty-ninth pass closed the gate's own escape hatches: a RELATIVE base
 * (`base: "./"` — the "obvious" one-line fix someone reaches for) emits
 * `./assets/...` URLs, which the original prefix test never matched — zero
 * matches read as success. The contract is now positive, not just reductive:
 * the shell must carry a module script, that script must be an asset URL, and
 * EVERY asset URL (root-relative, relative, or bare) must start with the
 * hosting prefix. A shell that boots no module, or references its bundle in
 * any unprefixed form, fails the package.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";

let failed = false;
for (const ui of ["builder-ui", "runtime-ui"]) {
    const prefix = `/${ui === "builder-ui" ? "builder" : "runtime"}/`;
    const shell = readFileSync(join(ui, "dist", "index.html"), "utf8");

    // the boot contract itself: a production SPA shell references its entry as
    // a module script — anything else (an empty or hand-edited shell) cannot
    // boot behind the gateway regardless of what the URLs look like
    const moduleScripts = [...shell.matchAll(/<script[^>]*type="module"[^>]*>/g)];
    if (moduleScripts.length === 0) {
        console.error(`${ui}: shell carries no type="module" script — it cannot boot (was the build skipped or the shell hand-edited?)`);
        failed = true;
        continue;
    }

    // every src/href in the shell, external URLs excepted (cdn links, data:,
    // in-page anchors): an EXTERNAL url is not ours to prefix
    const externalUrl = /^(?:[a-z][a-z0-9+.-]*:|\/\/|#)/i;
    const urls = [...shell.matchAll(/\b(?:src|href)="([^"]+)"/g)].map((m) => m[1]);
    const assets = urls.filter((u) => !externalUrl.test(u));
    if (assets.length === 0) {
        console.error(`${ui}: shell references no asset URLs at all — the build emitted no bundle references (vite config regression?)`);
        failed = true;
        continue;
    }
    for (const url of assets) {
        if (!url.startsWith(prefix)) {
            console.error(`${ui}: asset URL escapes its hosting prefix: ${url} (expected ${prefix}…)`);
            failed = true;
        }
    }
    const bad = assets.filter((u) => !u.startsWith(prefix)).length;
    if (bad === 0) {
        console.log(`${ui}: ${assets.length} asset URLs carry ${prefix} (vite --base), boot module present`);
    }
}
if (failed) {
    console.error("bundle-base gate FAILED — build with the --base prefix (see IMPLEMENTATION.md Phase 2 hosting)");
    process.exit(1);
}
