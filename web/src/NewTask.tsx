import { useState } from "react";
import { saying } from "./api";
import type { OwnStep, OwnTaskEdit, OwnTaskFormView } from "./types";

/** A datetime-local field's words as a moment, or null where it's empty. */
export function moment(field: string): number | null {
  if (!field) return null;
  const at = new Date(field).getTime();
  return Number.isNaN(at) ? null : at;
}

const pad = (value: number) => String(value).padStart(2, "0");

/** [at] as a datetime-local field writes it, where this browser is. */
export function local(at: number | null | undefined): string {
  if (at === null || at === undefined) return "";
  const when = new Date(at);
  return `${when.getFullYear()}-${pad(when.getMonth() + 1)}-${pad(when.getDate())}T${pad(when.getHours())}:${pad(when.getMinutes())}`;
}

interface Row {
  title: string;
  minutes: string;
  /** Its own place, as the phone keeps it: home, anywhere, or none of its own, so it goes with its task's. */
  where: string | null;
  anyOrder: boolean;
  /** Which step it was, kept with it so a renamed one keeps its tick. */
  wasIndex: number | null;
  was: OwnStep | null;
}

function rowsOf(start: OwnTaskEdit | undefined, editing: boolean): Row[] {
  return (start?.task.steps ?? []).map((step, index) => ({
    title: step.title,
    minutes: String(step.minutes),
    where: step.where ?? null,
    anyOrder: step.anyOrder ?? false,
    wasIndex: editing ? index : null,
    was: step,
  }));
}

/**
 * A task of your own, new, [from] what's been read for it, or [edit]ed: what the form won't take is the server's to say
 * (`OwnTaskRules`), and its kinds and limits are too.
 */
export function NewTask({ form, edit, from: read, close }: { form: OwnTaskFormView; edit?: OwnTaskEdit; from?: OwnTaskEdit; close: () => void }) {
  const given = edit ?? read;
  const start = given?.task;
  const [sourceId] = useState(() => given?.sourceId ?? crypto.randomUUID());
  const [title, setTitle] = useState(start?.title ?? "");
  const [kind, setKind] = useState(start?.kind ?? form.kinds[0]?.name ?? "");
  const [subject, setSubject] = useState(start?.className ?? "");
  const [minutes, setMinutes] = useState(String(start?.minutes ?? form.defaultMinutes));
  const [due, setDue] = useState(local(start?.dueAt));
  const [from, setFrom] = useState(local(start?.startFrom).slice(0, 10));
  const [notes, setNotes] = useState(start?.notes ?? "");
  const [rows, setRows] = useState(() => rowsOf(given, edit !== undefined));
  const [said, setSaid] = useState<string | null>(null);
  const event = form.kinds.find((choice) => choice.name === kind)?.event ?? false;

  const change = (index: number, made: Partial<Row>) => setRows(rows.map((row, at) => (at === index ? { ...row, ...made } : row)));
  const steps = event ? [] : rows;

  const save = () => {
    setSaid(null);
    const task = {
      ...start,
      title: title.trim(),
      notes,
      kind,
      className: subject.trim() || null,
      dueAt: moment(due),
      startFrom: event ? null : moment(from ? `${from}T00:00` : ""),
      minutes: steps.length > 0 ? steps.reduce((all, row) => all + Number(row.minutes), 0) : Number(minutes),
      steps: steps.map((row, index) => ({
        ...row.was,
        title: row.title.trim(),
        minutes: Number(row.minutes),
        where: row.where,
        anyOrder: row.anyOrder && index > 0,
      })),
      where: start?.where ?? "anywhere",
    };
    void saying({ type: "SaveOwnTask", sourceId, task, fromForm: true, wasIndexes: steps.map((row) => row.wasIndex) }).then((why) => {
      if (why === null) close();
      else setSaid(why);
    });
  };

  return (
    <form
      className="new-task"
      aria-label={edit ? "Edit task" : "New task"}
      onSubmit={(event) => {
        event.preventDefault();
        save();
      }}
    >
      <label className="long">
        What
        <input value={title} onChange={(event) => setTitle(event.target.value)} maxLength={form.maxTitle} autoFocus />
      </label>
      <label>
        Kind
        <select value={kind} onChange={(event) => setKind(event.target.value)}>
          {form.kinds.map((choice) => (
            <option key={choice.name} value={choice.name}>
              {choice.label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Subject
        <input value={subject} onChange={(event) => setSubject(event.target.value)} maxLength={form.maxClass} />
      </label>
      <label>
        {event ? "When" : "Due"}
        <input value={due} onChange={(event) => setDue(event.target.value)} type="datetime-local" />
      </label>
      {event ? null : (
        <>
          <label>
            Can't start before
            <input value={from} onChange={(event) => setFrom(event.target.value)} type="date" />
          </label>
          {steps.length === 0 ? (
            <label>
              Minutes
              <input value={minutes} onChange={(event) => setMinutes(event.target.value)} type="number" min="1" />
            </label>
          ) : null}
          <div className="rows">
            {steps.map((row, index) => (
              <div className="row" key={index} role="group" aria-label={`Step ${index + 1}`}>
                <label>
                  Step
                  <input value={row.title} onChange={(event) => change(index, { title: event.target.value })} maxLength={form.maxTitle} />
                </label>
                <label>
                  Minutes
                  <input value={row.minutes} onChange={(event) => change(index, { minutes: event.target.value })} type="number" min="1" />
                </label>
                <label className="mark">
                  <input type="checkbox" checked={row.where === "home"} onChange={(event) => change(index, { where: event.target.checked ? "home" : "anywhere" })} />
                  Home only
                </label>
                {index > 0 ? (
                  <label className="mark">
                    <input type="checkbox" checked={row.anyOrder} onChange={(event) => change(index, { anyOrder: event.target.checked })} />
                    Any order
                  </label>
                ) : null}
                <button type="button" className="quiet" onClick={() => setRows(rows.filter((_, at) => at !== index))}>
                  Remove
                </button>
              </div>
            ))}
            {rows.length < form.maxSteps ? (
              <div>
                <button type="button" className="quiet" onClick={() => setRows([...rows, { title: "", minutes: "30", where: null, anyOrder: false, wasIndex: null, was: null }])}>
                  Add a step
                </button>
              </div>
            ) : null}
          </div>
        </>
      )}
      <label className="long">
        Notes
        <textarea value={notes} onChange={(event) => setNotes(event.target.value)} maxLength={form.maxNotes} rows={3} />
      </label>
      <button className="button" disabled={!title.trim()}>
        {edit ? "Save" : "Add"}
      </button>
      <button type="button" className="quiet" onClick={close}>
        Cancel
      </button>
      {said ? (
        <p className="problem" role="status">
          {said}
        </p>
      ) : null}
    </form>
  );
}
