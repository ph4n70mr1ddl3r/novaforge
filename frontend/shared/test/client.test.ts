import { describe, expect, it, vi } from "vitest";
import { ApiError, PlatformClient } from "../src/client/platform.ts";

/** The gateway client: canonical query-DSL encoding, problem+json surfacing. */

function stubClient(responses: Record<string, { status: number; body: unknown }>) {
    const fetchImpl = vi.fn(async (input: string | URL, init?: RequestInit) => {
        const url = String(input);
        const method = (init?.method ?? "GET").toUpperCase();
        const key = Object.keys(responses).find(
            (candidate) => url.includes(candidate.split(" ")[1]!) && candidate.startsWith(method + " "),
        );
        const stub = responses[key ?? ""] ?? { status: 404, body: { title: "not stubbed", status: 404 } };
        return new Response(JSON.stringify(stub.body), {
            status: stub.status,
            headers: { "Content-Type": "application/json" },
        });
    });
    return { client: new PlatformClient("http://gateway", () => "tok", fetchImpl as unknown as typeof fetch), fetchImpl };
}

describe("PlatformClient", () => {
    it("encodes the query DSL as the one canonical GET form (filter/sort/page as JSON)", async () => {
        const { client, fetchImpl } = stubClient({
            "GET /runtime/Order": { status: 200, body: { rows: [], total: 0 } },
        });
        await client.list({
            entity: "Order",
            filter: { op: "eq", field: "status", value: "POSTED" },
            sort: [{ field: "reference", dir: "asc" }],
            size: 50,
            offset: 100,
        });
        const url = String((fetchImpl.mock.calls[0] as unknown[])[0]);
        expect(url).toContain("/api/v1/runtime/Order?");
        const params = new URLSearchParams(url.slice(url.indexOf("?") + 1));
        expect(JSON.parse(params.get("filter")!)).toEqual({ op: "eq", field: "status", value: "POSTED" });
        expect(JSON.parse(params.get("sort")!)).toEqual([{ field: "reference", dir: "asc" }]);
        expect(JSON.parse(params.get("page")!)).toEqual({ size: 50, offset: 100 });
    });

    it("surfaces problem+json errors with field detail", async () => {
        const { client } = stubClient({
            "POST /runtime/JournalEntry": {
                status: 422,
                body: {
                    type: "https://novaforge.dev/problems/validation",
                    title: "Validation failed",
                    status: 422,
                    code: "VALIDATION_FAILED",
                    errors: [{ field: "entryDate", message: "period is CLOSED" }],
                },
            },
        });
        const error = await client.createRecord("JournalEntry", {}).catch((caught: unknown) => caught);
        expect(error).toBeInstanceOf(ApiError);
        expect((error as ApiError).code).toBe("VALIDATION_FAILED");
        expect((error as ApiError).fieldErrors()).toEqual({ entryDate: "period is CLOSED" });
    });

    it("rides the bearer token and idempotency key on creates", async () => {
        const { client, fetchImpl } = stubClient({
            "POST /runtime/Order": { status: 200, body: { id: "x" } },
        });
        await client.createRecord("Order", { reference: "SO-1" }, "idem-1");
        const init = (fetchImpl.mock.calls[0] as [string, RequestInit])[1]!;
        expect(init.headers).toMatchObject({
            Authorization: "Bearer tok",
            "Idempotency-Key": "idem-1",
        });
    });

    it("puts pages through the versioned metadata path (PHASE-2 §8)", async () => {
        const { client, fetchImpl } = stubClient({
            "PUT /pages": { status: 200, body: { apiName: "Order_form" } },
        });
        await client.putPage("app-1", { apiName: "Order_form", type: "form", entity: "Order", layout: {} });
        const [url, init] = fetchImpl.mock.calls[0] as [string, RequestInit];
        expect(url).toContain("/api/v1/metadata/apps/app-1/pages/Order_form");
        expect(init.method).toBe("PUT");
    });
});
