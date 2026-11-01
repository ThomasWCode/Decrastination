// The server for the browser tests: its fat JAR in test mode, with a database of its own, so nothing here is the owner's.
import { spawn } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const dataDir = mkdtempSync(join(tmpdir(), "decrastination-e2e-"));
const server = spawn("java", ["-jar", "../server/build/libs/server-all.jar"], {
  stdio: "inherit",
  // A made-up key, for the stand-in: a test can't reach Claude, whatever's in this machine's environment.
  env: { ...process.env, DECRASTINATION_TEST: "1", DOMAIN: "", DATA_DIR: dataDir, PORT: process.argv[2] ?? "8090", ANTHROPIC_API_KEY: "a made-up key", CLAUDE_STAND_IN: "http://127.0.0.1:8092" },
});
server.on("exit", (code) => process.exit(code ?? 0));
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => server.kill());
