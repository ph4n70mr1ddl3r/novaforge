import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import type { AppDefinition, PlatformClient } from "@novaforge/shared";
import { Integrations } from "../src/integrations.tsx";

/**
 * The PHASE-6 §3 authoring surface: connectors (operations on a base URL,
        credentials by reference), credential references with store-only secret
 * provisioning (§9 — the material never rides metadata), webhooks both directions,
 * import mappings, and the operational surfaces beside the editors — the delivery
 * log and the replayable DLQ.
 */

const app: AppDefinition = {
    apiName: "Erp",
    entities: [
        {
            apiName: "Payment",
            fields: [{ apiName: "number", type: "text" }],
            relationships: [],
            validations: [],
            hooks: [],
            indexes: [],
        },
    ],
    integrations: {
        connectors: [
            {
                id: "bankFeed",
                type: "rest",
                baseUrl: "https://bank.example.local",
                credential: "bankFeedKey",
                operations: [{ name: "listTransactions", method: "GET", path: "/v1/transactions" }],
            },
        ],
        credentials: [{ id: "bankFeedKey", kind: "api_key", header: "X-Api-Key" }],
        webhooks: [
            {
                id: "paymentsFeed",
                direction: "inbound",
                entity: "Payment",
                mapping: { mode: "upsert", keyFields: ["number"], fields: { number: "${txn_id}" } },
                secretRef: "payments-feed",
                enabled: true,
            },
        ],
        imports: [],
    },
    stateMachines: [],
    permissionSet: { roles: [], objectPermissions: [], fieldSecurity: [] },
    pages: [],
    reports: [],
    dashboards: [],
    translations: [],
};

function stubClient(overrides: Partial<{
    deliveries: Record<string, unknown>[];
    dlq: Record<string, unknown>[];
    jobs: Record<string, unknown>[];
    jobRows: Record<string, unknown>[];
    putSecret: (ref: string, material: string, retireEarlier?: boolean) => Promise<unknown>;
    replay: (id: string) => Promise<unknown>;
}> = {}): {
    client: PlatformClient;
    calls: { secrets: [string, string, boolean?][]; replays: string[]; resumes: string[]; rowsFetched: string[] };
} {
    const calls = {
        secrets: [] as [string, string, boolean?][],
        replays: [] as string[],
        resumes: [] as string[],
        rowsFetched: [] as string[],
    };
    const client = {
        integrationDeliveries: async () => overrides.deliveries ?? [],
        integrationDlq: async () => overrides.dlq ?? [],
        integrationJobs: async () => overrides.jobs ?? [],
        integrationJobRows: async (jobId: string) => {
            calls.rowsFetched.push(jobId);
            return overrides.jobRows ?? [];
        },
        resumeIntegrationJob: async (jobId: string) => {
            calls.resumes.push(jobId);
            return { jobId, status: "pending" };
        },
        putSecret: async (ref: string, material: string, retireEarlier?: boolean) => {
            calls.secrets.push([ref, material, retireEarlier]);
            return overrides.putSecret?.(ref, material, retireEarlier) ?? { ref, status: "provisioned" };
        },
        replayDlqEntry: async (id: string) => {
            calls.replays.push(id);
            return overrides.replay?.(id) ?? { status: "replayed" };
        },
    } as unknown as PlatformClient;
    return { client, calls };
}

describe("Integrations (PHASE-6 §3/§9)", () => {
    it("edits a connector's operations and saves the branch patch", async () => {
        const onSave = vi.fn(async (_mutate: (fresh: NonNullable<AppDefinition["integrations"]>) => NonNullable<AppDefinition["integrations"]>) => {});
        const { client } = stubClient();
        render(createElement(Integrations, { app, client, onSave }));

        expect(screen.getByDisplayValue("bankFeed")).toBeTruthy();
        // add an operation to the existing connector
        fireEvent.click(screen.getByText("Add operation"));
        fireEvent.change(screen.getAllByLabelText(/Operation name bankFeed/)[1]!, { target: { value: "getBalance" } });
        fireEvent.change(screen.getByLabelText("Operation method bankFeed getBalance"), {
            target: { value: "POST" },
        });
        fireEvent.change(screen.getByLabelText("Operation path bankFeed getBalance"), {
            target: { value: "/v1/balance" },
        });

        fireEvent.click(screen.getByText("Save connectors"));
        await waitFor(() => expect(onSave).toHaveBeenCalled());
        // the mutation applies to the FRESH branch (the dashboards rule) — apply it
        // to the authored branch to observe what would be saved
        const branch = onSave.mock.calls[0]![0](app.integrations!) as {
            connectors: { operations: { name: string }[] }[];
        };
        expect(branch.connectors[0]!.operations.map((op) => op.name))
            .toEqual(["listTransactions", "getBalance"]);
    });

    it("authors a credential reference and provisions the secret into the store — never the metadata", async () => {
        const onSave = vi.fn(async (_mutate: (fresh: NonNullable<AppDefinition["integrations"]>) => NonNullable<AppDefinition["integrations"]>) => {});
        const { client, calls } = stubClient();
        render(createElement(Integrations, { app, client, onSave }));

        // the existing api_key credential's material provisions straight to the store
        fireEvent.change(screen.getByLabelText("Secret material bankFeedKey"), {
            target: { value: "sk-live-123" },
        });
        fireEvent.click(screen.getByLabelText("Provision secret bankFeedKey"));
        await waitFor(() => expect(calls.secrets).toEqual([["bankFeedKey", "sk-live-123", undefined]]));

        // a new credential of the oauth2 kind renders its own binding slots
        fireEvent.click(screen.getByText("Add credential"));
        fireEvent.change(screen.getByLabelText("Credential id 1"), { target: { value: "erpOidc" } });
        fireEvent.change(screen.getByLabelText("Credential kind 1"), {
            target: { value: "oauth2_client_credentials" },
        });
        fireEvent.change(screen.getByLabelText("Credential token URL 1"), {
            target: { value: "https://idp.example.local/token" },
        });

        fireEvent.click(screen.getByText("Save credentials"));
        await waitFor(() => expect(onSave).toHaveBeenCalled());
        const branch = onSave.mock.calls[0]![0](app.integrations!) as {
            credentials: { id: string; kind: string; tokenUrl?: string }[];
        };
        expect(branch.credentials.map((credential) => credential.id))
            .toEqual(["bankFeedKey", "erpOidc"]);
        expect(branch.credentials[1]).toMatchObject({
            kind: "oauth2_client_credentials",
            tokenUrl: "https://idp.example.local/token",
        });
        // the material never rides the branch — the schema cannot express it (§9)
        expect(JSON.stringify(branch)).not.toContain("sk-live-123");
    });

    it("authors an outbound webhook beside the inbound one; secrets provision per hook", async () => {
        const onSave = vi.fn(async (_mutate: (fresh: NonNullable<AppDefinition["integrations"]>) => NonNullable<AppDefinition["integrations"]>) => {});
        const { client, calls } = stubClient();
        render(createElement(Integrations, { app, client, onSave }));

        fireEvent.click(screen.getByText("Add webhook"));
        fireEvent.change(screen.getByLabelText("Webhook id 1"), { target: { value: "notifyErp" } });
        fireEvent.change(screen.getByLabelText("Webhook direction 1"), { target: { value: "outbound" } });
        fireEvent.change(screen.getByLabelText("Webhook URL 1"), {
            target: { value: "https://hooks.example.local/nf" },
        });
        fireEvent.change(screen.getByLabelText("Webhook filter 1"), {
            target: { value: "event == 'record.created'" },
        });
        fireEvent.change(screen.getByLabelText("Webhook secret notifyErp"), {
            target: { value: "whsec-1" },
        });
        fireEvent.click(screen.getByLabelText("Provision webhook secret notifyErp"));
        await waitFor(() => expect(calls.secrets).toContainEqual(["notifyErp", "whsec-1", undefined]));

        fireEvent.click(screen.getByText("Save webhooks"));
        await waitFor(() => expect(onSave).toHaveBeenCalled());
        const branch = onSave.mock.calls[0]![0](app.integrations!) as {
            webhooks: { id: string; direction: string }[];
        };
        expect(branch.webhooks).toHaveLength(2);
        expect(branch.webhooks[1]).toMatchObject({
            id: "notifyErp",
            direction: "outbound",
            url: "https://hooks.example.local/nf",
            events: "event == 'record.created'",
        });
    });

    it("renders the delivery log beside the editors and replays DLQ entries", async () => {
        const { client, calls } = stubClient({
            deliveries: [{
                kind: "connector", target: "bankFeed", status: "delivered",
                attempts: 1, lastStatus: 200, latencyMs: 143, createdAt: "2026-08-24T10:00:00Z",
            }],
            dlq: [{
                id: "dlq-1", kind: "webhook", target: "notifyErp",
                dedupeKey: "evt-9", error: "connect timeout", createdAt: "2026-08-24T11:00:00Z",
            }],
        });
        render(createElement(Integrations, {
            app, client, onSave: async () => {},
        }));

        expect(await screen.findByText("delivered")).toBeTruthy();
        expect(screen.getByText("143 ms")).toBeTruthy();
        expect(screen.getByText("connect timeout")).toBeTruthy();

        fireEvent.click(screen.getByLabelText("Replay DLQ entry dlq-1"));
        await waitFor(() => expect(calls.replays).toEqual(["dlq-1"]));
        await waitFor(() => expect(screen.getByText(/dlq-1: replayed/)).toBeTruthy());
    });

    it("renders import/export job runs with progress and drives the resume/inspect legs (§7)", async () => {
        const onSave = vi.fn(async (_mutate: (fresh: NonNullable<AppDefinition["integrations"]>) => NonNullable<AppDefinition["integrations"]>) => {});
        const { client, calls } = stubClient({
            jobs: [
                { id: "job-1", kind: "IMPORT", status: "paused", importMapping: "paymentsFeed",
                    processedRows: 41, totalRows: 100, failedRows: 0, createdAt: "2026-08-25T00:00:00Z" },
                { id: "job-2", kind: "EXPORT_ENTITY", status: "ok", entity: "Invoice",
                    processedRows: 12_000, totalRows: 12_000, failedRows: 0, createdAt: "2026-08-25T01:00:00Z" },
            ],
            jobRows: [
                { row: 40, status: "applied", recordId: "rec-40", code: null, detail: "" },
                { row: 41, status: "failed", recordId: null, code: "4001", detail: "required: number" },
            ],
        });
        render(createElement(Integrations, { app, client, onSave }));

        await waitFor(() => expect(screen.getByText("Import / export jobs")).toBeTruthy());
        // progress counters render per run (§7's import.progress surface, polled)
        expect(screen.getByText("41 / 100")).toBeTruthy();
        expect(screen.getByText("12000 / 12000")).toBeTruthy();

        // a paused import offers resume — the checkpointed exactly-once leg
        fireEvent.click(screen.getByLabelText("Resume job job-1"));
        await waitFor(() => expect(calls.resumes).toEqual(["job-1"]));

        // the row ledger (per-item outcomes retained) drills open
        fireEvent.click(screen.getByLabelText("Inspect rows of job job-1"));
        await waitFor(() => expect(calls.rowsFetched).toEqual(["job-1"]));
        await waitFor(() => expect(screen.getByText("required: number")).toBeTruthy());
    });

    it("a failed ops load never fakes an empty surface (re-audit)", async () => {
        // Anti-regression: the ops catches wiped the rows to [], rendering
        // "No deliveries yet" / "DLQ empty" lies over a dead route
        const failing = {
            integrationDeliveries: async () => {
                throw new Error("integration service unreachable");
            },
            integrationDlq: async () => {
                throw new Error("integration service unreachable");
            },
            integrationJobs: async () => {
                throw new Error("integration service unreachable");
            },
        } as unknown as PlatformClient;
        render(createElement(Integrations, { app, client: failing, onSave: async () => {} }));

        await waitFor(() => expect(screen.getByText(/Could not load deliveries: integration service unreachable/)).toBeTruthy());
        expect(screen.getByText(/Could not load the DLQ/)).toBeTruthy();
        expect(screen.getByText(/Could not load jobs/)).toBeTruthy();
        // never the empty-state lie
        expect(screen.queryByText(/No deliveries yet/)).toBeNull();
        expect(screen.queryByText(/DLQ empty/)).toBeNull();
        expect(screen.queryByText(/No job runs yet/)).toBeNull();
    });

    it("a failed ops RELOAD keeps the last good rows (re-audit)", async () => {
        // the first load lands; the reload after a replay dies — the rows must
        // stay (the old catch wiped them to [] mid-session)
        let deliveryCalls = 0;
        const calls = { replays: [] as string[] };
        const client = {
            integrationDeliveries: async () => {
                deliveryCalls += 1;
                if (deliveryCalls > 1) throw new Error("integration service unreachable");
                return [{ kind: "connector", target: "bankFeed", status: "delivered", createdAt: "2026-08-24T10:00:00Z" }];
            },
            integrationDlq: async () => [
                { id: "dlq-1", kind: "webhook", target: "notifyErp", dedupeKey: "evt-9", error: "connect timeout", createdAt: "2026-08-24T11:00:00Z" },
            ],
            integrationJobs: async () => [],
            replayDlqEntry: async (id: string) => {
                calls.replays.push(id);
                return { status: "replayed" };
            },
        } as unknown as PlatformClient;
        render(createElement(Integrations, { app, client, onSave: async () => {} }));

        expect(await screen.findByText("delivered")).toBeTruthy();
        // the replay's onReplayed fires the reload that fails
        fireEvent.click(screen.getByLabelText("Replay DLQ entry dlq-1"));
        await waitFor(() => expect(calls.replays).toEqual(["dlq-1"]));
        await waitFor(() => expect(deliveryCalls).toBe(2));
        // the last good row survives the failed reload — no empty-state lie
        expect(screen.getByText("delivered")).toBeTruthy();
        expect(screen.queryByText(/No deliveries yet/)).toBeNull();
    });

    it("a failed secret provisioning surfaces its error instead of swallowing (re-audit)", async () => {
        const { client } = stubClient({
            putSecret: async () => {
                throw new Error("secret store rejected the rotation");
            },
        });
        render(createElement(Integrations, { app, client, onSave: async () => {} }));

        fireEvent.change(screen.getByLabelText("Secret material bankFeedKey"), {
            target: { value: "sk-live-123" },
        });
        fireEvent.click(screen.getByLabelText("Provision secret bankFeedKey"));
        await waitFor(() =>
            expect(screen.getByText(/secret store rejected the rotation/)).toBeTruthy());
        // and never the "stored" status for the failed PUT
        expect(screen.queryByText(/rotation window/)).toBeNull();
    });
});
