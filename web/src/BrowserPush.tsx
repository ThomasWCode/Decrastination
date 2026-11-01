import { useEffect, useState } from "react";
import { api } from "./api";
import type { PushView } from "./types";

/** [key], as URL-safe base64, as the bytes a push manager takes. */
function keyBytes(key: string): Uint8Array<ArrayBuffer> {
  const text = atob(key.replaceAll("-", "+").replaceAll("_", "/"));
  return Uint8Array.from(text, (letter) => letter.charCodeAt(0));
}

/** This browser asked to be told of [channels], or, with none, to be told of nothing: why not, if it couldn't be. */
async function save(key: string, channels: string[]): Promise<string | null> {
  await navigator.serviceWorker.register("/sw.js");
  const registered = await navigator.serviceWorker.ready;
  const held = await registered.pushManager.getSubscription();
  if (channels.length === 0) {
    if (held) {
      await api.pushForget(held.endpoint);
      await held.unsubscribe();
    }
    return null;
  }
  if ((await Notification.requestPermission()) !== "granted") return "This browser won't show notifications: that's changed in its own settings.";
  const subscription = held ?? (await registered.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(key) }));
  const sealed = subscription.toJSON().keys;
  await api.pushKeep({ endpoint: subscription.endpoint, p256dh: sealed?.p256dh ?? "", auth: sealed?.auth ?? "", channels });
  return null;
}

/** This browser told of nothing more: for signing out, as it takes the session to ask, and what's told is the owner's alone. */
export async function stopTelling(): Promise<void> {
  if (!("serviceWorker" in navigator)) return;
  const registered = await navigator.serviceWorker.getRegistration();
  const held = await registered?.pushManager.getSubscription();
  if (!held) return;
  await api.pushForget(held.endpoint);
  await held.unsubscribe();
}

/** What this browser's told of with its tab closed (W-9): nothing till it's asked for, as the phone tells you of all of it anyway. */
export function BrowserPush({ push }: { push: PushView }) {
  const supported = "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;
  const [chosen, setChosen] = useState<string[] | null>(null);
  const [said, setSaid] = useState<string | null>(null);
  useEffect(() => {
    if (!supported) return;
    let gone = false;
    navigator.serviceWorker
      .getRegistration()
      .then((registered) => registered?.pushManager.getSubscription())
      .then((held) => (held ? api.pushChannels(held.endpoint).then((asked) => asked.channels) : []))
      .catch(() => [])
      .then((channels) => {
        if (!gone) setChosen(channels);
      });
    return () => {
      gone = true;
    };
  }, [supported]);
  if (!supported) return <p className="note long">This browser can't be told anything with its tab closed.</p>;
  if (!chosen) return null;
  const change = (next: string[]) => {
    setSaid(null);
    save(push.key, next)
      .then((why) => {
        if (why) setSaid(why);
        else setChosen(next);
      })
      .catch(() => setSaid("That didn't reach the server. Try again."));
  };
  return (
    <section className="browser-push" aria-label="This browser">
      <h3>This browser</h3>
      <p className="note long">Told here with its tab closed, as well as on the phone, so only what you'd want twice.</p>
      {push.channels.map((channel) => (
        <label key={channel.key}>
          <input type="checkbox" checked={chosen.includes(channel.key)} onChange={(event) => change(event.target.checked ? [...chosen, channel.key] : chosen.filter((one) => one !== channel.key))} />
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
