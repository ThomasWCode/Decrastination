import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// `/api` is the server on one origin in production (Caddy) and here, so the session is an ordinary cookie and there's no CORS.
const api = { "/api": { target: process.env.API_TARGET ?? "http://127.0.0.1:8090", changeOrigin: false } };

// The Caddyfile's, so the browser tests run under what the live site sends.
const csp = "default-src 'self'; img-src 'self' data: blob:; connect-src 'self'; base-uri 'self'; frame-ancestors 'none'";

export default defineConfig({
  plugins: [react()],
  server: { host: "127.0.0.1", port: 4175, strictPort: true, proxy: api },
  preview: { host: "127.0.0.1", port: 4175, strictPort: true, proxy: api, headers: { "Content-Security-Policy": csp } },
  test: { environment: "jsdom", include: ["src/**/*.test.{ts,tsx}"] },
});
