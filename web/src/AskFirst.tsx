import { useState } from "react";
import { saying } from "./api";
import type { ChatButton } from "./types";

/** A button that asks first, in the server's words, then sends what it says, and says why if that came to nothing. */
export function AskFirst({ button }: { button: ChatButton }) {
  const [asking, setAsking] = useState(false);
  const [said, setSaid] = useState<string | null>(null);
  const yes = () => {
    setAsking(false);
    if (button.action) void saying(button.action).then(setSaid);
  };
  return (
    <>
      <button className="quiet" onClick={() => setAsking(true)}>
        {button.label}
      </button>
      {asking ? (
        <div className="asking" role="alertdialog" aria-label={button.confirm ?? button.label}>
          <p className="title">{button.confirm}</p>
          {button.detail ? <p>{button.detail}</p> : null}
          <div className="buttons">
            <button className="button" onClick={yes}>
              {button.yes ?? button.label}
            </button>
            <button className="quiet" onClick={() => setAsking(false)}>
              {button.no ?? "Cancel"}
            </button>
          </div>
        </div>
      ) : null}
      {said ? (
        <p className="problem" role="status">
          {said}
        </p>
      ) : null}
    </>
  );
}
