import { useCallback, useEffect, useState } from "react";
import { api, send } from "./api";
import { useLive } from "./live";
import type { Action, TaskAction } from "./types";

/** What came of [action], for the line under the buttons: nothing, if it went through. */
export async function saying(action: Action): Promise<string | null> {
  try {
    const outcome = await send(action);
    return outcome.state === "dropped" ? (outcome.why ?? "That couldn't be done.") : null;
  } catch {
    return "That didn't reach the server. Try again.";
  }
}

function Asking({ asked, go, cancel }: { asked: TaskAction; go: () => void; cancel: () => void }) {
  return (
    <div className="asking" role="alertdialog" aria-label={asked.confirm ?? asked.label}>
      <p className="title">{asked.confirm}</p>
      {asked.detail ? <p>{asked.detail}</p> : null}
      <div className="buttons">
        <button className="button" onClick={go}>
          {asked.label}
        </button>
        <button className="quiet" onClick={cancel}>
          Cancel
        </button>
      </div>
    </div>
  );
}

/** A task, over the list: what it is, what's planned of it, and what can be done with it, each as the server says (D-137). */
export function TaskSheet({ id, close }: { id: string; close: () => void }) {
  const load = useCallback(() => api.task(id), [id]);
  const { value: task, failed } = useLive(load);
  const [said, setSaid] = useState<string | null>(null);
  const [asked, setAsked] = useState<TaskAction | null>(null);
  const [note, setNote] = useState("");

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") close();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [close]);

  const act = (action: Action) => {
    setAsked(null);
    setSaid(null);
    void saying(action).then(setSaid);
  };
  const keepNote = () => {
    const words = note.trim();
    if (!words) return;
    setNote("");
    act({ type: "AddNote", taskId: id, text: words });
  };

  return (
    <aside className="sheet" aria-label={task?.title ?? "Task"} data-testid="task-sheet">
      <button className="close" onClick={close} aria-label="Close">
        ×
      </button>
      {!task ? (
        failed ? <p className="problem">That task has gone.</p> : null
      ) : (
        <>
          <h2>{task.title}</h2>
          <p className="line">{task.line}</p>
          <p>
            <span className={task.overdue ? "due overdue" : "due"}>{task.due}</span> <span className="minutes">{task.minutes}</span>
          </p>
          {task.tags.length > 0 ? (
            <ul className="tags">
              {task.tags.map((tag) => (
                <li key={tag}>{tag}</li>
              ))}
            </ul>
          ) : null}
          {task.detail ? <p className="detail">{task.detail}</p> : null}
          {task.next ? <p className="next">{task.next}</p> : null}
          <div className="actions" role="group" aria-label="Do">
            {task.actions.map((action) => (
              <button key={action.label} className="action" onClick={() => (action.confirm ? setAsked(action) : act(action.action))}>
                {action.label}
              </button>
            ))}
          </div>
          {asked ? <Asking asked={asked} go={() => act(asked.action)} cancel={() => setAsked(null)} /> : null}
          {said ? (
            <p className="problem" role="status">
              {said}
            </p>
          ) : null}
          {task.steps.length > 0 ? (
            <section>
              <h3>Steps</h3>
              <ul className="steps">
                {task.steps.map(({ line, done, tick }) => (
                  <li key={line} className={done ? "done" : undefined}>
                    <span>{line}</span>
                    {tick ? (
                      <button className="quiet" onClick={() => act(tick)}>
                        Done
                      </button>
                    ) : null}
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
          {task.planned.length > 0 ? (
            <section>
              <h3>In the plan</h3>
              <ul>
                {task.planned.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>
            </section>
          ) : null}
          {task.instructions.length > 0 ? (
            <section>
              <h3>Your changes</h3>
              <ul>
                {task.instructions.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>
            </section>
          ) : null}
          <section>
            <h3>Notes</h3>
            <ul className="steps">
              {task.notes.map((line) => (
                <li key={line.line}>
                  <span>{line.line}</span>
                  <button className="quiet" onClick={() => act(line.delete)}>
                    Delete
                  </button>
                </li>
              ))}
            </ul>
            {task.notable ? (
              <form
                className="note"
                onSubmit={(event) => {
                  event.preventDefault();
                  keepNote();
                }}
              >
                <label>
                  A note on it
                  <input value={note} onChange={(event) => setNote(event.target.value)} />
                </label>
                <button className="button" disabled={!note.trim()}>
                  Keep
                </button>
              </form>
            ) : null}
          </section>
        </>
      )}
    </aside>
  );
}
