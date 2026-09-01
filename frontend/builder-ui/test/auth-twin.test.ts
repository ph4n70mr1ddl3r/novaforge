import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

/**
 * The OIDC client's twin canary (re-audit): `builder-ui/src/auth.ts` is a
 * byte-identical copy of the runtime client (`runtime-ui/src/auth.ts`) — the
 * PKCE flow, sessionStorage session, expiry margin, and single-flight refresh
 * shipped twice because the shared package cannot host a browser-session module
 * without a bundler decision. Only the runtime copy has behavioral tests; this
 * canary pins the OTHER guarantee that matters: the copies do not silently
 * drift. If this test fails, either port the change to BOTH files (token
 * handling diverging between the two shells is a security-adjacent split) or,
 * if the divergence is deliberate, move the module into `shared` and delete the
 * twin plus this canary consciously.
 */

import { resolve } from "node:path";

/** The twin paths, resolved from whichever cwd vitest picked. */
function twin(path: string): string {
    for (const root of [process.cwd(), resolve(process.cwd(), ".."), resolve(process.cwd(), "../..")]) {
        try {
            return readFileSync(resolve(root, path), "utf8");
        } catch {
            continue;
        }
    }
    throw new Error("auth twin not found from cwd " + process.cwd());
}

const runtimeAuth = twin("runtime-ui/src/auth.ts");
const builderAuth = twin("builder-ui/src/auth.ts");

describe("the auth client twins", () => {
    it("builder and runtime OIDC clients stay byte-identical — port changes to both", () => {
        expect(builderAuth).toBe(runtimeAuth);
    });
});
