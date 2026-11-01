import { useEffect, useState } from "react";
import { api } from "./api";
import type { PushView } from "./types";

/** From the start of signing out, unless that fails: what's still being asked for is let go, or it could outlast the session. */
let leaving = false;

/** Said when this browser's been stopped with its page still open, by a sign-out that failed. */
const STOPPED = "browser-push-stopped";

/** [key], as URL-safe base64, as the bytes a push manager takes. */
function keyBytes(key: string): Uint8Array<ArrayBuffer> {
  const text = atob(key.replaceAll("-", "+").replaceAll("_", "/"));
  return Uint8Array.from(text, (letter) => letter.charCodeAt(0));
}

/**
 * This browser told of nothing more: the browser's end first, as with that gone nothing can reach it, whatever the server
 * hears. Its push service then says it's gone at the next send, and the server lets it go, so a failure to say so here loses nothing.
 */
async function stop(held: PushSubscription): Promise<void> {
  try {
    await held.unsubscribe();
  } finally {
    await api.pushForget(held.endpoint);
  }
}

/** Whether [held] was made under the box's [key]: one made under old keys fails at every send, with nothing to say so. */
function madeUnder(held: PushSubscription, key: string): boolean {
  const had = held.options.applicationServerKey;
  if (!had) return false;
  const bytes = new Uint8Array(had);
  const wanted = keyBytes(key);
  return bytes.length === wanted.length && bytes.every((byte, index) => byte === wanted[index]);
}

/** This browser asked to be told of [channels], or, with none, to be told of nothing: why not, if it couldn't be. */
async function save(key: string, channels: string[]): Promise<string | null> {
  await navigator.serviceWorker.register("/sw.js");
  const registered = await navigator.serviceWorker.ready;
  let held = await registered.pushManager.getSubscription();
  if (channels.length === 0) {
    if (held) await stop(held);
    return null;
  }
  if ((await Notification.requestPermission()) !== "granted") return "This browser won't show notifications: that's changed in its own settings.";
  if (held && !madeUnder(held, key)) {
    await stop(held);
    held = null;
  }
  const subscription = held ?? (await registered.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(key) }));
  // Signing out can't see one still being made, and may already have looked.
  if (leaving) {
    await subscription.unsubscribe();
    return "Signing out stopped that.";
  }
  const sealed = subscription.toJSON().keys;
  await api.pushKeep({ endpoint: subscription.endpoint, p256dh: sealed?.p256dh ?? "", auth: sealed?.auth ?? "", channels });
  return null;
}

/** What this browser holds stopped, if it holds anything. */
async function stopHeld(): Promise<void> {
  if (!("serviceWorker" in navigator)) return;
  const registered = await navigator.serviceWorker.getRegistration();
  const held = await registered?.pushManager.getSubscription();
  if (held) await stop(held);
}

/**
 * Signing out, by [end]: this browser told of nothing more first, as it takes the session to ask, and what's told is the owner's
 * alone. Whether or not that gets through, [end] goes ahead. It doesn't wait for what's being asked for, which a browser's own
 * prompt can hold up for as long as it's left open. Signed out, this stays set: signing in loads the page afresh. A sign-out
 * that fails has stopped the browser all the same, so its page reads it again.
 */
export async function stopTelling(end: () => Promise<unknown>): Promise<void> {
  leaving = true;
  let ended = false;
  try {
    await stopHeld().catch(() => undefined);
    await end();
    ended = true;
  } finally {
    leaving = ended;
    if (!ended) dispatchEvent(new Event(STOPPED));
  }
}

/** What this browser's told of with its tab closed (W-9): nothing till it's asked for, as the phone tells you of all of it anyway. */
export function BrowserPush({ push }: { push: PushView }) {
  const supported = "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;
  const [chosen, setChosen] = useState<string[] | null>(null);
  const [said, setSaid] = useState<string | null>(null);
  // One change at a time: each sends the whole list, so two at once would each leave out the other's.
  const [busy, setBusy] = useState(false);
  // What it asked for, when that couldn't be read: not nothing, or the next tick would take the rest away.
  const [unread, setUnread] = useState(false);
  const [reads, setReads] = useState(0);
  useEffect(() => {
    const again = () => setReads((read) => read + 1);
    addEventListener(STOPPED, again);
    return () => removeEventListener(STOPPED, again);
  }, []);
  useEffect(() => {
    if (!supported) return;
    let gone = false;
    navigator.serviceWorker
      .getRegistration()
      .then((registered) => registered?.pushManager.getSubscription())
      .then((held) => (held ? api.pushChannels(held.endpoint).then((asked) => asked.channels) : []))
      .then((channels) => {
        if (gone) return;
        setUnread(false);
        setChosen(channels);
      })
      .catch(() => {
        if (!gone) setUnread(true);
      });
    return () => {
      gone = true;
    };
  }, [supported, reads]);
  if (!supported) return <p className="note long">This browser can't be told anything with its tab closed.</p>;
  if (unread) {
    return (
      <section className="browser-push" aria-label="This browser">
        <p className="problem">What this browser's told of couldn't be read.</p>
        <button className="quiet" onClick={() => setReads(reads + 1)}>
          Try again
        </button>
      </section>
    );
  }
  if (!chosen) return null;
  const change = (next: string[]) => {
    setSaid(null);
    setBusy(true);
    save(push.key, next)
      .then((why) => {
        if (why) setSaid(why);
        else setChosen(next);
      })
      .catch(() => setSaid("That didn't reach the server. Try again."))
      .finally(() => setBusy(false));
  };
  return (
    <section className="browser-push" aria-label="This browser">
      <h3>This browser</h3>
      <p className="note long">Told here with its tab closed, as well as on the phone, so only what you'd want twice.</p>
      {push.channels.map((channel) => (
        <label key={channel.key}>
          <input type="checkbox" disabled={busy} checked={chosen.includes(channel.key)} onChange={(event) => change(event.target.checked ? [...chosen, channel.key] : chosen.filter((one) => one !== channel.key))} />
          {channel.label}
        </label>
      ))}
      {said ? (
        <p className="problem" role="status">
          {said}
        </p>
      ) : null}
    </section>
  );
}
