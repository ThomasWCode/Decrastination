import { useCallback, useState, type ChangeEvent } from "react";
import { api, importing, restoring } from "./api";
import { AskFirst } from "./AskFirst";
import { useLive } from "./live";

/** A backup chosen and taken in at once, as the phone's Import takes one: what came of it goes to [tell]. */
function Restore({ tooBig, tell }: { tooBig: string; tell: (words: string) => void }) {
  const [busy, setBusy] = useState(false);
  const chosen = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    // So the same file can be chosen again.
    event.target.value = "";
    if (!file) return;
    setBusy(true);
    void importing(file, tooBig).then((words) => {
      setBusy(false);
      tell(words);
    });
  };
  return (
    <label className="button">
      {busy ? "Importing…" : "Import a backup"}
      <input className="visually-hidden" type="file" accept=".json,application/json,text/plain" disabled={busy} onChange={chosen} />
    </label>
  );
}

/** Backup and Reset: all the app keeps as a file, out and in, and each part reset, a copy saved first. While the phone writes, only the file's to be had. */
export function BackupPage() {
  const load = useCallback(() => api.backup(), []);
  const { value: view, failed } = useLive(load);
  const [said, setSaid] = useState<string | null>(null);
  if (!view) return failed ? <p className="problem">Backup couldn't be read. It tries again with the next change.</p> : null;
  return (
    <main className="week-page">
      <h1>Backup</h1>
      {said ? <p role="status">{said}</p> : null}
      <section aria-labelledby="backup-out">
        <h2 id="backup-out">Back up</h2>
        <p>{view.backUp}</p>
        {view.fit ? <p className="note">{view.fit}</p> : null}
        {view.lastNightly ? <p className="note">{view.lastNightly}</p> : null}
        {view.canDownload ? (
          <a className="button" href="/api/backup" download>
            Download a backup
          </a>
        ) : null}
      </section>
      {view.canChange ? (
        <>
          <section aria-labelledby="backup-in">
            <h2 id="backup-in">Restore</h2>
            <p>{view.restore}</p>
            <Restore tooBig={view.tooBig} tell={setSaid} />
          </section>
          {view.copies.length > 0 ? (
            <section aria-labelledby="backup-copies">
              <h2 id="backup-copies">Copies saved before a reset</h2>
              <ul className="stat-lines">
                {view.copies.map((name) => (
                  <li key={name}>
                    <span>{name}</span>
                    <button className="quiet" onClick={() => void restoring(name).then(setSaid)}>
                      Restore
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
          <section aria-labelledby="backup-reset">
            <h2 id="backup-reset">Reset</h2>
            <p className="note">A copy of everything the app keeps is saved first, to restore from here.</p>
            <ul className="stat-lines">
              {view.resets.map((reset) => (
                <li key={reset.title}>
                  <span>
                    <span className="title">{reset.title}</span> <span className="note">{reset.takes}</span>
                  </span>
                  <AskFirst button={reset.button} told={setSaid} />
                </li>
              ))}
            </ul>
          </section>
        </>
      ) : (
        <p className="note">Restoring and resetting are on the phone for now.</p>
      )}
    </main>
  );
}
