import type { DayView, DeviceLine, PairingCode, WorkView } from "./types";

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
  devices: () => read<DeviceLine[]>("/api/devices"),
  pair: () => read<PairingCode>("/api/devices/pair", { method: "POST", headers: CHANGING }),
  revoke: (id: string) => read<undefined>(`/api/devices/${encodeURIComponent(id)}`, { method: "DELETE", headers: CHANGING }),
  signOut: () => read<{ signedIn: boolean }>("/api/auth/logout", { method: "POST", headers: CHANGING }),
};
