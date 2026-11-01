import { useState } from "react";
import { saying, telling } from "./api";
import type { ChatButton } from "./types";

/** A button that asks first, in the words it's given, and on a yes does [go]. */
export function Asking({ label, title, detail, yes, no, go }: { label: string; title: string; detail?: string | null; yes: string; no: string; go: () => void }) {
  const [asking, setAsking] = useState(false);
  return (
    <>
      <button className="quiet" onClick={() => setAsking(true)}>
        {label}
      </button>
      {asking ? (
        <div className="asking" role="alertdialog" aria-label={title}>
          <p className="title">{title}</p>
          {detail ? <p>{detail}</p> : null}
          <div className="buttons">
            <button
              className="button"
              onClick={() => {
                setAsking(false);
                go();
              }}
            >
              {yes}
            </button>
            <button className="quiet" onClick={() => setAsking(false)}>
              {no}
            </button>
          </div>
        </div>
      ) : null}
    </>
  );
}

/**
 * A button of the server's that asks first, in its words, then sends what it says. Why it came to nothing is said here; or,
 * given [told], what came of it either way goes there, for a page that says it in one place.
 */
export function AskFirst({ button, told }: { button: ChatButton; told?: (words: string | null) => void }) {
  const [said, setSaid] = useState<string | null>(null);
  const go = () => {
    if (!button.action) return;
    if (told) void telling(button.action).then(told);
    else void saying(button.action).then(setSaid);
  };
  return (
    <>
      <Asking label={button.label} title={button.confirm ?? button.label} detail={button.detail} yes={button.yes ?? button.label} no={button.no ?? "Cancel"} go={go} />
      {said ? (
        <p className="problem" role="status">
          {said}
        </p>
      ) : null}
    </>
  );
}
