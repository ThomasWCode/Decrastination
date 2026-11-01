import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { request } from "@playwright/test";

const STORES = ["tasks.json", "settings.json", "session.json", "log.json", "instructions.json", "school.json", "model.json", "learning.json"];

const HOUR = 3_600_000;

/**
 * A made-up chat, about now: what's new, what waits, what he looked up and his cards, over the month the archive holds. All of it's
 * made up here, as the fixtures' times would be weeks old.
 */
function chatFrom(fixture: string, now: number) {
  const his = (id: string, hoursAgo: number, text: string, more: object = {}) => ({ id, from: "Claude", text, at: now - hoursAgo * HOUR, ...more });
  const yours = (id: string, hoursAgo: number, text: string, more: object = {}) => ({ id, from: "You", text, at: now - hoursAgo * HOUR, answeredAt: now - hoursAgo * HOUR, ...more });
  const question = (id: string, hoursAgo: number, which: string) =>
    his(id, hoursAgo, `Can you work through the ${which} made-up event?`, {
      kind: "EventQuestion",
      byApp: true,
      about: { eventKey: `made-up-${which}`, eventTitle: `The ${which} made-up event`, eventStart: now + 72 * HOUR },
      suggestedReplies: ["All of it", "A few hours", "None"],
    });
  const chat = {
    ...JSON.parse(fixture),
    readAt: now - 3 * HOUR,
    messages: [
      yours("seed-reply", 40, "An answer to something long ago", { replyTo: "seed-old-asked" }),
      yours("seed-week", 39, "How's the week looking?"),
      his("seed-his", 38, "Busy, but it fits.", {
        feeling: "Glad to have good news",
        unsent: ["Full, but fine."],
        searched: ["made-up words"],
        sources: [
          { title: "A made-up page", url: "https://example.com/made-up" },
          { title: "Not a page to follow", url: "javascript:void(0)" },
        ],
      }),
      his("seed-shows", 37, "Here's the one to look at.", { proposals: [{ id: "seed-card", open: "own:seed" }] }),
      question("seed-first", 30, "first"),
      question("seed-second", 29, "second"),
      his("seed-new", 2, "A new word for you"),
    ],
  };
  const at = now - 960 * HOUR;
  const month = new Date(at).toISOString().slice(0, 7);
  const archive = {
    version: 12,
    messages: [
      { id: "seed-old-word", from: "Claude", text: "An old word of his", at: at, kind: "Morning", suggestedReplies: ["Yes"] },
      { id: "seed-old-asked", from: "Claude", text: "An old question of his", at: at + HOUR },
    ],
  };
  return [
    { name: "chat.json", version: chat.version, text: JSON.stringify(chat) },
    { name: `chat-archive-${month}.json`, version: archive.version, text: JSON.stringify(archive) },
  ];
}

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
  const given = STORES.map((name) => {
    const text = readFileSync(join(shape, name), "utf-8");
    return { name, version: JSON.parse(text).version ?? 1, text };
  });
  // A task of your own for his card to show, with the fixture's as they are.
  const tasks = given.find((store) => store.name === "tasks.json");
  if (!tasks) throw new Error("No tasks.json");
  const held = JSON.parse(tasks.text);
  const now = Date.now();
  held.tasks = [...held.tasks, { id: "own:seed", source: "Own", sourceId: "seed", title: "A task he shows you", kind: "Homework", firstSeenAt: now, lastSeenAt: now, status: "Open" }];
  const stores = [...given.filter((store) => store !== tasks), { ...tasks, text: JSON.stringify(held) }, ...chatFrom(readFileSync(join(shape, "chat.json"), "utf-8"), now)];
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
    // A made-up parent code, as the phone would send it: RFC 6238's own, which the test works out.
    const code = await api.post("/api/parent-code", {
      headers: { Authorization: `Bearer ${token}`, "X-Protocol": "1" },
      data: { secret: "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" },
    });
    if (!code.ok()) throw new Error(`The parent code wasn't taken: ${code.status()}`);
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
