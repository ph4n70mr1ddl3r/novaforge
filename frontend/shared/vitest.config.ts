import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "jsdom",
    // globals on: @testing-library/react registers its automatic afterEach cleanup
    // against the global hook — without it, renders leak across assertions
    globals: true,
    include: ["test/**/*.test.tsx", "test/**/*.test.ts"],
  },
});
