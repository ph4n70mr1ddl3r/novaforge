import { describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { ApiError, type AppDefinition, type EntityDefinition } from "@novaforge/shared";
import { EntityBuilder } from "../src/entity-builder.tsx";

/** The entity builder (PHASE-2 T7): create/modify entities incl. relationships through the UI. */

const app: AppDefinition = {
    apiName: "Erp",
    id: "app-1",
    entities: [
        {
            apiName: "Customer",
            label: "Customer",
            module: "Sales",
            displayField: "name",
            fields: [
                { apiName: "name", type: "text", required: true },
                { apiName: "region", type: "enum", values: ["EU", "US"] },
            ],
            relationships: [],
            validations: [],
            hooks: [],
            indexes: [],
        },
    ],
    pages: [],
    permissionSet: { roles: [], objectPermissions: [], fieldSecurity: [] },
    stateMachines: [],
    reports: [],
    dashboards: [],
    translations: [],
};

describe("EntityBuilder (T7)", () => {
    it("lists entities and edits a field through the grid", async () => {
        const onSave = vi.fn<(entity: import("@novaforge/shared").EntityDefinition) => Promise<void>>(async () => {});
        render(createElement(EntityBuilder, { app, appId: "app-1", onSave, onDelete: async () => {} }));
        expect(screen.getByRole("button", { name: "Customer" })).toBeTruthy();
        // the fields grid shows the authored fields with type pickers + constraints
        expect((screen.getByLabelText("apiName row 0") as HTMLInputElement).value).toBe("name");
        expect((screen.getByLabelText("type row 1") as HTMLSelectElement).value).toBe("enum");
        expect((screen.getByLabelText("values row 1") as HTMLInputElement).value).toBe("EU,US");
        // edit the label and save — the UI drives the Metadata API, never by hand
        fireEvent.change(screen.getByLabelText("label row 0"), { target: { value: "Full name" } });
        screen.getByRole("button", { name: "Save entity" }).click();
        await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
        const saved = onSave.mock.calls[0]![0] as EntityDefinition;
        expect(saved.fields[0]!.label).toBe("Full name");
    });

    it("surfaces validation failures from the platform problem body", async () => {
        const onSave = vi.fn<(entity: import("@novaforge/shared").EntityDefinition) => Promise<void>>(async () => {
            throw new ApiError(400, {
                type: "x", title: "Validation failed", status: 400, code: "4000",
                errors: [{ field: "apiName", message: "entity apiName must be PascalCase" }],
            });
        });
        render(createElement(EntityBuilder, { app, appId: "app-1", onSave, onDelete: async () => {} }));
        screen.getByRole("button", { name: "Save entity" }).click();
        await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
        expect(screen.getByRole("alert").textContent).toContain("Validation failed");
    });

    it("a new entity starts from the seeded draft — never the previously edited entity's fields (found live, golden-journey run)", async () => {
        const onSave = vi.fn<(entity: import("@novaforge/shared").EntityDefinition) => Promise<void>>(async () => {});
        const { rerender } = render(createElement(EntityBuilder, { app, appId: "app-1", onSave, onDelete: async () => {} }));
        // edit Customer's first field label, then start a new entity (act-wrapped:
        // the editor's draft-reset effect must flush before the next keystroke —
        // exactly the browser's guarantee)
        fireEvent.change(screen.getByLabelText("label row 0"), { target: { value: "Full name" } });
        await act(async () => {
            screen.getByRole("button", { name: "New entity" }).click();
        });
        fireEvent.change(screen.getByLabelText("Entity apiName"), { target: { value: "Supplier" } });
        screen.getByRole("button", { name: "Save entity" }).click();
        await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
        const saved = onSave.mock.calls[0]![0] as EntityDefinition;
        // the seeded single `name` field — not Customer's grid, not its label edit
        expect(saved.fields).toEqual([{ apiName: "name", type: "text", required: true }]);
        // and the display field rides the seeded field — an empty one fails
        // save-validation ("must name an existing field"), found live
        expect(saved.displayField).toBe("name");

        // and the app swap (the shell loads a different app after onboarding): the
        // shell keys the builder by app id, so the swap remounts — never carrying
        // the previous app's entities[0] as the selected editor
        const other: typeof app = { ...app, id: "app-2", apiName: "Other", entities: [] };
        rerender(createElement(EntityBuilder, { key: "app-2", app: other, appId: "app-2", onSave, onDelete: async () => {} }));
        expect(screen.getByText("Select or create an entity.")).toBeTruthy();
    });

    it("adds a new entity with the wizard shape (PascalCase + first field)", async () => {
        const onSave = vi.fn<(entity: import("@novaforge/shared").EntityDefinition) => Promise<void>>(async () => {});
        render(createElement(EntityBuilder, { app, appId: "app-1", onSave, onDelete: async () => {} }));
        // act-wrapped: the editor's draft-reset effect flushes before the next
        // keystroke — the browser's guarantee, made explicit here
        await act(async () => {
            screen.getByRole("button", { name: "New entity" }).click();
        });
        fireEvent.change(screen.getByLabelText("Entity apiName"), { target: { value: "Supplier" } });
        screen.getByRole("button", { name: "Save entity" }).click();
        await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
        expect((onSave.mock.calls[0]![0] as EntityDefinition).apiName).toBe("Supplier");
    });
});
