import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "jsdom",
    globals: true,
    include: ["test/**/*.test.tsx", "test/**/*.test.ts"],
    // 15 s (2026-09-03 review): the 5 s default flaked under `pnpm -r test`
    // parallelism — green in isolation, red under load with varying tests.
    testTimeout: 15000,
  },
});
