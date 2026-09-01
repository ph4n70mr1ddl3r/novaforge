import { describe, expect, it, vi } from "vitest";
import { ApiError, PlatformClient } from "../src/client/platform.ts";

/**
 * The gateway's failure bodies are not always problem+json (re-audit): a 502/503
 * from a proxy is an HTML page, an empty hop is an empty body — the defensive
 * parse must synthesize the Problem contract so every error surface renders the
 * actual status. A regression turns every gateway error into a SyntaxError no
 * surface can render.
 */

function stubClient(body: (url: string) => Response) {
    const fetchImpl = vi.fn(async (input: string | URL) => body(String(input)));
    return new PlatformClient("http://gateway", () => "tok", fetchImpl as unknown as typeof fetch);
}

describe("PlatformClient — non-JSON error bodies keep the problem contract", () => {
    it("a gateway HTML 502 answers ApiError(502) with the body snippet as detail", async () => {
        const client = stubClient(() => new Response("<html>Bad Gateway</html>", {
            status: 502,
            headers: { "content-type": "text/html" },
        }));
        const error = await client.listApps().catch((e: unknown) => e);
        expect(error).toBeInstanceOf(ApiError);
        const apiError = error as ApiError;
        expect(apiError.status).toBe(502);
        expect(apiError.problem.title).toContain("502");
        expect(apiError.problem.detail).toContain("Bad Gateway");
    });

    it("an empty proxy body answers ApiError with no fabricated detail", async () => {
        const client = stubClient(() => new Response("", { status: 503 }));
        const error = (await client.listApps().catch((e: unknown) => e)) as ApiError;
        expect(error.status).toBe(503);
        expect(error.problem.title).toContain("503");
        expect(error.problem.detail).toBeUndefined();
    });

    it("a real problem+json body still wins over the synthesized fallback", async () => {
        const client = stubClient(() => new Response(
            JSON.stringify({ title: "Unauthorized", status: 401, detail: "token expired" }),
            { status: 401, headers: { "content-type": "application/problem+json" } }));
        const error = (await client.listApps().catch((e: unknown) => e)) as ApiError;
        expect(error.status).toBe(401);
        expect(error.problem.title).toBe("Unauthorized");
        expect(error.problem.detail).toBe("token expired");
    });
});
