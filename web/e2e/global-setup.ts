import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { request } from "@playwright/test";

const STORES = ["tasks.json", "settings.json", "session.json", "log.json", "instructions.json", "school.json", "model.json", "learning.json"];

/** Signs in, pairs a phone and sends the newest made-up stores, as the phone would, so the pages have something to show. */
async function seeded(server: string, sessionFile: string, commands: boolean) {
  const api = await request.newContext({ baseURL: server });
  const signedIn = await api.post("/api/test/signin");
  if (!signedIn.ok()) throw new Error(`Test sign-in failed: ${signedIn.status()}`);
  const headers = { "X-Requested-With": "test" };
  const { code } = await (await api.post("/api/devices/pair", { headers })).json();
  const { token } = await (await api.post("/api/devices/claim", { data: { code, name: "Test phone" } })).json();
  const root = join("..", "fixtures", "stores");
  const newest = readdirSync(root).sort().at(-1);
  if (!newest) throw new Error("No fixtures/stores/<date>/");
  const shape = join(root, newest);
  const stores = STORES.map((name) => {
    const text = readFileSync(join(shape, name), "utf-8");
    return { name, version: JSON.parse(text).version ?? 1, text };
  });
  const seeded = await api.post("/api/stores/seed", {
    headers: { Authorization: `Bearer ${token}`, "X-Protocol": "1" },
    data: { zone: "Europe/London", build: "e2e", stores },
  });
  if (!seeded.ok()) throw new Error(`Seeding failed: ${seeded.status()}`);
  const answer = await seeded.json();
  if (answer.refused.length > 0) throw new Error(`Stores refused: ${answer.refused}`);
  if (commands) {
    const over = await api.post("/api/test/commands");
    if (!over.ok()) throw new Error(`Going over to commands failed: ${over.status()}`);
  }
  // The session for the browser: the same cookie on the website's own origin.
  const state = await api.storageState();
  const cookies = state.cookies.map((c) => ({ ...c, domain: "127.0.0.1" }));
  writeFileSync(sessionFile, JSON.stringify({ cookies, origins: [] }));
  await api.dispose();
}

/** Each server's its own: one mirroring, as the site is till the switch, and one applying commands. */
export default async function globalSetup() {
  await seeded("http://127.0.0.1:8090", "e2e/.state.json", false);
  await seeded("http://127.0.0.1:8091", "e2e/.state-writes.json", true);
}
