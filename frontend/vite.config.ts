import { loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { defineConfig as defineVitestConfig } from "vitest/config";

export default defineVitestConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "VITE_");
  return {
    plugins: [react()],
  server: {
    host: true,
      proxy: {
      "/api": env.VITE_API_PROXY_TARGET ?? "http://localhost:8080",
      "/oauth2": env.VITE_API_PROXY_TARGET ?? "http://localhost:8080",
      "/login": env.VITE_API_PROXY_TARGET ?? "http://localhost:8080",
      "/logout": env.VITE_API_PROXY_TARGET ?? "http://localhost:8080",
      },
    },
    test: { environment: "jsdom" },
  };
});
