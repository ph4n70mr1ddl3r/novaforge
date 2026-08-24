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
        const onSave = vi.fn(async (_patch: Record<string, unknown>) => {});
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
        const patch = onSave.mock.calls[0]![0] as {
            integrations: { connectors: { operations: { name: string }[] }[] };
        };
        expect(patch.integrations.connectors[0]!.operations.map((op) => op.name))
            .toEqual(["listTransactions", "getBalance"]);
    });

    it("authors a credential reference and provisions the secret into the store — never the metadata", async () => {
        const onSave = vi.fn(async (_patch: Record<string, unknown>) => {});
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
        const patch = onSave.mock.calls[0]![0] as {
            integrations: { credentials: { id: string; kind: string; tokenUrl?: string }[] };
        };
        expect(patch.integrations.credentials.map((credential) => credential.id))
            .toEqual(["bankFeedKey", "erpOidc"]);
        expect(patch.integrations.credentials[1]).toMatchObject({
            kind: "oauth2_client_credentials",
            tokenUrl: "https://idp.example.local/token",
        });
        // the material never rides the branch — the schema cannot express it (§9)
        expect(JSON.stringify(patch)).not.toContain("sk-live-123");
    });

    it("authors an outbound webhook beside the inbound one; secrets provision per hook", async () => {
        const onSave = vi.fn(async (_patch: Record<string, unknown>) => {});
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
        const patch = onSave.mock.calls[0]![0] as {
            integrations: { webhooks: { id: string; direction: string }[] };
        };
        expect(patch.integrations.webhooks).toHaveLength(2);
        expect(patch.integrations.webhooks[1]).toMatchObject({
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
        const onSave = vi.fn(async (_patch: Record<string, unknown>) => {});
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
});
