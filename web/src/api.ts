import type { Action, DayView, DeviceLine, Outcome, PairingCode, TaskView, WorkView } from "./types";

/** A request the server answered with a refusal. */
export class ApiError extends Error {
  constructor(readonly status: number) {
    super(`The server answered ${status}`);
  }
}

// A cross-site form can't send this header: the server needs it on whatever changes something.
const CHANGING = { "X-Requested-With": "decrastination" };

async function read<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { credentials: "same-origin", ...init });
  if (!response.ok) throw new ApiError(response.status);
  return (response.status === 204 ? undefined : await response.json()) as T;
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
  const outcomes = await read<Outcome[]>("/api/commands", {
    method: "POST",
    headers: { ...CHANGING, "Content-Type": "application/json" },
    body: JSON.stringify({ commands }),
  });
  const outcome = outcomes[0];
  if (!outcome) throw new Error("No answer for the command");
  return outcome;
}
