import { useEffect, useState } from "react";

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

/** Runs of [load] with only the newest's answer heard, so a slow one can't overwrite what came after it, nor a page that's moved on. */
export function newestOnly<T>(load: () => Promise<T>, show: (value: T) => void, fail: () => void) {
  let newest = 0;
  return {
    run() {
      const request = ++newest;
      load()
        .then((value) => {
          if (request === newest) show(value);
        })
        .catch(() => {
          if (request === newest) fail();
        });
    },
    cancel() {
      newest++;
    },
  };
}

/** How often a page is asked for again with nothing changed: the time moves on, with the now line and what's next. */
const EVERY_MS = 60_000;

/** [load]'s answer, fetched again when the revision moves, as the page comes back into view, and each minute it's in view. */
export function useLive<T>(load: () => Promise<T>): Loaded<T> {
  const [state, setState] = useState<Loaded<T>>({ value: null, failed: false });
  useEffect(() => {
    const loads = newestOnly(
      load,
      (value) => setState({ value, failed: false }),
      () => setState((was) => ({ ...was, failed: true })),
    );
    const refresh = () => loads.run();
    refresh();
    let seen: string | null = null;
    const stop = onRevision((revision) => {
      if (revision === seen) return;
      seen = revision;
      refresh();
    });
    const clock = setInterval(() => {
      if (!document.hidden) refresh();
    }, EVERY_MS);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      loads.cancel();
      stop();
      clearInterval(clock);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [load]);
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
