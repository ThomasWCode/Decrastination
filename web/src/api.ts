import type { Action, Asked, DayView, DeviceLine, Outcome, PairingCode, TaskAsk, TaskView, WorkView } from "./types";

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
  task: (id: string) => read<TaskView>(`/api/views/task/${encodeURIComponent(id)}`),
  devices: () => read<DeviceLine[]>("/api/devices"),
  pair: () => read<PairingCode>("/api/devices/pair", { method: "POST", headers: CHANGING }),
  revoke: (id: string) => read<undefined>(`/api/devices/${encodeURIComponent(id)}`, { method: "DELETE", headers: CHANGING }),
  signOut: () => read<{ signedIn: boolean }>("/api/auth/logout", { method: "POST", headers: CHANGING }),
};

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
    return outcome.state === "dropped" ? (outcome.why ?? "That couldn't be done.") : null;
  } catch {
    return UNREACHED;
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
