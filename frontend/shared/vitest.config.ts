import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "jsdom",
    // globals on: @testing-library/react registers its automatic afterEach cleanup
    // against the global hook — without it, renders leak across assertions
    globals: true,
    setupFiles: ["./test/canvas-stub.ts"],
    include: ["test/**/*.test.tsx", "test/**/*.test.ts"],
    // 15 s (2026-09-03 review): the 5 s default flaked under `pnpm -r test`
    // parallelism on a loaded runner — suites green in isolation red under load
    // (varying tests per run, "Test timed out in 5000ms"). The journeys render
    // real component trees against stubbed APIs; 15 s keeps CI honest without
    // masking a genuine hang.
    testTimeout: 15000,
  },
});
