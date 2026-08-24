import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { PlatformClient } from "@novaforge/shared";
import { Templates } from "../src/templates.tsx";

/**
 * The template catalog (PHASE-8 §6): the listing (name/publisher/version/
 * description — no commerce) renders in the builder, and install creates a new
 * draft app through the §6 API — no app context needed, the first app in a fresh
 * workspace installs from here.
 */

function stubClient() {
    const fetchImpl = vi.fn(async (input: string | URL, init?: RequestInit) => {
        const url = String(input);
        const method = (init?.method ?? "GET").toUpperCase();
        if (method === "GET" && url.includes("/metadata/templates")) {
            return new Response(JSON.stringify([
                { id: "tpl-1", name: "ERP", publisher: "NovaForge", version: "3", description: "the dogfood" },
                { id: "tpl-2", name: "A/R Starter", publisher: "Acme", version: "1", description: "" },
            ]), { status: 200, headers: { "Content-Type": "application/json" } });
        }
        if (method === "POST" && url.includes("/templates/tpl-1/install")) {
            expect(init?.body ? JSON.parse(String(init.body)) : {}).toEqual({});
            return new Response(JSON.stringify({ apiName: "erp2", id: "app-9" }), {
                status: 200, headers: { "Content-Type": "application/json" } });
        }
        return new Response(JSON.stringify({ title: "not stubbed", status: 404 }), { status: 404 });
    });
    return { client: new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch), fetchImpl };
}

describe("Templates (PHASE-8 §6)", () => {
    it("lists the catalog and installs a template as a new draft app", async () => {
        const { client } = stubClient();
        const onInstalled = vi.fn();
        render(createElement(Templates, { client, onInstalled }));

        expect(await screen.findByText("ERP")).toBeTruthy();
        expect(screen.getByText("NovaForge")).toBeTruthy();
        expect(screen.getByText("A/R Starter")).toBeTruthy();

        fireEvent.click(screen.getByRole("button", { name: "Install ERP" }));
        await waitFor(() => expect(onInstalled).toHaveBeenCalledWith("erp2"));
        expect(await screen.findByText(/Installed 'ERP' as a new draft app/)).toBeTruthy();
    });

    it("surfaces a failed install accessibly", async () => {
        const fetchImpl = vi.fn(async () =>
            new Response(JSON.stringify({ title: "template gone", status: 404 }), { status: 404 }));
        const client = new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);
        render(createElement(Templates, { client }));
        await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
    });
});
