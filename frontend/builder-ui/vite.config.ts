import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

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
    port: 5174,
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
});
