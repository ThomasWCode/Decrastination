import { defineConfig, devices } from "@playwright/test";

const browser = { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } };

// The website against the real server, run from its fat JAR in test mode with the made-up stores in
// fixtures/ (e2e/global-setup.ts). Two of each: reading against a server that mirrors, as the site does
// till the switch, and writing against one that applies commands, each with a database of its own.
// Build the JAR first: ./gradlew :server:buildFatJar
export default defineConfig({
  testDir: "e2e",
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  globalSetup: "./e2e/global-setup.ts",
  use: {
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: [
    {
      command: "node e2e/start-server.mjs 8090",
      url: "http://127.0.0.1:8090/api/health",
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
    {
      command: "node e2e/start-server.mjs 8091",
      url: "http://127.0.0.1:8091/api/health",
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
    {
      command: "npm run preview",
      url: "http://127.0.0.1:4175/",
      reuseExistingServer: !process.env.CI,
    },
    {
      command: "npx vite preview --port 4176 --strictPort",
      url: "http://127.0.0.1:4176/",
      env: { API_TARGET: "http://127.0.0.1:8091" },
      reuseExistingServer: !process.env.CI,
    },
  ],
  projects: [
    { name: "reading", testIgnore: "writes.spec.ts", use: { ...browser, baseURL: "http://127.0.0.1:4175", storageState: "e2e/.state.json" } },
    { name: "writing", testMatch: "writes.spec.ts", use: { ...browser, baseURL: "http://127.0.0.1:4176", storageState: "e2e/.state-writes.json" } },
  ],
});
