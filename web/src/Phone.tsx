import { useCallback, useState } from "react";
import { api } from "./api";
import { Asking } from "./AskFirst";
import { ago, useLive } from "./live";
import type { PairingCode } from "./types";

/** Pairing a phone: a code to type into its Settings, good for five minutes; and what's paired, each able to be revoked. */
export function PhonePage() {
  const load = useCallback(() => api.devices(), []);
  const { value: devices, failed } = useLive(load);
  const [code, setCode] = useState<PairingCode | null>(null);
  const [revoked, setRevoked] = useState<string[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const pair = () => {
    setProblem(null);
    api
      .pair()
      .then(setCode)
      .catch(() => setProblem("A code couldn't be made. Sign in again if this goes on."));
  };
  const revoke = (id: string) => {
    setProblem(null);
    api
      .revoke(id)
      .then(() => setRevoked((was) => [...was, id]))
      .catch(() => setProblem("That couldn't be revoked. Try again."));
  };
  return (
    <main className="phone-page">
      <h1>Phone</h1>
      <section>
        <h2>Pair a phone</h2>
        <p>On the phone, open Settings, then The website, tap Pair and type this code. It works once, for five minutes.</p>
        {code ? (
          <p className="code" data-testid="pairing-code">
            {code.code}
          </p>
        ) : null}
        <button className="button" onClick={pair}>
          {code ? "Show another code" : "Show a code"}
        </button>
        {problem ? <p className="problem">{problem}</p> : null}
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
                <Asking label="Revoke" title={`Revoke ${device.name}?`} detail="It stops reaching the server at once, and has to be paired again." yes="Revoke" no="Keep it" go={() => revoke(device.id)} />
              </li>
            ))}
        </ul>
        {devices?.length === 0 ? <p>No phone is paired yet.</p> : null}
      </section>
    </main>
  );
}
