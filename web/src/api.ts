import type { Action, Asked, ChatView, DayView, DeviceLine, NoticesView, Outcome, PairingCode, PhotoRead, SettingsView, TaskAsk, TaskView, WorkView } from "./types";

/** A request the server answered with a refusal. */
export class ApiError extends Error {
  constructor(readonly status: number) {
    super(`The server answered ${status}`);
  }
}

// A cross-site form can't send this header: the server needs it on whatever changes something.
const CHANGING = { "X-Requested-With": "decrastination" };

const UNREACHED = "That didn't reach the server. Try again.";

async function read<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { credentials: "same-origin", ...init });
  if (!response.ok) throw new ApiError(response.status);
  return (response.status === 204 ? undefined : await response.json()) as T;
}

function posting(body: unknown): RequestInit {
  return { method: "POST", headers: { ...CHANGING, "Content-Type": "application/json" }, body: JSON.stringify(body) };
}

export const api = {
  me: () => read<{ signedIn: boolean }>("/api/me"),
  day: (date: string | null) => read<DayView>(`/api/views/day${date ? `?date=${encodeURIComponent(date)}` : ""}`),
  work: () => read<WorkView>("/api/views/work"),
  chat: (earlier: number, reach: string | null) => read<ChatView>(`/api/views/chat?earlier=${earlier}${reach ? `&reach=${encodeURIComponent(reach)}` : ""}`),
  notices: () => read<NoticesView>("/api/views/notices"),
  task: (id: string) => read<TaskView>(`/api/views/task/${encodeURIComponent(id)}`),
  settings: () => read<SettingsView>("/api/views/settings"),
  setCredentials: (given: Record<string, string>) => read<{ held: string[] }>("/api/credentials", posting(given)),
  devices: () => read<DeviceLine[]>("/api/devices"),
  pair: () => read<PairingCode>("/api/devices/pair", { method: "POST", headers: CHANGING }),
  revoke: (id: string) => read<undefined>(`/api/devices/${encodeURIComponent(id)}`, { method: "DELETE", headers: CHANGING }),
  signOut: () => read<{ signedIn: boolean }>("/api/auth/logout", { method: "POST", headers: CHANGING }),
};

/** [jpeg], a photo of homework, read into tasks to check: a call to Claude, so only at your asking. */
export function readPhoto(jpeg: Blob): Promise<PhotoRead> {
  return read<PhotoRead>("/api/photos/tasks", { method: "POST", headers: { ...CHANGING, "Content-Type": "image/jpeg" }, body: jpeg });
}

/** [action] as a command, made here: the server applies it through its owner, and says what came of it (D-140). */
export async function send(action: Action): Promise<Outcome> {
  const commands = [{ id: crypto.randomUUID(), at: Date.now(), action }];
  const outcomes = await read<Outcome[]>("/api/commands", posting({ commands }));
  const outcome = outcomes[0];
  if (!outcome) throw new Error("No answer for the command");
  return outcome;
}

/** What came of [action], for the line under the buttons: nothing, if it went through. */
export async function saying(action: Action): Promise<string | null> {
  try {
    const outcome = await send(action);
    return outcome.state === "applied" ? null : (outcome.why ?? "That couldn't be done.");
  } catch {
    return UNREACHED;
  }
}

/**
 * The chat read up to [at], the newest message the page showed, on leaving it: kept alive, as the page may be going, and left
 * unsaid if it doesn't get there, as the next visit's read will take it further.
 */
export function readTo(at: number) {
  const commands = [{ id: crypto.randomUUID(), at: Date.now(), action: { type: "ReadChat", now: at } }];
  void fetch("/api/commands", { credentials: "same-origin", keepalive: true, ...posting({ commands }) }).catch(() => undefined);
}

/** Your message that waits, asked for again: why not, if it couldn't be. */
export async function tryAgain(): Promise<string | null> {
  try {
    await read<undefined>("/api/chat/try-again", { method: "POST", headers: CHANGING });
    return null;
  } catch {
    return UNREACHED;
  }
}

/** What came of a block let go on the Day: the server judges it by the phone's rules, on the plan as it then is. */
export async function dropping(drop: { key: string; date: string; onto: string | null; minute: number | null }): Promise<Asked> {
  try {
    return await read<Asked>("/api/day/drop", posting(drop));
  } catch {
    return { done: false, said: UNREACHED };
  }
}

/** What came of asking [ask] of task [id], the server making it the commands the phone's sheet would. */
export async function asking(id: string, ask: TaskAsk): Promise<Asked> {
  try {
    return await read<Asked>(`/api/tasks/${encodeURIComponent(id)}/ask`, posting(ask));
  } catch {
    return { done: false, said: UNREACHED };
  }
}
