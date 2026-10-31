import { useCallback, useEffect, useState } from "react";

/** The state's revision as the server says it: the one it's at on connecting, and on reconnecting, then each new one. */
function onRevision(listener: (revision: string) => void): () => void {
  const events = new EventSource("/api/events");
  events.addEventListener("revision", (event: MessageEvent<string>) => listener(event.data));
  return () => events.close();
}

export interface Loaded<T> {
  value: T | null;
  failed: boolean;
}

/** [load]'s answer, fetched again when the revision moves and as the page comes back into view. */
export function useLive<T>(load: () => Promise<T>): Loaded<T> {
  const [state, setState] = useState<Loaded<T>>({ value: null, failed: false });
  const refresh = useCallback(() => {
    load()
      .then((value) => setState({ value, failed: false }))
      .catch(() => setState((was) => ({ ...was, failed: true })));
  }, [load]);
  useEffect(() => {
    refresh();
    let seen: string | null = null;
    const stop = onRevision((revision) => {
      if (revision === seen) return;
      seen = revision;
      refresh();
    });
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
