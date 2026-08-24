import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import type { PlatformClient } from "@novaforge/shared";
import { Notifications } from "../src/notifications.tsx";

/**
 * The notification inbox + preferences (PHASE-4 §8's runtime UI): my rows paged
 * with mark-read, and the per-category channel toggles riding the notification
 * service's own-data surface (/api/v1/notifications/**).
 */

function clientWith(calls: { method: string; path: string; body?: unknown }[]): PlatformClient {
    return {
        notifications: async () => {
            calls.push({ method: "GET", path: "/api/v1/notifications" });
            return {
                rows: [
                    { id: "n-1", category: "task-assignment", title: "PO-42 needs approval", body: "Approve or reject", created_at: "2026-08-24T10:00:00Z", read_at: null },
                    { id: "n-2", category: "sla-warning", title: "PO-42 SLA at 80%", body: "Breach in 4h", created_at: "2026-08-24T11:00:00Z", read_at: "2026-08-24T12:00:00Z" },
                ],
                total: 2,
            };
        },
        markNotificationRead: async (id: string) => {
            calls.push({ method: "POST", path: `/api/v1/notifications/${id}/read` });
            return { status: "read" };
        },
        notificationPreferences: async () => {
            calls.push({ method: "GET", path: "/api/v1/notifications/preferences" });
            return [{ category: "task-assignment", inbox: true, email: false }];
        },
        setNotificationPreference: async (category: string, inbox: boolean, email: boolean) => {
            calls.push({ method: "POST", path: "/api/v1/notifications/preferences", body: { category, inbox, email } });
            return { status: "saved" };
        },
    } as unknown as PlatformClient;
}

describe("Notifications (PHASE-4 §8)", () => {
    it("renders the inbox, marks a row read, and reloads", async () => {
        const calls: { method: string; path: string; body?: unknown }[] = [];
        render(createElement(Notifications, { client: clientWith(calls) }));

        await waitFor(() => expect(screen.getByText("PO-42 needs approval")).toBeTruthy());
        // unread rows carry the mark-read action; read ones show the timestamp
        const unread = screen.getByRole("row", { name: /PO-42 needs approval/ });
        expect(unread.getAttribute("data-read")).toBe("false");
        expect(screen.getByRole("row", { name: /PO-42 SLA at 80%/ }).getAttribute("data-read")).toBe("true");

        fireEvent.click(screen.getByRole("button", { name: "Mark read PO-42 needs approval" }));
        await waitFor(() =>
            expect(calls).toContainEqual({ method: "POST", path: "/api/v1/notifications/n-1/read" }),
        );
        // the reload after marking reads the inbox again
        await waitFor(() =>
            expect(calls.filter((call) => call.path === "/api/v1/notifications").length).toBeGreaterThanOrEqual(2),
        );
    });

    it("loads saved preferences and toggles a channel through the write API", async () => {
        const calls: { method: string; path: string; body?: unknown }[] = [];
        render(createElement(Notifications, { client: clientWith(calls) }));

        // saved: task-assignment inbox on, email off — unsaved categories default on
        await waitFor(() => {
            const emailToggle = screen.getByLabelText("task-assignment email channel") as HTMLInputElement;
            expect(emailToggle.checked).toBe(false);
        });
        expect((screen.getByLabelText("task-assignment inbox channel") as HTMLInputElement).checked).toBe(true);
        expect((screen.getByLabelText("sla-warning email channel") as HTMLInputElement).checked).toBe(true);

        fireEvent.click(screen.getByLabelText("task-assignment email channel"));
        await waitFor(() =>
            expect(calls).toContainEqual({
                method: "POST",
                path: "/api/v1/notifications/preferences",
                body: { category: "task-assignment", inbox: true, email: true },
            }),
        );
        expect(screen.getByRole("status").textContent).toContain("Preferences saved");
    });
});
