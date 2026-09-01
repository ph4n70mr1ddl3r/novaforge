import { describe, expect, it, vi, afterEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { act } from "react";

/**
 * The runtime entry's boot pin — the twin of the builder's (twenty-sixth pass),
 * which found the builder entry dead at module scope with zero test execution;
 * the runtime entry had the same hole until the twenty-eighth pass. This test
 * imports the REAL main.tsx against a jsdom #root and asserts the boot UI
 * renders — an entry that crashes at module scope fails here.
 *
 * The same pass fixed the entry's mount API and pins it here: the SPA is
 * client-only (the gateway-served index.html ships an EMPTY #root), so a boot
 * through hydrateRoot makes React 19 throw a hydration mismatch on every boot;
 * the thrown error escapes asynchronously and failed the whole suite under
 * load. The guard asserts the boot logs no hydration error — the createRoot
 * contract, deterministic instead of load-timing-lucky.
 */

describe("runtime entry boot", () => {
    afterEach(() => {
        vi.restoreAllMocks();
    });

    it("mounts the real entry module and renders the sign-in surface", async () => {
        const bootErrors: unknown[][] = [];
        vi.spyOn(console, "error").mockImplementation((...args: unknown[]) => {
            bootErrors.push(args);
        });
        document.body.innerHTML = '<div id="root"></div>';
        (window as { novaforge?: unknown }).novaforge = {
            issuer: "http://localhost:8082/realms/novaforge",
            base: "",
        };
        await act(async () => {
            await import("../src/main.tsx");
            await new Promise((resolve) => setTimeout(resolve, 20));
        });
        await waitFor(() => {
            const text = document.body.textContent ?? "";
            expect(text.length).toBeGreaterThan(0);
        });
        // whichever session state restoreSession resolves to (no live realm here,
        // so the error or sign-in surface), the boot UI rendered — not a dead root
        expect(document.querySelector("#root")?.innerHTML).not.toBe("");
        expect(screen.queryByRole("button", { name: "Sign in" }) ?? screen.getByRole("alert"))
            .toBeTruthy();
        // a client-only SPA has nothing to hydrate: any hydration mismatch logged
        // at boot means the entry reverted to hydrateRoot — the tree-regeneration
        // defect, deterministically
        const hydration = bootErrors.filter((args) =>
            args.some((a) => /hydrat|mismatch/i.test(String(a))),
        );
        expect(hydration).toEqual([]);
    });
});
