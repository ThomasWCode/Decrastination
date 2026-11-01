import { useCallback } from "react";
import { api } from "./api";
import { useLive } from "./live";
import type { SetupLine } from "./types";

function Line({ line }: { line: SetupLine }) {
  const needed = line.done === false;
  return (
    <li className={needed ? "setup-line needed" : "setup-line"}>
      <span className="mark" aria-hidden="true">
        {line.done === true ? "✓" : needed ? "!" : "•"}
      </span>
      <span>
        <span className="title">
          {needed ? <span className="visually-hidden">Needs you: </span> : null}
          {line.title}
        </span>
        <span className="line">{line.detail}</span>
      </span>
    </li>
  );
}

/** What each part of the app needs, and whether it has it (UI-18). */
export function SetupPage() {
  const load = useCallback(() => api.setup(), []);
  const { value: view, failed } = useLive(load);
  if (!view) return failed ? <p className="problem">Setup couldn't be read. It tries again with the next change.</p> : null;
  return (
    <main className="week-page">
      <h1>Setup</h1>
      {view.saved.length > 0 ? (
        <section aria-labelledby="setup-saved">
          <h2 id="setup-saved">Saved data</h2>
          <ul className="setup-lines">
            {view.saved.map((line) => (
              <Line key={line.title + line.detail} line={line} />
            ))}
          </ul>
        </section>
      ) : null}
      <section aria-labelledby="setup-sources">
        <h2 id="setup-sources">Sources</h2>
        <ul className="setup-lines">
          {view.sources.map((line) => (
            <Line key={line.title} line={line} />
          ))}
        </ul>
      </section>
      <section aria-labelledby="setup-claude">
        <h2 id="setup-claude">Claude</h2>
        <ul className="setup-lines">
          <Line line={view.claude} />
        </ul>
      </section>
    </main>
  );
}
