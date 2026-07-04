import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The plugin entry (plugin.ts) must be a self-contained classic script that
// Penpot loads inside its SES sandbox, while index.html is a regular React app
// served in the plugin iframe. Both are built to dist/ and served by
// `vite preview` on port 4500 (same pattern as mcp/packages/plugin).
export default defineConfig({
  base: "./",
  plugins: [react()],
  build: {
    // plugin.ts is bundled separately by esbuild into a single classic script
    // (Penpot's SES loader cannot follow ES module imports); vite only builds
    // the iframe UI.
    rollupOptions: {
      input: { index: "./index.html" },
    },
    target: "es2022",
    emptyOutDir: false,
  },
  preview: {
    port: 4500,
    cors: true,
  },
});
