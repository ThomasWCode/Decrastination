import { useState } from "react";
import { saying } from "./TaskSheet";

/** A datetime-local field's words as a moment, or null where it's empty. */
export function moment(field: string): number | null {
  if (!field) return null;
  const at = new Date(field).getTime();
  return Number.isNaN(at) ? null : at;
}

/** A task of your own: its name, how long and when it's due. What the form won't take is the server's to say (`OwnTaskRules`). */
export function NewTask({ close }: { close: () => void }) {
  const [title, setTitle] = useState("");
  const [minutes, setMinutes] = useState("30");
  const [due, setDue] = useState("");
  const [said, setSaid] = useState<string | null>(null);

  const add = () => {
    setSaid(null);
    const task = { title: title.trim(), minutes: Number(minutes), dueAt: moment(due) };
    void saying({ type: "SaveOwnTask", sourceId: crypto.randomUUID(), task, fromForm: true }).then((why) => {
      if (why === null) close();
      else setSaid(why);
    });
  };

  return (
    <form
      className="new-task"
      aria-label="New task"
      onSubmit={(event) => {
        event.preventDefault();
        add();
      }}
    >
      <label>
        What
        <input value={title} onChange={(event) => setTitle(event.target.value)} autoFocus />
      </label>
      <label>
        Minutes
        <input value={minutes} onChange={(event) => setMinutes(event.target.value)} type="number" min="1" />
      </label>
      <label>
        Due
        <input value={due} onChange={(event) => setDue(event.target.value)} type="datetime-local" />
      </label>
      <button className="button" disabled={!title.trim()}>
        Add
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
