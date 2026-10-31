// The server for the browser tests: its fat JAR in test mode, with a database of its own, so nothing here is the owner's.
import { spawn } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const dataDir = mkdtempSync(join(tmpdir(), "decrastination-e2e-"));
const server = spawn("java", ["-jar", "../server/build/libs/server-all.jar"], {
  stdio: "inherit",
  env: { ...process.env, DECRASTINATION_TEST: "1", DOMAIN: "", DATA_DIR: dataDir, PORT: "8090" },
});
server.on("exit", (code) => process.exit(code ?? 0));
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => server.kill());
