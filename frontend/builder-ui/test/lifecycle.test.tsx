import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import type { AppDefinition, GapLogEntry, PlatformClient } from "@novaforge/shared";
import { GapLogEditor, Lifecycle } from "../src/lifecycle.tsx";

/**
 * The Phase 8 lifecycle screens against the change-set review payload the Metadata
 * Service actually shapes (§3): per-branch diffs, version-bound suite results, the
 * script-ratio pair, the credential re-bind union, the gap-log entries the version
 * resolves (the Phase 7 continuity surface), and the promotion history with
 * overrides rendered forever. The gap-log editor beside them is the PHASE-7 §1
 * rule-2 authoring surface: log every gap before working around it.
 */

function stubClient(changeset: Record<string, unknown>): PlatformClient {
    return {
        changeset: async () => changeset,
        promote: async () => ({}),
        rollback: async () => ({}),
    } as unknown as PlatformClient;
}

const review = {
    env: "staging",
    publishedVersion: 4,
    diff: {
        entities: { added: ["CreditNote"], modified: ["Invoice"], removed: [] },
        gapLog: { added: [], modified: ["G-1"], removed: [] },
        permissionSetChanged: true,
    },
    suiteResults: [{ suite: "reconciliation", green: true, runAt: "2026-08-25T00:00:00Z" }],
    scriptRatio: {
        draft: 0.33,
        published: 0.5,
        modules: {
            GL: { hooks: 1, scripts: 0, scriptShare: 0 },
            Inventory: { hooks: 1, scripts: 1, scriptShare: 1 },
        },
    },
    credentialRefs: ["bankFeedKey", "erpOidc"],
    resolvedGaps: [
        { id: "G-1", area: "P3 Business Logic", disposition: "closed", resolvedIn: "this change set" },
    ],
    promotions: [
        { env: "staging", toVersion: 4, overridden: false },
        { env: "prod", toVersion: 3, overridden: true, reason: "hotfix" },
    ],
};

const app: AppDefinition = {
    apiName: "Erp",
    entities: [],
    pages: [],
    stateMachines: [],
    permissionSet: { roles: [], objectPermissions: [], fieldSecurity: [] },
    reports: [],
    dashboards: [],
    translations: [],
    gapLog: [
        {
            id: "G-1",
            area: "P3 Business Logic",
            blocker: "createRecord cannot capture the created id",
            priority: "high",
            disposition: "open",
        },
    ],
};

describe("Lifecycle change-set review (PHASE-8 §3)", () => {
    it("publishes the dev version from the builder (PHASE-2 §8: authoring without an API leg)", async () => {
        const publish = vi.fn(async () => ({ version: 7 }));
        const client = {
            ...stubClient(review),
            publish,
        } as unknown as PlatformClient;
        render(createElement(Lifecycle, { client, appId: "app-1" }));
        fireEvent.click(screen.getByTestId("publish"));
        await waitFor(() => expect(screen.getByRole("status").textContent).toContain("Published v7"));
        expect(publish).toHaveBeenCalledWith("app-1");
    });

    it("renders the real payload: diff rows, version-bound suites, ratio, re-bind union, resolved gaps, override history", async () => {
        render(createElement(Lifecycle, { client: stubClient(review), appId: "app-1" }));

        await waitFor(() => expect(screen.getByText("Change set (from v4)")).toBeTruthy());
        // per-branch diff rows
        expect(screen.getByText("CreditNote")).toBeTruthy();
        expect(screen.getByText("added")).toBeTruthy();
        // the gapLog diff row + the resolved-gaps entry both reference G-1
        expect(screen.getAllByText("G-1").length).toBeGreaterThanOrEqual(1);
        // suite results hash-bound to the draft
        expect(screen.getByText(/reconciliation: green/)).toBeTruthy();
        // the script-ratio pair + the §9-item-7 per-module report
        expect(screen.getByTestId("script-ratio").textContent).toContain("33%");
        expect(screen.getByTestId("script-ratio").textContent).toContain("Inventory 100% (1/1)");
        // the re-bind union
        expect(screen.getByText("Credentials to re-bind in staging")).toBeTruthy();
        expect(screen.getByText("erpOidc")).toBeTruthy();
        // the gap-log entries this version resolves (Phase 7 continuity)
        expect(screen.getByText(/\(P3 Business Logic\) — closed: this change set/)).toBeTruthy();
        // overrides render forever
        expect(screen.getByText(/OVERRIDE: hotfix/)).toBeTruthy();
    });

    it("a red suite disables promote until a green run of this exact draft lands", async () => {
        render(createElement(Lifecycle, {
            client: stubClient({
                ...review,
                suiteResults: [{ suite: "reconciliation", green: false }],
            }),
            appId: "app-1",
        }));
        await waitFor(() => expect(screen.getByText(/reconciliation: red/)).toBeTruthy());
        expect((screen.getByTestId("promote") as HTMLButtonElement).disabled).toBe(true);
    });
});

describe("GapLogEditor (PHASE-7 §1 rule 2)", () => {
    it("triages a logged gap and saves the gapLog branch patch", async () => {
        // the save mutates a FRESH fetch (the dashboards rule) — apply the captured
        // patch builder to the unchanged app to observe what would be saved
        let saved: Record<string, unknown> | undefined;
        const onSave = vi.fn(async (patch: (fresh: AppDefinition) => Record<string, unknown>) => {
            saved = patch(app);
        });
        render(createElement(GapLogEditor, { app, onSave }));

        fireEvent.change(screen.getByLabelText("Disposition of G-1"), {
            target: { value: "accept-as-platform-feature" },
        });
        fireEvent.change(screen.getByLabelText("Resolved in (G-1)"), {
            target: { value: "next platform increment" },
        });
        fireEvent.click(screen.getByText("Save gap log"));

        await waitFor(() => expect(onSave).toHaveBeenCalled());
        const gapLog = (saved as { gapLog: { id: string; disposition: string; resolvedIn?: string }[] }).gapLog;
        expect(gapLog[0]!.id).toBe("G-1");
        expect(gapLog[0]!.disposition).toBe("accept-as-platform-feature");
        expect(gapLog[0]!.resolvedIn).toBe("next platform increment");
    });

    it("keeps an entry another tab logged after mount — the dashboards rule (re-audit)", async () => {
        // Anti-regression: the editor saved its mount-time gapLog verbatim, so a
        // concurrent tab's entry was silently deleted by this tab's triage save
        const foreign = {
            id: "G-2", area: "P4 Reporting", blocker: "no CSV export",
            priority: "low" as const, disposition: "open" as const,
        };
        const saved: Record<string, unknown>[] = [];
        const onSave = vi.fn(async (patch: (fresh: AppDefinition) => Record<string, unknown>) => {
            // the SHELL's fresh fetch lands with the concurrent entry aboard
            saved.push(patch({ ...app, gapLog: [...app.gapLog!, foreign] }));
        });
        render(createElement(GapLogEditor, { app, onSave }));

        fireEvent.change(screen.getByLabelText("Disposition of G-1"), {
            target: { value: "backlog" },
        });
        fireEvent.click(screen.getByText("Save gap log"));
        await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
        const gapLog = saved[0]!.gapLog as GapLogEntry[];
        // the triaged G-1 AND the foreign G-2 — no silent delete
        expect(gapLog.map((entry) => entry.id)).toEqual(["G-1", "G-2"]);
        expect(gapLog[0]!.disposition).toBe("backlog");
    });

    it("Add gap mints the first free G-N — no collision with gap ids (re-audit)", async () => {
        // Anti-regression: G-${length+1} collided with a surviving G-N after
        // out-of-band deletions left gaps — [G-1, G-3] minted a duplicate G-3
        const gapped: AppDefinition = {
            ...app,
            gapLog: [
                ...(app.gapLog ?? []),
                { id: "G-3", area: "P5 Platform", blocker: "no audit export", priority: "low", disposition: "open" },
            ],
        };
        let saved: Record<string, unknown> | undefined;
        const onSave = vi.fn(async (patch: (fresh: AppDefinition) => Record<string, unknown>) => {
            saved = patch(gapped);
        });
        render(createElement(GapLogEditor, { app: gapped, onSave }));

        fireEvent.click(screen.getByText("Add gap"));
        fireEvent.click(screen.getByText("Save gap log"));
        await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
        const gapLog = (saved as { gapLog: GapLogEntry[] }).gapLog;
        expect(gapLog.map((entry) => entry.id)).toEqual(["G-1", "G-3", "G-2"]);
    });
});
