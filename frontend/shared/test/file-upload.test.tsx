import { describe, expect, it, vi } from "vitest";
import { createElement } from "react";
import { fireEvent, render, waitFor } from "@testing-library/react";
import axe from "axe-core";
import { FileUpload } from "../src/catalog/FileUpload.tsx";
import { PageRenderer } from "../src/renderer/renderer.ts";

// jsdom has no fetch against a real File Service — the grant/upload/complete legs
// are stubbed; the component's state machine (busy → attached | failed) is the test.
const grant = { id: "att-1", uploadUrl: "http://storage/presigned/put", expiresAt: "soon", method: "PUT" as const };
const completion = { id: "att-1", virusScan: "clean", checksum: "abc", size: 5 };

function stubFetch() {
  const calls: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: string | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push(`${init?.method ?? "GET"} ${url}`);
      if (url.endsWith("/api/v1/files/uploads")) {
        return new Response(JSON.stringify(grant), { status: 200 });
      }
      if (url.includes("/presigned/put")) {
        return new Response(null, { status: 200 });
      }
      if (url.endsWith("/api/v1/files/att-1/complete")) {
        return new Response(JSON.stringify(completion), { status: 200 });
      }
      return new Response("{}", { status: 404 });
    }),
  );
  // deterministic checksum digest (the crypto leg is not under test)
  vi.stubGlobal(
    "crypto",
    { subtle: { digest: async () => new ArrayBuffer(32) } },
  );
  return calls;
}


describe("renderer wiring (2026-08-31, fourteenth pass)", () => {
    it("a novaforge.file-upload node receives the context's token leg and binds the id back to the record", async () => {
        const calls: string[] = [];
        vi.stubGlobal(
            "fetch",
            vi.fn(async (input: string | URL, init?: RequestInit) => {
                const url = String(input);
                calls.push(`${(init?.headers as Record<string, string>)["Authorization"] ?? "-"} ${url}`);
                if (url.endsWith("/api/v1/files/uploads")) {
                    return new Response(JSON.stringify(grant), { status: 200 });
                }
                if (url.includes("/presigned/put")) {
                    return new Response(null, { status: 200 });
                }
                if (url.endsWith("/api/v1/files/att-1/complete")) {
                    return new Response(JSON.stringify(completion), { status: 200 });
                }
                return new Response("{}", { status: 404 });
            }),
        );
        vi.stubGlobal("crypto", { subtle: { digest: async () => new ArrayBuffer(32) } });

        const values: Record<string, unknown> = {};
        const page = {
            apiName: "p",
            type: "form" as const,
            entity: "E",
            model: {
                base: "auto" as const,
                kind: "form" as const,
                root: {
                    type: "novaforge.file-upload",
                    key: "u1",
                    bind: "invoice",
                    version: "1.0.0",
                    props: { entity: "E" },
                },
                actions: [],
            },
        };
        render(createElement(PageRenderer, {
            page,
            entity: {
                apiName: "E",
                label: "E",
                displayField: undefined,
                fields: [{ apiName: "invoice", type: "text" as const }],
                relationships: [],
                validations: [],
                hooks: [],
                indexes: [],
            },
            context: {
                mode: "runtime",
                clock: "2026-08-31T00:00:00.000Z",
                user: { name: "u", roles: [] },
                fields: { invoice: { apiName: "invoice", type: "text" } },
                record: { invoice: null },
                errors: {},
                actions: {
                    save: async () => {},
                    cancel: async () => {},
                    deleteRecord: async () => {},
                    openPage: async () => {},
                },
                navigate: () => {},
                getValue: (path: string) => values[path],
                setValue: (path: string, value: unknown) => {
                    values[path] = value;
                },
                files: { base: "", token: () => "live-token" },
            },
        }));

        // the catalog component is lazy — wait for the suspension to resolve
        let input = null as HTMLInputElement | null;
        await waitFor(() => {
            input = document.querySelector('input[type="file"]') as HTMLInputElement | null;
            expect(input).not.toBeNull();
        });
        const uploadFile = new File(["hello"], "x.txt", { type: "text/plain" });
        // jsdom's File lacks arrayBuffer in some versions — the component hashes it
        uploadFile.arrayBuffer = async () => new TextEncoder().encode("hello").buffer as ArrayBuffer;
        fireEvent.change(input!, { target: { files: [uploadFile] } });
        await waitFor(() => expect(calls.length).toBeGreaterThanOrEqual(3));
        // both authorized legs carried the context's live token
        expect(calls.filter((call) => call.startsWith("Bearer live-token"))).toHaveLength(2);
        // the completion bound the attachment id back to the record field
        await waitFor(() => expect(values["invoice"]).toBe("att-1"));
    });
});

declare module "vitest" {
  interface Assertion<T> {
    toHaveNoViolations(): T;
  }
}

expect.extend({
  toHaveNoViolations(received: {
    violations: Array<{ id: string; help: string; nodes: unknown[] }>;
  }) {
    return {
      pass: received.violations.length === 0,
      message: () =>
        `${received.violations.length} axe violations: ` +
        received.violations
          .map((v: { id: string; nodes: unknown[]; help: string }) =>
            `${v.id} (${v.nodes.length} nodes): ${v.help}`)
          .join("; "),
    };
  },
});

function pickFile(): File {
  const file = new File(["bytes"], "invoice.pdf", { type: "application/pdf" });
  // jsdom's File lacks arrayBuffer (the browser ships it) — the digest leg is not
  // under test, so the instance carries a deterministic stand-in
  Object.assign(file, { arrayBuffer: async () => new ArrayBuffer(8) });
  return file;
}

describe("FileUpload (PHASE-6 §8 — the file field's upload path)", () => {
  it("rides the presigned flow: grant → PUT → complete with checksum", async () => {
    const calls = stubFetch();
    const uploaded = vi.fn();
    const { container } = render(
      <FileUpload filesBase="http://files" bearerToken="tok" onUploaded={uploaded} />,
    );
    fireEvent.change(container.querySelector("input[type=file]")!, {
      target: { files: [pickFile()] },
    });
    await waitFor(() => expect(uploaded).toHaveBeenCalledWith("att-1", "clean"));
    expect(calls).toEqual([
      "POST http://files/api/v1/files/uploads",
      "PUT http://storage/presigned/put",
      "POST http://files/api/v1/files/att-1/complete",
    ]);
    expect(container.textContent).toContain("att-1 · clean");
    expect(await axe.run(container, {})).toHaveNoViolations();
    vi.unstubAllGlobals();
  });

  it("surfaces a rejected completion as an alert, never silently", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("{}", { status: 400 })),
    );
    vi.stubGlobal("crypto", { subtle: { digest: async () => new ArrayBuffer(32) } });
    const { container } = render(<FileUpload filesBase="http://files" />);
    fireEvent.change(container.querySelector("input[type=file]")!, {
      target: { files: [pickFile()] },
    });
    await waitFor(() =>
      expect(container.querySelector('[role="alert"]')?.textContent)
        .toContain("upload grant failed"),
    );
    vi.unstubAllGlobals();
  });
});
