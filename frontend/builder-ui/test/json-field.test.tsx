import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { createElement } from "react";
import { JsonTextField } from "../src/json-field.tsx";

/**
 * The JSON inputs' keep-typing contract (eighteenth pass): the four authoring
 * fields (flow params, job params, webhook/import mappings) parsed on every
 * keystroke — typing `{` threw in the change handler, the keystroke never
 * committed, and valid JSON could only ever be pasted.
 */
describe("JsonTextField", () => {
    it("keeps typing through an incomplete literal — the text stays, the model lags", () => {
        const onParsed = vi.fn();
        render(createElement(JsonTextField, {
            "aria-label": "params",
            placeholder: "{}",
            value: { hook: "nightly" },
            onParsed,
        }));
        const input = screen.getByLabelText("params") as HTMLInputElement;
        // the committed model round-trips into the box
        expect(input.value).toBe('{"hook":"nightly"}');
        fireEvent.change(input, { target: { value: "{" } });
        // the keystroke SURVIVES (the old pattern snapped back or threw)
        expect(input.value).toBe("{");
        expect(onParsed).not.toHaveBeenCalled();
        // completing the literal commits it
        fireEvent.change(input, { target: { value: '{"hook":"nightly","x":1}' } });
        expect(onParsed).toHaveBeenCalledWith({ hook: "nightly", x: 1 });
    });

    it("commits objects only — a valid-JSON scalar or array never lands in the model", () => {
        // Anti-regression (re-audit): JSON.parse happily returns 5 / [1,2] / "x" —
        // cast blindly, the job-params and mapping fields silently carried
        // non-record shapes their consumers spread and Object.keys over
        const onParsed = vi.fn();
        render(createElement(JsonTextField, {
            "aria-label": "params",
            placeholder: "{}",
            value: { a: 1 },
            onParsed,
        }));
        const input = screen.getByLabelText("params") as HTMLInputElement;
        for (const scalar of ["5", "[1,2]", '"x"', "true"]) {
            fireEvent.change(input, { target: { value: scalar } });
            expect(onParsed).not.toHaveBeenCalled();
        }
        fireEvent.change(input, { target: { value: '{"a":2}' } });
        expect(onParsed).toHaveBeenCalledWith({ a: 2 });
    });

    it("empties the model when the text empties", () => {
        const onParsed = vi.fn();
        render(createElement(JsonTextField, {
            "aria-label": "params",
            placeholder: "{}",
            value: { a: 1 },
            onParsed,
        }));
        fireEvent.change(screen.getByLabelText("params"), { target: { value: "" } });
        expect(onParsed).toHaveBeenCalledWith(undefined);
    });

    it("re-seeds when the model changes externally (a reload), but not for its own commits", () => {
        const onParsed = vi.fn();
        const { rerender } = render(createElement(JsonTextField, {
            "aria-label": "params",
            placeholder: "{}",
            value: { a: 1 },
            onParsed,
        }));
        const input = screen.getByLabelText("params") as HTMLInputElement;
        // our own commit: same serialization — the text keeps the user's formatting
        fireEvent.change(input, { target: { value: '{"a":2}' } });
        expect(onParsed).toHaveBeenLastCalledWith({ a: 2 });
        // an external reset to different content re-seeds the box
        rerender(createElement(JsonTextField, {
            "aria-label": "params",
            placeholder: "{}",
            value: { b: 9 },
            onParsed,
        }));
        expect((screen.getByLabelText("params") as HTMLInputElement).value).toBe('{"b":9}');
    });
});
