import { useCallback, useState } from "react";
import { api } from "./api";
import { ago, useLive } from "./live";
import type { PairingCode } from "./types";

/** Pairing a phone: a code to type into its Setup, good for five minutes; and what's paired, each able to be revoked. */
export function PhonePage() {
  const load = useCallback(() => api.devices(), []);
  const { value: devices, failed } = useLive(load);
  const [code, setCode] = useState<PairingCode | null>(null);
  const [revoked, setRevoked] = useState<string[]>([]);
  const revoke = (id: string) => {
    api.revoke(id).then(() => setRevoked((was) => [...was, id]));
  };
  return (
    <main className="phone-page">
      <h1>Phone</h1>
      <section>
        <h2>Pair a phone</h2>
        <p>On the phone, open Setup, then This phone, and type this code. It works once, for five minutes.</p>
        {code ? (
          <p className="code" data-testid="pairing-code">
            {code.code}
          </p>
        ) : null}
        <button className="button" onClick={() => api.pair().then(setCode)}>
          {code ? "Show another code" : "Show a code"}
        </button>
      </section>
      <section>
        <h2>Paired</h2>
        {failed ? <p className="problem">The list couldn't be read.</p> : null}
        <ul className="devices">
          {devices
            ?.filter((device) => !revoked.includes(device.id))
            .map((device) => (
              <li key={device.id}>
                <span className="title">{device.name}</span>
                <span className="line">{device.lastSeenAt ? `Last heard from ${ago(device.lastSeenAt)}` : "Not heard from yet"}</span>
                <button className="quiet" onClick={() => revoke(device.id)}>
                  Revoke
                </button>
              </li>
            ))}
        </ul>
        {devices?.length === 0 ? <p>No phone is paired yet.</p> : null}
      </section>
    </main>
  );
}
