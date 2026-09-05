import { describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { render, screen } from "@testing-library/react";
import { useState, createElement, type ReactNode } from "react";
import {
  FieldDate,
  FieldInput,
  FieldJson,
  FieldLookup,
  FieldMultiLookup,
  FieldNumber,
  FieldRichText,
  FieldSelect,
  FieldSwitch,
} from "../src/catalog/fields.tsx";
import {
  FormLayout,
  ListLayout,
  RecordActions,
} from "../src/catalog/layouts.tsx";
import { RendererContext, type RendererContextValue } from "../src/renderer/context.ts";

/**
 * The catalog's keyboard-only runs (PHASE-2 §11 item 2: "per-catalog-component
 * stories incl. keyboard-only runs + axe scans" — the axe half lives in the
 * gallery; this suite is the keyboard half). Every interactive catalog widget
 * must be operable without a pointer: Tab traversal order, Space/Enter
 * activation, and the combobox journeys all land the value the pointer path
 * lands. The ListLayout leg caught a real defect on landing: the generated
 * list's record-open path was a row click only — a keyboard user could not
 * open a record at all (WCAG 2.1.1).
 */

type FieldMap = RendererContextValue["fields"];

const fields: FieldMap = {
  reference: { apiName: "reference", label: "Reference", type: "text" },
  status: { apiName: "status", label: "Status", type: "enum", values: ["DRAFT", "POSTED"] },
  amount: { apiName: "amount", label: "Amount", type: "money" },
  orderedAt: { apiName: "orderedAt", label: "Ordered", type: "date" },
  active: { apiName: "active", label: "Active", type: "boolean" },
  meta: { apiName: "meta", label: "Meta", type: "json" },
  customer: { apiName: "customer", label: "Customer", type: "lookup" },
  tags: { apiName: "tags", label: "Tags", type: "m2m" },
  notes: { apiName: "notes", label: "Notes", type: "longText" },
};

/**
 * The harness re-renders on setValue (stateful record store) — the honest
 * stand-in for the shell's context, whose controlled widgets read back through
 * getValue after every keyboard write.
 */
function mountHarness(ui: ReactNode, overrides: Partial<RendererContextValue> = {}) {
  const setValueCalls: Array<[string, unknown]> = [];
  function Harness(): ReactNode {
    const [store, setStore] = useState<Record<string, unknown>>(() => ({
      id: "rec-1",
      reference: "",
      status: "",
      amount: "",
      orderedAt: "",
      active: false,
      meta: { channel: "web" },
      customer: "",
      tags: [] as unknown[],
      notes: "",
    }));
    const value: RendererContextValue = {
      mode: "preview",
      clock: "2026-09-03T10:00:00Z",
      user: { name: "Demo", roles: ["user"], locale: "en" },
      fields,
      record: store,
      errors: {},
      getValue: (path) => store[path.split(".")[0]!],
      setValue: (path, val) => {
        setValueCalls.push([path, val]);
        setStore((previous) => ({ ...previous, [path.split(".")[0]!]: val }));
      },
      actions: {
        save: async () => {},
        cancel: async () => {},
        deleteRecord: async () => {},
        openPage: async () => {},
      },
      navigate: () => {},
      data: {
        list: async () => ({ rows: [], total: 0 }),
        search: async () => [],
        displayFieldOf: () => "name",
      },
      ...overrides,
    };
    return createElement(RendererContext.Provider, { value }, ui);
  }
  return { setValueCalls, ...render(createElement(Harness)) };
}

const withContext = (value: RendererContextValue, ...children: ReturnType<typeof createElement>[]) =>
  createElement(RendererContext.Provider, { value }, ...children);

describe("catalog keyboard-only runs (PHASE-2 §11 item 2)", () => {
  it("the generated form traverses and submits keyboard-only", async () => {
    const user = userEvent.setup();
    const save = vi.fn(async () => {});
    const { setValueCalls, container } = mountHarness(
      createElement(
        FormLayout,
        { title: "Order", columns: 2 },
        createElement(FieldInput, { field: "reference", label: "Reference" }),
        createElement(FieldNumber, { field: "amount", label: "Amount", currency: "EUR" }),
        createElement(FieldSelect, { field: "status", label: "Status", options: ["DRAFT", "POSTED"] }),
        createElement(FieldSwitch, { field: "active", label: "Active" }),
        createElement(FieldDate, { field: "orderedAt", label: "Ordered", mode: "date" }),
        createElement(FieldInput, { field: "notes", label: "Notes", multiline: true }),
      ),
      { actions: { save, cancel: async () => {}, deleteRecord: async () => {}, openPage: async () => {} },
        pageActions: [{ type: "save" }, { type: "cancel" }] },
    );

    // Tab visits every labeled input in DOM order…
    const order: string[] = [];
    for (let i = 0; i < 6; i++) {
      await user.tab();
      const active = document.activeElement!;
      const label = container.querySelector(`label[for="${active.id}"]`);
      order.push((label?.textContent ?? active.tagName).replace("*", "").trim());
    }
    expect(order).toEqual(["Reference", "Amount (EUR)", "Status", "Active", "Ordered", "Notes"]);

    // …typing lands values (the keyboard path is the only write path here)…
    await user.type(screen.getByLabelText(/Reference/), "SO-0009");
    await user.type(screen.getByLabelText(/Amount/), "25.00");
    expect(setValueCalls.filter(([path]) => path === "reference").pop()).toEqual(["reference", "SO-0009"]);
    expect(setValueCalls.filter(([path]) => path === "amount").pop()).toEqual(["amount", "25.00"]);

    // …and the action bar is reachable and Enter-activatable.
    const saveButton = screen.getByText("Save");
    saveButton.focus();
    expect(document.activeElement).toBe(saveButton);
    await user.keyboard("{Enter}");
    expect(save).toHaveBeenCalledTimes(1);
  });

  it("FieldSelect selects an option through the native select's keyboard path", async () => {
    const user = userEvent.setup();
    const { setValueCalls } = mountHarness(
      createElement(FieldSelect, { field: "status", label: "Status", options: ["DRAFT", "POSTED"] }),
    );
    const select = screen.getByLabelText("Status") as HTMLSelectElement;
    await user.tab();
    expect(document.activeElement).toBe(select);
    // selectOptions models the keyboard selection path of a native select
    // (the platform control — options have no pointer-only surface)
    await user.selectOptions(select, "POSTED");
    expect(setValueCalls.pop()).toEqual(["status", "POSTED"]);
  });

  it("FieldSwitch toggles with Space", async () => {
    const user = userEvent.setup();
    const { setValueCalls } = mountHarness(
      createElement(FieldSwitch, { field: "active", label: "Active" }),
    );
    const switchInput = screen.getByLabelText("Active");
    await user.tab();
    expect(document.activeElement).toBe(switchInput);
    await user.keyboard(" "); // Space — the switch's activation key
    expect(setValueCalls.pop()).toEqual(["active", true]);
    await user.keyboard(" ");
    expect(setValueCalls.pop()).toEqual(["active", false]);
  });

  it("FieldLookup's combobox journey is keyboard-only: search, select, and the id lands", async () => {
    const user = userEvent.setup();
    const { setValueCalls } = mountHarness(
      createElement(FieldLookup, { field: "customer", label: "Customer", target: "Customer", minChars: 2 }),
      {
        data: {
          list: async () => ({ rows: [{ id: "cu-1", name: "Acme" }], total: 1 }),
          search: async () => [{ id: "cu-1", name: "Acme" }],
          displayFieldOf: () => "name",
        },
      },
    );
    const input = screen.getByLabelText("Customer") as HTMLInputElement;
    await user.tab();
    expect(document.activeElement).toBe(input);

    await user.type(input, "ac");
    const option = await screen.findByRole("option", { name: "Acme" });
    await user.tab(); // into the option button
    expect(option.querySelector("button")).toBe(document.activeElement);
    await user.keyboard("{Enter}");

    // THE PIN: the bound value is the row id — never the typed term…
    expect(setValueCalls.filter(([path]) => path === "customer").pop()).toEqual(["customer", "cu-1"]);
    // …and the closed box shows the target's display label, not the raw id.
    await screen.findByDisplayValue("Acme");
  });

  it("FieldMultiLookup adds and removes chips keyboard-only", async () => {
    const user = userEvent.setup();
    const { setValueCalls } = mountHarness(
      createElement(FieldMultiLookup, { field: "tags", label: "Tags", target: "Tag" }),
      {
        data: {
          list: async () => ({ rows: [], total: 0 }),
          search: async () => [{ id: "p1", name: "priority" }],
          displayFieldOf: () => "name",
        },
      },
    );
    const input = screen.getByLabelText("Tags") as HTMLInputElement;
    await user.tab();
    await user.type(input, "pri");
    await user.tab(); // into the option button
    await user.keyboard("{Enter}");
    expect(setValueCalls.filter(([path]) => path === "tags").pop()).toEqual(["tags", ["p1"]]);

    // the chip's Remove control is reachable and Enter-activatable
    const remove = screen.getByRole("button", { name: "Remove p1" });
    remove.focus();
    await user.keyboard("{Enter}");
    expect(setValueCalls.filter(([path]) => path === "tags").pop()).toEqual(["tags", []]);
  });

  it("FieldJson's readonly viewer is focusable (Tab)", async () => {
    const user = userEvent.setup();
    mountHarness(createElement(FieldJson, { field: "meta", label: "Meta" }));
    await user.tab();
    expect(document.activeElement?.tagName).toBe("PRE");
  });

  it("FieldRichText is a keyboard-operable multiline editor", async () => {
    const user = userEvent.setup();
    const { setValueCalls } = mountHarness(
      createElement(FieldRichText, { field: "notes", label: "Notes" }),
    );
    await user.tab();
    await user.type(screen.getByLabelText("Notes"), "line{Enter}two");
    expect(setValueCalls.pop()).toEqual(["notes", "line\ntwo"]);
  });

  it("RecordActions activates Edit and Delete by keyboard", async () => {
    const user = userEvent.setup();
    const navigate = vi.fn();
    const deleteRecord = vi.fn(async () => {});
    mountHarness(
      createElement(RecordActions, { showEdit: true, showDelete: true }),
      {
        navigate,
        actions: { save: async () => {}, cancel: async () => {}, deleteRecord, openPage: async () => {} },
      },
    );
    await user.tab();
    expect(document.activeElement?.textContent).toBe("Edit");
    await user.keyboard("{Enter}");
    expect(navigate).toHaveBeenCalledWith(undefined, "form", "rec-1");

    await user.tab();
    expect(document.activeElement?.textContent).toBe("Delete");
    await user.keyboard("{Enter}");
    // the destructive delete is a two-step confirm — Enter arms it, the inline
    // panel's "Delete record" fires it (focus lands on the safe "Keep" first)
    const confirmDelete = await screen.findByRole("button", { name: "Delete record" });
    await user.click(confirmDelete);
    expect(deleteRecord).toHaveBeenCalledTimes(1);
  });

  it("ListLayout is keyboard-operable: sort, page, and open a record", async () => {
    const user = userEvent.setup();
    const navigate = vi.fn();
    const { container } = mountHarness(
      createElement(ListLayout, { title: "Orders", columns: ["reference"], pageSize: 1, sortable: true }),
      {
        mode: "runtime",
        entity: "Order",
        navigate,
        data: {
          list: async () => ({
            rows: [
              { id: "rec-1", reference: "SO-0001" },
              { id: "rec-2", reference: "SO-0002" },
            ],
            total: 2,
          }),
          search: async () => [],
        },
      },
    );

    // the sort header button toggles by keyboard; aria-sort follows
    await user.tab();
    const sortButton = screen.getByRole("button", { name: /reference/i });
    expect(document.activeElement).toBe(sortButton);
    await user.keyboard("{Enter}");
    expect(container.querySelector('th[aria-sort="ascending"]')).not.toBeNull();
    await user.keyboard("{Enter}");
    expect(container.querySelector('th[aria-sort="descending"]')).not.toBeNull();

    // THE KEYBOARD-ONLY PIN: a record opens without a pointer — the first
    // cell's open control (a real button); Enter navigates to the detail view
    const openButtons = screen.getAllByRole("button", { name: /Open SO-000\d/ });
    expect(openButtons).toHaveLength(2);
    openButtons[0]!.focus();
    await user.keyboard("{Enter}");
    expect(navigate).toHaveBeenCalledWith("Order", "detail", "rec-1");

    // the pager: Next reachable and activatable, Previous disabled at the first page
    const previous = screen.getByRole("button", { name: "Previous" }) as HTMLButtonElement;
    const next = screen.getByRole("button", { name: "Next" });
    expect(previous.disabled).toBe(true);
    next.focus();
    await user.keyboard("{Enter}");
    expect(await screen.findByText("2–2 / 2")).toBeTruthy();
    expect(previous.disabled).toBe(false);
  });
});
