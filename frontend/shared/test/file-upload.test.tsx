import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, waitFor } from "@testing-library/react";
import axe from "axe-core";
import { FileUpload } from "../src/catalog/FileUpload.tsx";

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
