import { useCallback, useEffect, useState } from "react";

/** Each new revision of the state, as the server says it: one connection, shared by the pages. */
function onRevision(listener: () => void): () => void {
  const events = new EventSource("/api/events");
  events.addEventListener("revision", listener);
  return () => events.close();
}

export interface Loaded<T> {
  value: T | null;
  failed: boolean;
}

/** [load]'s answer, fetched again at each revision and as the page comes back into view. */
export function useLive<T>(load: () => Promise<T>): Loaded<T> {
  const [state, setState] = useState<Loaded<T>>({ value: null, failed: false });
  const refresh = useCallback(() => {
    load()
      .then((value) => setState({ value, failed: false }))
      .catch(() => setState((was) => ({ ...was, failed: true })));
  }, [load]);
  useEffect(() => {
    refresh();
    const stop = onRevision(refresh);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [refresh]);
  return state;
}

/** "5 minutes ago", for how fresh what's shown is. */
export function ago(at: number, now: number = Date.now()): string {
  const minutes = Math.floor((now - at) / 60_000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return `${Math.floor(hours / 24)} days ago`;
}
