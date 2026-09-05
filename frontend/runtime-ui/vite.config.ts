import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Static bundle behind the gateway (PHASE-2 §13 Q5): the dev proxy covers local
// development — same origin in every other environment.
export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          // framework + data layer as their own cacheable chunk: app code ships
          // far more often than react/react-dom/react-query do (the app imports
          // react-dom/client — the exact id must be listed, not the package)
          react: ["react", "react-dom/client", "@tanstack/react-query"],
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
});
