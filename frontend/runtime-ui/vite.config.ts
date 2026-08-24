import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Static bundle behind the gateway (PHASE-2 §13 Q5): the dev proxy covers local
// development — same origin in every other environment.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
});
