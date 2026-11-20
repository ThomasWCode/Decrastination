import { useEffect, useRef, useState } from "react";
import { asking, saying } from "./api";
import { PhotoCheck, targetKey } from "./PhotoCheck";
import type { About, Action, BlockSheet as Sheet, TaskAction } from "./types";

/** The chat's address about [about]: the chat page shows it over its box and sends it with the message (6.6.0). */
export function chatAbout(about: About): string {
  return `/?about=${encodeURIComponent(JSON.stringify(about))}`;
}

/**
 * A block of the Day's own sheet (6.6.0), as the phone's tap opens one: a piece's, a missed slot's or an event's, what it says
 * and what can be done with it, all as the server built it (D-137). The same panel as a task's sheet, one open at a time, Escape
 * closing it and the focus going back to the block. [openTask]: *Its due date, minutes…*, the task's own sheet. While the server
 * mirrors the phone it opens to read: its buttons are absent.
 */
export function BlockSheet({ sheet, gone, canChange, close, openTask, go }: { sheet: Sheet; gone: boolean; canChange: boolean; close: () => void; openTask: (id: string) => void; go: (to: string) => void }) {
  const panel = useRef<HTMLElement>(null);
  const [busy, setBusy] = useState(false);
  const [asked, setAsked] = useState<TaskAction | null>(null);
  const [said, setSaid] = useState<{ text: string | null; bad: boolean; undo: Action | null } | null>(null);

  useEffect(() => {
    const opener = document.activeElement;
    panel.current?.focus();
    return () => {
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  }, []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") close();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [close]);

  /** One at a time, as a second press of a button that's still going would make what it sends twice. */
  const going = (work: Promise<{ text: string | null; bad: boolean; undo?: Action | null }>) => {
    setAsked(null);
    setSaid(null);
    setBusy(true);
    void work.then(({ text, bad, undo }) => {
      setBusy(false);
      setSaid(text || undo ? { text, bad, undo: undo ?? null } : null);
    });
  };
  const press = (button: TaskAction) => {
    const { ask, action } = button;
    if (ask && sheet.taskId) going(asking(sheet.taskId, ask).then(({ done, said: words }) => ({ text: words ?? null, bad: !done, undo: done ? (button.undo ?? null) : null })));
    else if (action) going(saying(action).then((why) => ({ text: why, bad: why !== null, undo: why === null ? (button.undo ?? null) : null })));
  };
  const ifSure = (button: TaskAction) => (button.confirm ? setAsked(button) : press(button));

  return (
    <aside className="sheet" aria-label={sheet.title} data-testid="block-sheet" ref={panel} tabIndex={-1}>
      <button className="close" onClick={close} aria-label="Close">
        ×
      </button>
      <h2>{sheet.title}</h2>
      {sheet.lines.map((line) => (
        <p key={line} className="line">
          {line}
        </p>
      ))}
      {/* Its block gone from the Day (done, or planned again): read as it was, what it came to with it. */}
      {gone ? <p className="note">Done, or planned again, since.</p> : null}
      {!gone && sheet.actions.length > 0 ? (
        <div className="actions" role="group" aria-label={sheet.kind === "event" ? "How it counts" : "Do"}>
          {sheet.actions.map((button) =>
            button.check ? (
              <PhotoCheck key={targetKey(button.check)} target={button.check} disabled={busy} said={(text, bad) => setSaid({ text, bad, undo: null })} />
            ) : (
              <button key={button.label} className="action" disabled={busy} onClick={() => ifSure(button)}>
                {button.label}
              </button>
            ),
          )}
        </div>
      ) : null}
      {!gone && sheet.note && sheet.actions.length > 0 ? <p className="note">{sheet.note}</p> : null}
      {asked ? (
        <div className="asking" role="alertdialog" aria-label={asked.confirm ?? asked.label}>
          <p>{asked.confirm}</p>
          {asked.detail ? <p>{asked.detail}</p> : null}
          <button className="button" onClick={() => press(asked)}>
            {asked.label}
          </button>
          <button className="quiet" onClick={() => setAsked(null)}>
            Cancel
          </button>
        </div>
      ) : null}
      {said ? (
        <p className={said.bad ? "said problem" : "said"} role="status">
          {said.text}{" "}
          {said.undo ? (
            <button className="quiet" onClick={() => said.undo && going(saying(said.undo).then((why) => ({ text: why, bad: why !== null })))}>
              Undo
            </button>
          ) : null}
        </p>
      ) : null}
      <div className="sheet-links">
        {sheet.taskSheet && sheet.taskId ? (
          <button className="quiet" onClick={() => sheet.taskId && openTask(sheet.taskId)}>
            Its due date, minutes…
          </button>
        ) : null}
        {canChange && sheet.about ? (
          <button className="quiet" onClick={() => sheet.about && go(chatAbout(sheet.about))}>
            Ask Felix about it
          </button>
        ) : null}
      </div>
    </aside>
  );
}
