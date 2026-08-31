import { afterEach, describe, expect, it, vi } from "vitest";
import { sessionManager, type OidcSession } from "../src/auth.ts";

/**
 * The single-flight session manager (2026-08-31, thirteenth pass): refreshes used
 * to run only at page load — an SPA in use past the access token's lifetime 401'd
 * every call. The manager refreshes on the margin, and N concurrent callers must
 * share ONE refresh grant (a rotating refresh token replayed N times would itself
 * invalidate the session).
 */

function session(expiresAt: number, refreshToken = "r1"): OidcSession {
    return { accessToken: "a1", claims: {}, expiresAt, refreshToken };
}

afterEach(() => {
    vi.restoreAllMocks();
    sessionStorage.clear();
});

describe("sessionManager", () => {
    it("returns the live token without touching the grant inside the margin", async () => {
        const grant = vi.fn();
        const manager = sessionManager({ issuer: "http://idp" }, session(Date.now() + 60_000));
        // grant unused; token() is pure while the token lives
        expect(grant).not.toHaveBeenCalled();
        await expect(manager.token()).resolves.toBe("a1");
    });

    it("single-flights one refresh for concurrent expired callers", async () => {
        const grant = vi.fn(async () => new Response(JSON.stringify({
            access_token: "a2",
            refresh_token: "r2",
            expires_in: 300,
        }), { status: 200 }));
        vi.stubGlobal("fetch", grant);
        const manager = sessionManager(
            { issuer: "http://idp" },
            session(Date.now() - 1_000),
        );
        const tokens = await Promise.all([
            manager.token(),
            manager.token(),
            manager.token(),
            manager.token(),
        ]);
        expect(grant).toHaveBeenCalledTimes(1);      // one grant, four callers
        expect(new Set(tokens)).toEqual(new Set(["a2"]));
        expect(manager.current()?.refreshToken).toBe("r2");
    });

    it("the 401 hook shares the same single flight and reports unrecoverable as null", async () => {
        let fail = false;
        const grant = vi.fn(async () => {
            if (fail) {
                return new Response("{}", { status: 400 });
            }
            return new Response(JSON.stringify({
                access_token: "a3",
                refresh_token: "r3",
                expires_in: 300,
            }), { status: 200 });
        });
        vi.stubGlobal("fetch", grant);
        const manager = sessionManager(
            { issuer: "http://idp" },
            session(Date.now() - 1_000),
        );
        const [viaToken, viaHook] = await Promise.all([manager.token(), manager.refreshOnUnauthorized()]);
        expect(grant).toHaveBeenCalledTimes(1);
        expect(viaToken).toBe("a3");
        expect(viaHook).toBe("a3");

        // the next refresh is unrecoverable: the session clears, the hook reports null
        fail = true;
        const expired = sessionManager(
            { issuer: "http://idp" },
            session(Date.now() - 1_000, "r-dead"),
        );
        await expect(expired.refreshOnUnauthorized()).resolves.toBeNull();
        expect(expired.current()).toBeNull();
        expect(sessionStorage.getItem("novaforge.session")).toBeNull();
    });
});
