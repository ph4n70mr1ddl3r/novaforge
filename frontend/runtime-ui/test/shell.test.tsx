import { describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { createElement } from "react";
import axe from "axe-core";
import {
    PlatformClient,
    resolveDefaultPage,
    pageApiName,
    applyDeltas,
    resolvePage,
    diffPages,
    toPersistedLayout,
} from "@novaforge/shared";
import { RuntimeShell } from "../src/shell.tsx";
import { Inbox } from "../src/inbox.tsx";

/**
 * The runtime shell journey (PHASE-2 §6/T6): published metadata → nav → auto list
 * page (server-side paging) → form page through the real renderer; the approval
 * inbox rides the same shell (PHASE-4 §5).
 */

const app: import("@novaforge/shared").AppDefinition = {
    apiName: "erp",
    label: "ERP",
    entities: [
        {
            apiName: "Customer",
            label: "Customers",
            module: "Sales",
            displayField: "name",
            fields: [
                { apiName: "name", type: "text", required: true, label: "Name" },
                { apiName: "email", type: "email", label: "Email" },
                { apiName: "region", type: "enum", values: ["EU", "US"], label: "Region" },
            ],
            relationships: [],
            validations: [],
            hooks: [],
            indexes: [],
        },
        {
            apiName: "Order",
            label: "Orders",
            module: "Sales",
            displayField: "reference",
            fields: [
                { apiName: "reference", type: "text", required: true, label: "Reference" },
                { apiName: "status", type: "enum", values: ["DRAFT", "POSTED"], label: "Status" },
                { apiName: "total", type: "money", currency: "EUR", label: "Total" },
            ],
            relationships: [],
            validations: [],
            hooks: [],
            indexes: [],
        },
    ],
    pages: [],
    permissionSet: {
        roles: [{ name: "arClerk", description: "" }],
        objectPermissions: [
            { role: "arClerk", entity: "Customer", create: true, read: true, update: true },
            { role: "arClerk", entity: "Order", create: true, read: true, update: true, delete: true },
        ],
        fieldSecurity: [{ role: "arClerk", entity: "Order", field: "total", access: "readonly" }],
    },
    stateMachines: [],
    reports: [],
    dashboards: [],
    translations: [],
};

function stubClient(lists: Record<string, unknown> = {}) {
    const fetchImpl = vi.fn(async (input: string | URL) => {
        const url = String(input);
        if (url.includes("/runtime/erp.Customer")) {
            return new Response(
                JSON.stringify(lists.Customer ?? { rows: [{ id: "c-1", name: "Acme", region: "EU" }], total: 1 }),
                { status: 200, headers: { "Content-Type": "application/json" } },
            );
        }
        if (url.includes("/runtime/erp.Order")) {
            return new Response(
                JSON.stringify(lists.Order ?? { rows: [], total: 0 }),
                { status: 200, headers: { "Content-Type": "application/json" } },
            );
        }
        return new Response(JSON.stringify({ title: "not stubbed", status: 404 }), { status: 404 });
    });
    return { client: new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch), fetchImpl };
}

function shell(client: PlatformClient) {
    return createElement(RuntimeShell, {
        client,
        published: { version: 3, app } as never,
        user: { name: "demo", roles: ["erp.arClerk"] },
        versionKey: "v3",
    });
}

describe("RuntimeShell", () => {
    it("renders nav from published metadata (module grouping) and the auto list page with server paging", async () => {
        const { client } = stubClient();
        render(shell(client));
        expect(await screen.findByRole("button", { name: "Customers" })).toBeTruthy();
        expect(screen.getByRole("button", { name: "Orders" })).toBeTruthy();
        // navigate to the Customers list
        screen.getByRole("button", { name: "Customers" }).click();
        await waitFor(() => expect(screen.getByText("1 record")).toBeTruthy());
        expect(screen.getByText("Acme")).toBeTruthy();
    });

    it("renders the form page through the real renderer (zero page definitions)", async () => {
        const { client } = stubClient();
        render(shell(client));
        screen.getByRole("button", { name: "Orders" }).click();
        await waitFor(() => expect(screen.getByText("0 records")).toBeTruthy());
        // the Order list exposes the create action (arClerk create grant)
        expect(screen.getByRole("button", { name: /new|add/i })).toBeTruthy();
    });

    it("a double-clicked Save creates exactly one record (in-flight fence + idempotency key)", async () => {
        // Anti-regression (eighteenth pass): the save had no in-flight guard and
        // the create carried no idempotency key — a fast double-click landed two
        // POSTs and minted two records before the re-render disabled anything.
        let release: ((response: Response) => void) | undefined;
        const posts: { key: string | null; url: string }[] = [];
        const fetchImpl = vi.fn(async (input: string | URL, init?: RequestInit) => {
            const url = String(input);
            const method = (init?.method ?? "GET").toUpperCase();
            if (url.includes("/runtime/erp.Customer") && method === "POST") {
                posts.push({ key: (init?.headers as Record<string, string>)?.["Idempotency-Key"] ?? null, url });
                return new Promise<Response>((resolve) => {
                    release = resolve;
                });
            }
            if (url.includes("/runtime/erp.Customer")) {
                return new Response(
                    JSON.stringify({ rows: [{ id: "c-1", name: "Acme", region: "EU" }], total: 1 }),
                    { status: 200, headers: { "Content-Type": "application/json" } },
                );
            }
            if (url.includes("/runtime/erp.Order")) {
                return new Response(JSON.stringify({ rows: [], total: 0 }), { status: 200 });
            }
            return new Response(JSON.stringify({ title: "not stubbed", status: 404 }), { status: 404 });
        });
        const client = new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);
        render(shell(client));
        screen.getByRole("button", { name: "Customers" }).click();
        await waitFor(() => expect(screen.getByText("1 record")).toBeTruthy());
        screen.getByRole("button", { name: /new|add/i }).click();
        const name = (await screen.findByLabelText(/^Name/)) as HTMLInputElement;
        await act(async () => {
            fireEvent.change(name, { target: { value: "Acme" } });
        });
        const save = await screen.findByRole("button", { name: "Save" });
        // two clicks before the create resolves — the fence must swallow the second
        await act(async () => {
            save.click();
            save.click();
        });
        expect(posts).toHaveLength(1);
        // the create rode an idempotency key (the server-side ceiling on the race)
        expect(posts[0]!.key).toBeTruthy();
        // the create addresses the entity APP-QUALIFIED (erp.Customer): a bare
        // name 400s at the resolver the moment a second published app defines
        // the same apiName in the tenant (the ERP-corpus collision, live)
        expect(posts[0]!.url).toContain("/runtime/erp.Customer");
        // the create resolves cleanly — and the fence never let a second POST out
        release?.(new Response(JSON.stringify({ id: "c-2", name: "Acme" }), { status: 200 }));
        await act(async () => {
            await new Promise((resolve) => setTimeout(resolve, 20));
        });
        expect(posts).toHaveLength(1);
    });

    it("the inbox pages tasks and resolves approvals", async () => {
        const { client } = stubClient();
        render(createElement(Inbox, { client }));
        // /api/v1/workflow/tasks is not stubbed → error surfaced accessibly, not a crash
        await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
    });

    it("the inbox fences its pager while a resolve reload is in flight (re-audit)", async () => {
        // the resolve-reload's response is slow: the pager staying live let a page
        // change race it — the older page-0 rows landed last and clobbered the
        // newer page. busy now owns the pager too.
        const rows = [{ id: "t-1", type: "approval", entity: "Erp.Order", recordId: "r-1", status: "OPEN", createdAt: "2026-08-24T10:00:00Z" }];
        let releaseReload: ((rows: Record<string, unknown>[], total: number) => void) | undefined;
        const fetchImpl = vi.fn(async (input: string | URL, init?: RequestInit) => {
            const url = String(input);
            const method = (init?.method ?? "GET").toUpperCase();
            if (url.includes("/workflow/tasks/") && method === "POST") {
                return new Response(JSON.stringify({ ok: true }), { status: 200 });
            }
            if (url.includes("/workflow/tasks")) {
                return new Promise<Response>((resolve) => {
                    releaseReload = (nextRows, total) =>
                        resolve(new Response(JSON.stringify({ rows: nextRows, total }),
                            { status: 200, headers: { "Content-Type": "application/json" } }));
                });
            }
            return new Response(JSON.stringify({ title: "not stubbed", status: 404 }), { status: 404 });
        });
        const client = new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);
        render(createElement(Inbox, { client }));

        // the mount load commits page 0 (total 30 → Next is armed)
        await waitFor(() => expect(releaseReload).toBeTruthy());
        const mountGate = releaseReload!;
        releaseReload = undefined;
        mountGate(rows, 30);
        await waitFor(() => expect(screen.getByRole("row", { name: /Erp\.Order \(r-1\)/ })).toBeTruthy());
        expect((screen.getByRole("button", { name: "Next" }) as HTMLButtonElement).disabled).toBe(false);

        // approve → the resolve reload hangs: the pager must fence for the flight
        screen.getByRole("button", { name: "Approve" }).click();
        await waitFor(() =>
            expect((screen.getByRole("button", { name: "Next" }) as HTMLButtonElement).disabled).toBe(true));
        // the slow response eventually lands and commits the fresh (empty) page
        await waitFor(() => expect(releaseReload).toBeTruthy());
        releaseReload!([], 29);
        await waitFor(() => expect(screen.getByText("No pending approvals.")).toBeTruthy());
        expect((screen.getByRole("button", { name: "Next" }) as HTMLButtonElement).disabled).toBe(false);
    });

    it("a stale inbox reload never clobbers a newer load's rows (notifications' fence, re-audit)", async () => {
        // two resolve-reloads overlap (a double-approve before the buttons fence);
        // the OLDER response landing last must be refused by the sequence check
        const rows = (ids: string[]) => ids.map((id) => ({
            id, type: "approval", entity: "Erp.Order", recordId: `r-${id}`, status: "OPEN",
            createdAt: "2026-08-24T10:00:00Z",
        }));
        const gates: ((rows: Record<string, unknown>[]) => void)[] = [];
        const fetchImpl = vi.fn(async (input: string | URL, init?: RequestInit) => {
            const url = String(input);
            const method = (init?.method ?? "GET").toUpperCase();
            if (url.includes("/workflow/tasks/") && method === "POST") {
                return new Response(JSON.stringify({ ok: true }), { status: 200 });
            }
            if (url.includes("/workflow/tasks")) {
                return new Promise<Response>((resolve) => {
                    gates.push((nextRows) => {
                        resolve(new Response(
                            JSON.stringify({ rows: nextRows, total: nextRows.length }),
                            { status: 200, headers: { "Content-Type": "application/json" } },
                        ));
                    });
                });
            }
            return new Response(JSON.stringify({ title: "not stubbed", status: 404 }), { status: 404 });
        });
        const client = new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);
        render(createElement(Inbox, { client }));

        // mount load commits (one row, one task on screen)
        await waitFor(() => expect(gates.length).toBe(1));
        gates.shift()!(rows(["t-1"]));
        await waitFor(() => expect(screen.getByRole("row", { name: /Erp\.Order \(r-t-1\)/ })).toBeTruthy());

        // two approvals leave in one batch — both reloads fly before busy fences
        await act(async () => {
            screen.getByRole("button", { name: "Approve" }).click();
            fireEvent.click(screen.getByRole("button", { name: "Approve" }));
        });
        await waitFor(() => expect(gates.length).toBe(2));
        // the NEWER reload's rows land first…
        gates[1]!(rows(["t-2"]));
        await waitFor(() => expect(screen.getByRole("row", { name: /Erp\.Order \(r-t-2\)/ })).toBeTruthy());
        // …then the stale one resolves LAST — it must be refused, not committed
        gates[0]!(rows(["t-1"]));
        await act(async () => {
            await new Promise((resolve) => setTimeout(resolve, 20));
        });
        expect(screen.queryByRole("row", { name: /Erp\.Order \(r-t-2\)/ })).toBeTruthy();
        expect(screen.queryByRole("row", { name: /Erp\.Order \(r-t-1\)/ })).toBeNull();
    });

    it("the inbox claims role tasks and delegates (PHASE-4 §11's full ladder)", async () => {
        const calls: string[] = [];
        const roleTask = {
            id: "t-1", type: "approval", entity: "Erp.JournalEntry", recordId: "r-9",
            assignee: null, role: "accountingManager", status: "OPEN", createdBy: "u-2",
            createdAt: "2026-08-24T10:00:00Z",
        };
        const fetchImpl = vi.fn(async (input: string | URL, init?: RequestInit) => {
            const url = String(input);
            calls.push(`${(init?.method ?? "GET").toUpperCase()} ${url}`);
            if (url.includes("/workflow/tasks/t-1/claim")) {
                return new Response(JSON.stringify({ ...roleTask, assignee: "u-1" }), {
                    status: 200, headers: { "Content-Type": "application/json" } });
            }
            if (url.includes("/workflow/tasks/t-1/delegate")) {
                return new Response(JSON.stringify({ id: "t-2", status: "OPEN" }), {
                    status: 200, headers: { "Content-Type": "application/json" } });
            }
            if (url.includes("/workflow/tasks")) {
                return new Response(JSON.stringify({ rows: [roleTask], total: 1 }), {
                    status: 200, headers: { "Content-Type": "application/json" } });
            }
            return new Response(JSON.stringify({ title: "not stubbed", status: 404 }), { status: 404 });
        });
        const client = new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);

        render(createElement(Inbox, { client }));
        // role-addressed task renders its role and offers Claim; assigned offers Delegate
        expect(await screen.findByText(/role: accountingManager/)).toBeTruthy();
        screen.getByRole("button", { name: "Claim" }).click();
        await waitFor(() => expect(calls.some((call) => call.includes("/tasks/t-1/claim"))).toBe(true));

        // delegate rides the in-app dialog now (the blocking window.prompt is gone)
        screen.getByRole("button", { name: "Delegate" }).click();
        const targetInput = await screen.findByRole("dialog", { name: "Delegate task" });
        const field = targetInput.querySelector("input") as HTMLInputElement;
        fireEvent.change(field, { target: { value: "u-9" } });
        fireEvent.click(within(targetInput).getByRole("button", { name: "Delegate" }));
        await waitFor(() => expect(calls.some((call) => call.includes("/tasks/t-1/delegate"))).toBe(true));
    });

    it("marks the active view in the nav with aria-current — the runtime nav finally has a where-am-I face", async () => {
        const { client } = stubClient();
        render(shell(client));
        const home = await screen.findByRole("button", { name: "Home" });
        expect(home.getAttribute("aria-current")).toBe("page");

        // an entity list: its nav button takes the marker, Home loses it
        const customers = screen.getByRole("button", { name: "Customers" });
        customers.click();
        await waitFor(() => expect(screen.getByText("1 record")).toBeTruthy());
        expect(customers.getAttribute("aria-current")).toBe("page");
        expect(home.getAttribute("aria-current")).toBeNull();

        // and the top-level surfaces each carry it in turn
        screen.getByRole("button", { name: "Approvals" }).click();
        await waitFor(() =>
            expect(screen.getByRole("button", { name: "Approvals" }).getAttribute("aria-current")).toBe("page"));
        expect(customers.getAttribute("aria-current")).toBeNull();
    });

    it("navigating away from a dirty form asks first — a stray nav click used to destroy typed data", async () => {
        const { client } = stubClient();
        render(shell(client));
        screen.getByRole("button", { name: "Customers" }).click();
        await waitFor(() => expect(screen.getByText("1 record")).toBeTruthy());
        screen.getByRole("button", { name: /new|add/i }).click();
        // the form's lazy fields suspend then resolve — wait for the FORM's own
        // chrome before grabbing the field, never a detached node of the old page
        await screen.findByRole("button", { name: "Save" });
        const name = screen.getByLabelText(/^Name/) as HTMLInputElement;
        await act(async () => {
            fireEvent.change(name, { target: { value: "Acme" } });
        });

        // the nav click must NOT switch views — the guard interjects first
        await act(async () => {
            screen.getByRole("button", { name: "Approvals" }).click();
        });
        const dialog = await screen.findByRole("dialog", { name: "Unsaved changes" });
        // the draft is still on screen behind the dialog — nothing was lost yet
        expect((screen.getByLabelText(/^Name/) as HTMLInputElement).value).toBe("Acme");

        // Keep editing: the dialog closes, the route never changed
        await act(async () => {
            within(dialog).getByRole("button", { name: "Keep editing" }).click();
        });
        expect(screen.queryByRole("dialog", { name: "Unsaved changes" })).toBeNull();
        expect(screen.getByLabelText(/^Name/)).toBeTruthy();

        // Discard changes: the interrupted navigation completes
        await act(async () => {
            screen.getByRole("button", { name: "Approvals" }).click();
        });
        await act(async () => {
            within(await screen.findByRole("dialog", { name: "Unsaved changes" }))
                .getByRole("button", { name: "Discard changes" }).click();
        });
        await waitFor(() => expect(screen.getByRole("heading", { name: "My approvals" })).toBeTruthy());
    });

    it("the unsaved-changes dialog honors the keyboard contract — Escape cancels, Tab is trapped, focus returns to the trigger", async () => {
        // the inbox's ask dialog and the builder's rollback panel both trap Tab,
        // cancel on Escape, and restore focus on close — the guard's dialog was
        // the product's most destructive modal and the only one without the
        // contract (Tab wandered behind its scrim; close dumped focus to <body>)
        const { client } = stubClient();
        render(shell(client));
        screen.getByRole("button", { name: "Customers" }).click();
        await waitFor(() => expect(screen.getByText("1 record")).toBeTruthy());
        screen.getByRole("button", { name: /new|add/i }).click();
        await screen.findByRole("button", { name: "Save" });
        const name = screen.getByLabelText(/^Name/) as HTMLInputElement;
        await act(async () => {
            fireEvent.change(name, { target: { value: "Acme" } });
        });

        // the guard fires from a nav click — the trigger owns focus at that moment
        // (a keyboard user Tab-navigating holds focus on the button they press)
        const trigger = screen.getByRole("button", { name: "Approvals" });
        await act(async () => {
            trigger.focus();
            trigger.click();
        });
        const dialog = await screen.findByRole("dialog", { name: "Unsaved changes" });
        // the dialog's own autofocus owns focus on open — the trigger is remembered
        // for the restore on CLOSE
        expect(document.activeElement).toBe(within(dialog).getByRole("button", { name: "Keep editing" }));

        // Tab at the dialog's LAST control wraps to its first — never to the
        // record form behind the scrim
        await act(async () => {
            within(dialog).getByRole("button", { name: "Discard changes" }).focus();
            fireEvent.keyDown(document, { key: "Tab" });
        });
        expect(dialog.contains(document.activeElement)).toBe(true);
        expect(document.activeElement).toBe(within(dialog).getByRole("button", { name: "Keep editing" }));

        // Escape cancels: the dialog closes, the route never changed, and focus
        // is restored to the nav trigger that opened the gate
        await act(async () => {
            fireEvent.keyDown(document, { key: "Escape" });
        });
        expect(screen.queryByRole("dialog", { name: "Unsaved changes" })).toBeNull();
        expect(screen.getByLabelText(/^Name/)).toBeTruthy();
        expect(document.activeElement).toBe(trigger);
    });

    it("a page that just SAVED navigates silently — the guard never re-prompts on its own save", async () => {
        const fetchImpl = vi.fn(async (input: string | URL, init?: RequestInit) => {
            const url = String(input);
            const method = (init?.method ?? "GET").toUpperCase();
            if (url.includes("/runtime/erp.Customer") && method === "POST") {
                return new Response(JSON.stringify({ id: "c-2", name: "Acme", version: 1 }), { status: 200 });
            }
            if (url.includes("/runtime/erp.Customer/c-2")) {
                return new Response(JSON.stringify({ id: "c-2", name: "Acme", version: 1 }), { status: 200 });
            }
            if (url.includes("/runtime/erp.Customer")) {
                return new Response(
                    JSON.stringify({ rows: [{ id: "c-1", name: "Acme", region: "EU" }], total: 1 }),
                    { status: 200 },
                );
            }
            return new Response(JSON.stringify({ rows: [], total: 0 }), { status: 200 });
        });
        const client = new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);
        render(shell(client));
        screen.getByRole("button", { name: "Customers" }).click();
        await waitFor(() => expect(screen.getByText("1 record")).toBeTruthy());
        screen.getByRole("button", { name: /new|add/i }).click();
        await screen.findByRole("button", { name: "Save" });
        const name = screen.getByLabelText(/^Name/);
        await act(async () => {
            fireEvent.change(name, { target: { value: "Acme" } });
        });
        const save = await screen.findByRole("button", { name: "Save" });
        await act(async () => {
            save.click();
        });
        // the create resolved and navigated to the new record's detail
        await waitFor(() => expect(screen.getByDisplayValue("Acme")).toBeTruthy());

        // leaving NOW is silent: the save adopted its own baseline before navigating
        await act(async () => {
            screen.getByRole("button", { name: "Approvals" }).click();
        });
        await waitFor(() => expect(screen.getByRole("heading", { name: "My approvals" })).toBeTruthy());
        expect(screen.queryByRole("dialog", { name: "Unsaved changes" })).toBeNull();
    });

    it("the ask dialog traps Tab, cancels on Escape, and restores focus to its trigger", async () => {
        const rows = [{ id: "t-1", type: "approval", entity: "Erp.Order", recordId: "r-1", status: "OPEN", createdBy: "u-2", createdAt: "2026-08-24T10:00:00Z" }];
        const fetchImpl = vi.fn(async (input: string | URL) => {
            const url = String(input);
            if (url.includes("/workflow/tasks")) {
                return new Response(JSON.stringify({ rows, total: 1 }), { status: 200 });
            }
            return new Response(JSON.stringify({ title: "not stubbed", status: 404 }), { status: 404 });
        });
        const client = new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);
        render(createElement(Inbox, { client }));

        const triggers = await screen.findAllByRole("button", { name: "Reject" });
        expect(triggers).toHaveLength(1);
        await act(async () => {
            // a real click focuses the trigger — jsdom's synthetic click doesn't
            triggers[0]!.focus();
            triggers[0]!.click();
        });
        const dialog = await screen.findByRole("dialog", { name: "Reject task" });
        expect(dialog.getAttribute("aria-modal")).toBe("true");
        // autofocus landed in the comment field
        const comment = dialog.querySelector("textarea") as HTMLTextAreaElement;
        expect(document.activeElement).toBe(comment);

        // Tab from the LAST focusable (the dialog's own Reject) cycles to the FIRST
        const submit = within(dialog).getByRole("button", { name: "Reject" });
        await act(async () => {
            submit.focus();
            fireEvent.keyDown(document, { key: "Tab" });
        });
        expect(document.activeElement).toBe(comment);

        // Escape cancels — and focus returns to the row's Reject trigger
        await act(async () => {
            fireEvent.keyDown(document, { key: "Escape" });
        });
        await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
        expect(document.activeElement).toBe(triggers[0]);
    });

    it("passes axe on the shell chrome", async () => {
        const { client } = stubClient();
        const { container } = render(shell(client));
        await screen.findByRole("button", { name: "Customers" });
        const results = await axe.run(container, {});
        expect(results.violations).toEqual([]);
    });

    it("a state-machine transition PATCHes the versioned record and applies the SERVER's response (re-audit)", async () => {
        // Anti-regression: the transition buttons only setRecords locally — the
        // state flip silently reverted on reload and nothing reached the server.
        const machine = {
            id: "sm-order",
            entity: "Order",
            stateField: "status",
            initial: "DRAFT",
            states: [{ name: "DRAFT" }, { name: "POSTED" }],
            transitions: [{ from: "DRAFT", to: "POSTED" }],
        };
        const record = { id: "o-1", reference: "ORD-1", status: "DRAFT", version: 4 };
        const patches: { method: string; url: string; body: Record<string, unknown> }[] = [];
        const fetchImpl = vi.fn(async (input: string | URL, init?: RequestInit) => {
            const url = String(input);
            const method = (init?.method ?? "GET").toUpperCase();
            if (url.includes("/runtime/erp.Order/o-1") && method === "PATCH") {
                patches.push({ method, url, body: JSON.parse(String(init?.body)) });
                // the SERVER's record: new state, new version, and a marker a local
                // guess could never produce
                return new Response(
                    JSON.stringify({ id: "o-1", reference: "ORD-1-SERVER", status: "POSTED", version: 5 }),
                    { status: 200, headers: { "Content-Type": "application/json" } },
                );
            }
            if (url.includes("/runtime/erp.Order/o-1")) {
                return new Response(JSON.stringify(record), {
                    status: 200, headers: { "Content-Type": "application/json" } });
            }
            if (url.includes("/runtime/erp.Order")) {
                return new Response(JSON.stringify({ rows: [record], total: 1 }), {
                    status: 200, headers: { "Content-Type": "application/json" } });
            }
            if (url.includes("/runtime/erp.Customer")) {
                return new Response(
                    JSON.stringify({ rows: [{ id: "c-1", name: "Acme", region: "EU" }], total: 1 }),
                    { status: 200 },
                );
            }
            return new Response(JSON.stringify({ title: "not stubbed", status: 404 }), { status: 404 });
        });
        const client = new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);
        render(createElement(RuntimeShell, {
            client,
            published: { version: 3, app: { ...app, stateMachines: [machine] } } as never,
            user: { name: "demo", roles: ["erp.arClerk"] },
            versionKey: "v3",
        }));

        // list → row click opens the detail; the DRAFT machine offers POSTED
        screen.getByRole("button", { name: "Orders" }).click();
        await waitFor(() => expect(screen.getByText("ORD-1")).toBeTruthy());
        screen.getByText("ORD-1").click();
        const posted = await screen.findByRole("button", { name: "POSTED" });
        posted.click();
        posted.click(); // the double-click — the fence must swallow it

        // exactly ONE versioned PATCH carrying the state field and the record's version
        await waitFor(() => expect(patches).toHaveLength(1));
        expect(patches[0]!.method).toBe("PATCH");
        expect(patches[0]!.url).toContain("/runtime/erp.Order/o-1");
        expect(patches[0]!.body).toEqual({ version: 4, status: "POSTED" });
        // the SERVER's record lands (its marker, in the detail's readonly input),
        // and the POSTED-state record offers no further transitions — the group empties
        await waitFor(() => expect(screen.getByDisplayValue("ORD-1-SERVER")).toBeTruthy());
        await waitFor(() =>
            expect(screen.queryByRole("group", { name: "State transitions" })).toBeNull());
    });
});

describe("the page pipeline: L1 → overlay deltas → persisted artifact", () => {
    const order = app.entities[1]!;
    it("saved deltas reshape the rendered page; toPersistedLayout round-trips", () => {
        const l1 = resolveDefaultPage(order, "form", { role: "arClerk", permissions: app.permissionSet });
        expect(l1.model.root.children!.find((child) => child.key === "field:total")!.readonly).toBe("true");

        const edited = applyDeltas(l1, [
            { op: "setSlot", key: "field:status", slot: "visibility", value: "status != 'POSTED'" },
        ]).page;
        const persisted = toPersistedLayout(edited, l1);
        expect(persisted.base).toBe("auto");
        expect(persisted.deltas).toHaveLength(1);

        // resolution from the saved artifact reproduces the edit
        const resolved = resolvePage(
            { apiName: pageApiName("Order", "form"), type: "form", entity: "Order", layout: persisted },
            order,
            { role: "arClerk", permissions: app.permissionSet },
        );
        expect(resolved.stale).toEqual([]);
        expect(
            resolved.page.model.root.children!.find((child) => child.key === "field:status")!.visibility,
        ).toBe("status != 'POSTED'");
        // and diffPages(edited vs l1) equals the persisted deltas
        expect(diffPages(l1, edited)).toEqual(persisted.deltas);
    });
});
