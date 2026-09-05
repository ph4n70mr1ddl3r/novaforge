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

    it("serializes composites to the server's canonical wire shape (the op as the node key)", async () => {
        // Anti-regression: the TS type names `op` + `children`, but the JVM
        // QueryParser reads `{"and": […]}` (the shape ReportCompiler lowers to).
        // Sent verbatim, every drill-through deep link — the one composite
        // producer — 400'd as VALIDATION_FAILED before its list could render.
        const { client, fetchImpl } = stubClient({
            "GET /runtime/Order": { status: 200, body: { rows: [], total: 0 } },
        });
        await client.list({
            entity: "Order",
            filter: {
                op: "and",
                children: [
                    { field: "status", op: "eq", value: "POSTED" },
                    { field: "reference", op: "contains", value: "SO" },
                ],
            },
            size: 50,
            offset: 0,
        });
        const url = String((fetchImpl.mock.calls[0] as unknown[])[0]);
        const params = new URLSearchParams(url.slice(url.indexOf("?") + 1));
        expect(JSON.parse(params.get("filter")!)).toEqual({
            and: [
                { field: "status", op: "eq", value: "POSTED" },
                { field: "reference", op: "contains", value: "SO" },
            ],
        });

        // `or` lowers the same way, and nesting survives
        await client.list({
            entity: "Order",
            filter: {
                op: "or",
                children: [
                    { field: "status", op: "eq", value: "DRAFT" },
                    { op: "and", children: [{ field: "total", op: "gt", value: 100 }] },
                ],
            },
            size: 50,
            offset: 0,
        });
        const url2 = String((fetchImpl.mock.calls[1] as unknown[])[0]);
        const params2 = new URLSearchParams(url2.slice(url2.indexOf("?") + 1));
        expect(JSON.parse(params2.get("filter")!)).toEqual({
            or: [
                { field: "status", op: "eq", value: "DRAFT" },
                { and: [{ field: "total", op: "gt", value: 100 }] },
            ],
        });
    });

    it("recovers a 401 with one refresh and one retry — and surfaces when refresh fails", async () => {
        // Anti-regression (2026-08-31, thirteenth pass): the client had no notion of
        // token expiry — an SPA in use past the access token's lifetime 401'd every
        // call until a manual reload. The refresh hook retries exactly once.
        let calls = 0;
        const fetchImpl = vi.fn(async (input: string | URL, init?: RequestInit) => {
            calls += 1;
            const bearer = (init?.headers as Record<string, string>)["Authorization"];
            const ok = bearer === "Bearer fresh";
            return new Response(JSON.stringify(ok ? { rows: [], total: 0 } : { title: "Unauthorized", status: 401 }), {
                status: ok ? 200 : 401,
                headers: { "Content-Type": "application/json" },
            });
        });
        const refresh = vi.fn(async () => "fresh");
        const client = new PlatformClient(
            "http://gateway",
            () => "stale",
            fetchImpl as unknown as typeof fetch,
            refresh,
        );
        const result = await client.list({ entity: "Order", size: 10, offset: 0 });
        expect(result.total).toBe(0);
        expect(calls).toBe(2);           // 401 then the retried request
        expect(refresh).toHaveBeenCalledTimes(1);

        // refresh gives up → the 401 surfaces to the caller
        const giveUp = new PlatformClient(
            "http://gateway",
            () => "stale",
            (async () => new Response(JSON.stringify({ title: "Unauthorized", status: 401 }), {
                status: 401,
                headers: { "Content-Type": "application/json" },
            })) as unknown as typeof fetch,
            async () => null,
        );
        await expect(giveUp.list({ entity: "Order", size: 10, offset: 0 })).rejects.toMatchObject({
            status: 401,
        });
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

    it("runs a named flow hook on demand — the runFlow action's transport (PHASE-3 §8)", async () => {
        const { client, fetchImpl } = stubClient({
            "POST /hooks": { status: 200, body: { id: "r-1", status: "DRAFT" } },
        });
        const fresh = await client.runHook("Order", "r-1", "stampCredit");
        const [url, init] = fetchImpl.mock.calls[0] as [string, RequestInit];
        expect(url).toContain("/api/v1/runtime/Order/r-1/hooks/stampCredit");
        expect(init.method).toBe("POST");
        expect(fresh).toMatchObject({ id: "r-1" });
    });

    it("claims and delegates inbox tasks through the §5 operations", async () => {
        const { client, fetchImpl } = stubClient({
            "POST /claim": { status: 200, body: { id: "t-1", status: "OPEN" } },
            "POST /delegate": { status: 200, body: { id: "t-2", status: "OPEN" } },
        });
        await client.claimTask("t-1");
        await client.delegateTask("t-1", "u-9");
        const claim = fetchImpl.mock.calls[0] as [string, RequestInit];
        expect(String(claim[0])).toContain("/api/v1/workflow/tasks/t-1/claim");
        const delegate = fetchImpl.mock.calls[1] as [string, RequestInit];
        expect(String(delegate[0])).toContain("/api/v1/workflow/tasks/t-1/delegate");
        expect(JSON.parse(String(delegate[1].body))).toEqual({ toUser: "u-9" });
    });

    it("lists and installs templates through the §6 catalog", async () => {
        const { client, fetchImpl } = stubClient({
            "GET /templates": { status: 200, body: [{ id: "tpl-1", name: "ERP" }] },
            "POST /install": { status: 200, body: { apiName: "erp2" } },
        });
        const templates = await client.templates();
        expect(templates).toHaveLength(1);
        const installed = await client.installTemplate("tpl-1", "erp2");
        expect(installed).toMatchObject({ apiName: "erp2" });
        const [url, init] = fetchImpl.mock.calls[1] as [string, RequestInit];
        expect(url).toContain("/api/v1/metadata/templates/tpl-1/install");
        expect(JSON.parse(String(init.body))).toEqual({ apiName: "erp2" });
    });
});
