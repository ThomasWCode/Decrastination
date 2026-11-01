import { useCallback, useEffect, useState } from "react";
import { api, asking, saying } from "./api";
import { useLive } from "./live";
import { moment, NewTask } from "./NewTask";
import type { Action, MoveView, OwnTaskFormView, TaskAction, TaskAsk, TaskField } from "./types";

/** What a button came to, for the line under them, and what takes it back. */
interface Said {
  text: string | null;
  bad: boolean;
  undo: Action | null;
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

const INPUTS: Record<string, string> = { due: "datetime-local", minutes: "number", start: "date" };

/** What's typed into field [kind], as what's asked of the task. */
function given(kind: string, value: string): TaskAsk | null {
  if (!value) return null;
  if (kind === "due") {
    const at = moment(value);
    return at === null ? null : { kind, at };
  }
  return kind === "minutes" ? { kind, minutes: Number(value) } : { kind, date: value };
}

function Field({ field, ask, press }: { field: TaskField; ask: (ask: TaskAsk) => void; press: (button: TaskAction) => void }) {
  const [changing, setChanging] = useState(false);
  const [value, setValue] = useState("");
  const set = () => {
    const made = given(field.kind, value);
    if (!made) return;
    setChanging(false);
    setValue("");
    ask(made);
  };
  return (
    <div className="field">
      <span className="label">{field.label}</span>
      <span className="says">{field.says}</span>
      {field.note ? <span className="note">{field.note}</span> : null}
      <span className="changing">
        {field.changes ? (
          <button className="quiet" aria-label={`Change ${field.label}`} onClick={() => setChanging(!changing)}>
            Change
          </button>
        ) : null}
        {field.undo ? (
          <button className="quiet" aria-label={`${field.undo.label} ${field.label}`} onClick={() => field.undo && press(field.undo)}>
            {field.undo.label}
          </button>
        ) : null}
      </span>
      {changing ? (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            set();
          }}
        >
          <input aria-label={field.label} type={INPUTS[field.kind] ?? "text"} value={value} onChange={(event) => setValue(event.target.value)} autoFocus />
          <button className="button" disabled={!value}>
            Set
          </button>
        </form>
      ) : null}
    </div>
  );
}

/** Where its next piece can go (UI-04): a day of the fortnight, then this piece at a time, or all of it that day. */
function Move({ move, ask }: { move: MoveView; ask: (ask: TaskAsk) => void }) {
  const [date, setDate] = useState<string | null>(null);
  const [time, setTime] = useState("17:00");
  const minute = () => {
    const [hours, minutes] = time.split(":").map(Number);
    return (hours ?? 0) * 60 + (minutes ?? 0);
  };
  return (
    <div className="move" role="group" aria-label={`Move ${move.piece}`}>
      <p className="title">Move: {move.piece}</p>
      <div className="days">
        {move.days.map((day) => (
          <button key={day.date} disabled={!day.allowed} aria-pressed={date === day.date} onClick={() => setDate(day.date)}>
            {day.label}
          </button>
        ))}
      </div>
      {move.notes.map((note) => (
        <p className="notes" key={note}>
          {note}
        </p>
      ))}
      {date ? (
        <div className="going">
          <input aria-label="At" type="time" value={time} onChange={(event) => setTime(event.target.value)} />
          <button className="button" disabled={!time} onClick={() => ask({ kind: "toTime", date, minute: minute() })}>
            This piece, at that time
          </button>
          <button className="action" onClick={() => ask({ kind: "toDay", date })}>
            All of it, any time that day
          </button>
        </div>
      ) : null}
    </div>
  );
}

/** A task, over the list: what it is, what's planned of it, and what can be done with it, each as the server says (D-137). */
export function TaskSheet({ id, form, close }: { id: string; form: OwnTaskFormView | null; close: () => void }) {
  const load = useCallback(() => api.task(id), [id]);
  const { value: task, failed } = useLive(load);
  const [said, setSaid] = useState<Said | null>(null);
  const [asked, setAsked] = useState<TaskAction | null>(null);
  const [note, setNote] = useState("");
  const [editing, setEditing] = useState(false);
  const [moving, setMoving] = useState(false);
  const [busy, setBusy] = useState(false);

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
  const ask = (made: TaskAsk, undo: Action | null = null) => {
    setMoving(false);
    going(asking(id, made).then(({ done, said: words }) => ({ text: words ?? null, bad: !done, undo: done ? undo : null })));
  };
  const act = (action: Action, undo: Action | null = null) => {
    going(saying(action).then((why) => ({ text: why, bad: why !== null, undo: why === null ? undo : null })));
  };
  const press = (button: TaskAction) => {
    if (button.ask) ask(button.ask, button.undo ?? null);
    else if (button.action) act(button.action, button.undo ?? null);
  };
  const ifSure = (button: TaskAction) => (button.confirm ? setAsked(button) : press(button));
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
      {failed ? (
        <p className="problem">That task has gone, or it can't be read just now.</p>
      ) : !task ? null : (
        <>
          <h2>{task.title}</h2>
          <p className="line">{task.line}</p>
          {task.tags.length > 0 ? (
            <ul className="tags">
              {task.tags.map((tag) => (
                <li key={tag}>{tag}</li>
              ))}
            </ul>
          ) : null}
          {task.detail ? <p className="detail">{task.detail}</p> : null}
          <div className="fields">
            {task.fields.map((field) => (
              <Field key={field.kind} field={field} ask={ask} press={ifSure} />
            ))}
          </div>
          {task.next ? <p className="next">{task.next}</p> : null}
          <div className="actions" role="group" aria-label="Do">
            {task.actions.map((action) => (
              <button key={action.label} className="action" disabled={busy} onClick={() => ifSure(action)}>
                {action.label}
              </button>
            ))}
            {task.move ? (
              <button className="action" aria-expanded={moving} onClick={() => setMoving(!moving)}>
                Move
              </button>
            ) : null}
            {task.edit && form ? (
              <button className="action" onClick={() => setEditing(true)}>
                Edit
              </button>
            ) : null}
          </div>
          {task.where ? <p className="line">{task.where}</p> : null}
          {moving && task.move ? <Move move={task.move} ask={ask} /> : null}
          {editing && task.edit && form ? <NewTask form={form} edit={task.edit} close={() => setEditing(false)} /> : null}
          {asked ? <Asking asked={asked} go={() => press(asked)} cancel={() => setAsked(null)} /> : null}
          {said ? (
            <p className={said.bad ? "problem" : "said"} role="status">
              {said.text}{" "}
              {said.undo ? (
                <button className="quiet" onClick={() => said.undo && act(said.undo)}>
                  Undo
                </button>
              ) : null}
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
                      <button className="quiet" disabled={busy} onClick={() => act(tick)}>
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
          {task.notes.length > 0 || task.notable ? (
            <section>
              <h3>Notes</h3>
              <ul className="steps">
                {task.notes.map((line) => (
                  <li key={line.line}>
                    <span>{line.line}</span>
                    {line.delete ? (
                      <button className="quiet" onClick={() => line.delete && ifSure(line.delete)}>
                        {line.delete.label}
                      </button>
                    ) : null}
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
          ) : null}
        </>
      )}
    </aside>
  );
}
