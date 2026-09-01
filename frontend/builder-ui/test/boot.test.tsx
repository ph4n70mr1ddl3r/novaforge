import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { act } from "react";

/**
 * The builder entry's boot pin (the golden journey's live blocker, found by the
 * twenty-sixth pass): the entry invoked Root() — a component FUNCTION — at
 * module scope inside the hydrateRoot tree, so useState ran outside any render
 * where React's dispatcher is null and the entire SPA died before painting the
 * sign-in screen. Nothing caught it: the gitignored built bundle had not been
 * rebuilt since the regression landed, and no test executed the entry. This
 * test imports the REAL main.tsx against a jsdom #root and asserts the boot
 * UI renders — an entry that crashes at module scope fails here.
 */

describe("builder entry boot", () => {
    it("mounts the real entry module and renders the sign-in surface", async () => {
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
            expect(text).toContain("NovaForge Builder");
        });
        // whichever session state restoreSession resolves to (no live realm here,
        // so the error or sign-in surface), the boot UI rendered — not a dead root
        expect(document.querySelector("#root")?.innerHTML).not.toBe("");
        expect(screen.queryByRole("button", { name: "Sign in" }) ?? screen.getByRole("alert"))
            .toBeTruthy();
    });
});
