import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    host: true,
    // 本機開發時把 /api 轉發給 api 模組，避免開發模式下的 CORS 問題
    // （見 add-job-dashboard/design.md D1 的 dashboard-frontend CORS 決策）
    proxy: {
      "/api": {
        target: "http://localhost:8083",
        changeOrigin: true,
      },
    },
  },
  build: {
    sourcemap: mode === "development",
  },
  base: "./",
}));
