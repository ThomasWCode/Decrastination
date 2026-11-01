import { useCallback, useState } from "react";
import { api, saying } from "./api";
import { useLive } from "./live";
import type { Action, CredentialGroup, ListLine, PendingLine, SettingField, SettingsPage, SettingsView } from "./types";

type Value = unknown;

/** Minutes of the day as a time field writes them. */
function clock(minute: number): string {
  return `${String(Math.floor(minute / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
}

function minuteOf(time: string): number {
  const [hours, minutes] = time.split(":").map(Number);
  return (hours ?? 0) * 60 + (minutes ?? 0);
}

interface Window {
  startMin: number;
  endMin: number;
}

/** One field's input, by the kind the server says it is: what's typed goes back as the value it sent. */
function Input({ field, value, change, off }: { field: SettingField; value: Value; change: (value: Value) => void; off: boolean }) {
  const label = `${field.label}${field.waits ? " †" : ""}`;
  switch (field.kind) {
    case "switch":
      return (
        <label className="switch">
          <input type="checkbox" disabled={off} checked={value === true} onChange={(event) => change(event.target.checked)} />
          {label}
        </label>
      );
    case "time":
      return (
        <label>
          {label}
          <input type="time" disabled={off} value={clock(value as number)} onChange={(event) => change(minuteOf(event.target.value))} />
        </label>
      );
    case "window": {
      const window = value as Window;
      return (
        <fieldset disabled={off}>
          <legend>{label}</legend>
          <label>
            From
            <input type="time" value={clock(window.startMin)} onChange={(event) => change({ ...window, startMin: minuteOf(event.target.value) })} />
          </label>
          <label>
            To
            <input type="time" value={clock(window.endMin)} onChange={(event) => change({ ...window, endMin: minuteOf(event.target.value) })} />
          </label>
        </fieldset>
      );
    }
    case "number":
    case "optionalNumber":
      return (
        <label>
          {label}
          <input
            type="number"
            disabled={off}
            min={field.min ?? undefined}
            max={field.max ?? undefined}
            value={value === null ? "" : String(value)}
            onChange={(event) => change(event.target.value === "" ? null : Number(event.target.value))}
          />
        </label>
      );
    case "lines":
      return (
        <label className="long">
          {label}
          <textarea disabled={off} rows={4} value={(value as string[]).join("\n")} onChange={(event) => change(event.target.value.split("\n"))} />
        </label>
      );
    case "classes": {
      const classes = value as Record<string, string>;
      return (
        <fieldset className="long" disabled={off}>
          <legend>{label}</legend>
          {field.codes.map((code) => (
            <label key={code}>
              {code} is
              <input
                value={classes[code] ?? ""}
                maxLength={field.max ?? undefined}
                onChange={(event) => {
                  const rest = Object.fromEntries(Object.entries(classes).filter(([name]) => name !== code));
                  change(event.target.value ? { ...rest, [code]: event.target.value } : rest);
                }}
              />
            </label>
          ))}
        </fieldset>
      );
    }
    default:
      return (
        <label className="long">
          {label}
          <input disabled={off} value={(value as string | null) ?? ""} maxLength={field.max ?? undefined} onChange={(event) => change(event.target.value || (field.kind === "optionalText" ? null : ""))} />
        </label>
      );
  }
}

/** What a page changed from what the server sent: all that's sent, so a change made elsewhere to any other stands. */
function changes(fields: SettingField[], edits: Record<string, Value>): Record<string, Value> {
  const changed = Object.entries(edits).filter(([name, value]) => {
    const field = fields.find((one) => one.name === name);
    return field && JSON.stringify(field.value) !== JSON.stringify(cleaned(field, value));
  });
  return Object.fromEntries(changed.map(([name, value]) => [name, cleaned(fields.find((one) => one.name === name), value)]));
}

/** A list's lines as the phone keeps them: trimmed, none empty. */
function cleaned(field: SettingField | undefined, value: Value): Value {
  return field?.kind === "lines" ? (value as string[]).map((line) => line.trim()).filter(Boolean) : value;
}

function Fields({ page, canChange }: { page: SettingsPage; canChange: boolean }) {
  const [edits, setEdits] = useState<Record<string, Value>>({});
  const [said, setSaid] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const changed = changes(page.fields, edits);
  const save = () => {
    setSaid(null);
    setSaved(false);
    void saying({ type: "ChangeSettings", fields: changed }).then((why) => {
      setSaid(why);
      if (why === null) {
        setEdits({});
        setSaved(true);
      }
    });
  };
  return (
    <form
      className="settings-fields"
      onSubmit={(event) => {
        event.preventDefault();
        save();
      }}
    >
      {page.fields.map((field) => (
        <div className={field.kind === "lines" || field.kind === "classes" ? "setting long" : "setting"} key={field.name}>
          <Input
            field={field}
            value={field.name in edits ? edits[field.name] : field.value}
            off={!canChange}
            change={(value) => {
              setSaved(false);
              setEdits({ ...edits, [field.name]: value });
            }}
          />
          {field.note ? <p className="note">{field.note}</p> : null}
        </div>
      ))}
      {page.note ? <p className="note long">{page.note}</p> : null}
      {canChange ? (
        <div className="long">
          <button className="button" disabled={Object.keys(changed).length === 0}>
            Save
          </button>
          {saved ? <span className="said"> Saved.</span> : null}
          {said ? (
            <p className="problem" role="status">
              {said}
            </p>
          ) : null}
        </div>
      ) : null}
    </form>
  );
}

function Lines({ lines, says }: { lines: ListLine[]; says: (why: string | null) => void }) {
  return (
    <ul className="lines">
      {lines.map((line) => (
        <li key={line.line}>
          <span>{line.line}</span>
          {line.delete ? (
            <button className="quiet" aria-label={`Delete ${line.line}`} onClick={() => line.delete && void saying(line.delete).then(says)}>
              Delete
            </button>
          ) : null}
        </li>
      ))}
    </ul>
  );
}

function School({ view }: { view: SettingsView }) {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [label, setLabel] = useState("");
  const [half, setHalf] = useState("");
  const [ends, setEnds] = useState("12:30");
  const [said, setSaid] = useState<string | null>(null);
  const add = (action: Action) => void saying(action).then(setSaid);
  return (
    <section>
      {view.school.length === 0 ? <p className="note">No days off or half-days yet. A day off is planned as a weekend is.</p> : null}
      <Lines lines={view.school} says={setSaid} />
      {view.canChange ? (
        <>
          <form
            className="adding"
            aria-label="Days off"
            onSubmit={(event) => {
              event.preventDefault();
              if (from) add({ type: "AddNoSchool", entry: { from, to: to || from, label: label.trim() } });
            }}
          >
            <label>
              First day off
              <input type="date" value={from} onChange={(event) => setFrom(event.target.value)} />
            </label>
            <label>
              Last day off
              <input type="date" value={to} min={from} onChange={(event) => setTo(event.target.value)} />
            </label>
            <label>
              What it is
              <input value={label} maxLength={60} onChange={(event) => setLabel(event.target.value)} />
            </label>
            <button className="button" disabled={!from}>
              Add days off
            </button>
          </form>
          <form
            className="adding"
            aria-label="A half-day"
            onSubmit={(event) => {
              event.preventDefault();
              if (half) add({ type: "AddHalfDay", entry: { date: half, endsAtMin: minuteOf(ends) } });
            }}
          >
            <label>
              A half-day
              <input type="date" value={half} onChange={(event) => setHalf(event.target.value)} />
            </label>
            <label>
              Ends
              <input type="time" value={ends} onChange={(event) => setEnds(event.target.value)} />
            </label>
            <button className="button" disabled={!half}>
              Add a half-day
            </button>
          </form>
        </>
      ) : null}
      {said ? (
        <p className="problem" role="status">
          {said}
        </p>
      ) : null}
    </section>
  );
}

function Remembered({ view }: { view: SettingsView }) {
  const [rule, setRule] = useState("");
  const [said, setSaid] = useState<string | null>(null);
  const add = () => {
    const text = rule.trim();
    setRule("");
    void saying({ type: "Remember", given: [{ text, kind: "rule" }] }).then(setSaid);
  };
  return (
    <section>
      {view.rules.length > 0 ? <h3>How Felix should behave</h3> : null}
      <Lines lines={view.rules} says={setSaid} />
      {view.facts.length > 0 ? <h3>Remembered, newest first</h3> : null}
      <Lines lines={view.facts} says={setSaid} />
      {view.factsRoom ? <p className="note">{view.factsRoom}</p> : null}
      {view.canChange ? (
        <form
          className="adding"
          aria-label="A standing instruction"
          onSubmit={(event) => {
            event.preventDefault();
            if (rule.trim()) add();
          }}
        >
          <label className="long">
            A standing instruction for Felix
            <input value={rule} maxLength={300} onChange={(event) => setRule(event.target.value)} />
          </label>
          <button className="button" disabled={!rule.trim()}>
            Add
          </button>
        </form>
      ) : null}
      {said ? (
        <p className="problem" role="status">
          {said}
        </p>
      ) : null}
    </section>
  );
}

/** Sign-ins, entered here and kept sealed on the server (W-19): it says which it holds, never what they are. */
function Credentials({ groups }: { groups: CredentialGroup[] }) {
  const [typed, setTyped] = useState<Record<string, string>>({});
  // What it says it holds, from its answer: a sign-in is in no store, so no revision says it's changed.
  const [held, setHeld] = useState<string[] | null>(null);
  const [said, setSaid] = useState<string | null>(null);
  const save = () => {
    setSaid(null);
    api
      .setCredentials(typed)
      .then((answer) => {
        setTyped({});
        setHeld(answer.held);
      })
      .catch(() => setSaid("That didn't reach the server. Try again."));
  };
  return (
    <form
      className="credentials"
      aria-label="Sign-ins"
      onSubmit={(event) => {
        event.preventDefault();
        save();
      }}
    >
      {groups.map((group) => (
        <fieldset key={group.title}>
          <legend>{group.title}</legend>
          {group.fields.map((field) => (
            <label key={field.name}>
              {field.label}
              <input
                type={field.secret ? "password" : "text"}
                autoComplete="off"
                placeholder={(held?.includes(field.name) ?? field.held) ? "Held" : "Not set"}
                value={typed[field.name] ?? ""}
                onChange={(event) => setTyped({ ...typed, [field.name]: event.target.value })}
              />
            </label>
          ))}
        </fieldset>
      ))}
      <button className="button" disabled={Object.keys(typed).length === 0}>
        Save sign-ins
      </button>
      {said ? (
        <p className="problem" role="status">
          {said}
        </p>
      ) : null}
    </form>
  );
}

/** What's waiting its hours: to cancel, or to apply now by a parent's code, which the holder types in seeing what it's for. */
function Waiting({ line, parentCode }: { line: PendingLine; parentCode: boolean }) {
  const [asking, setAsking] = useState(false);
  const [code, setCode] = useState("");
  const [said, setSaid] = useState<string | null>(null);
  const apply = () => {
    setCode("");
    void saying({ type: "ApplyChangeNow", id: line.id, code }).then(setSaid);
  };
  return (
    <li>
      <span>{line.line}</span>
      {line.cancel ? (
        <button className="quiet" onClick={() => line.cancel && void saying(line.cancel).then(setSaid)}>
          Cancel
        </button>
      ) : null}
      {parentCode ? (
        <button className="quiet" onClick={() => setAsking(!asking)}>
          Parent code
        </button>
      ) : null}
      {asking ? (
        <form
          className="adding"
          aria-label="Parent code"
          onSubmit={(event) => {
            event.preventDefault();
            apply();
          }}
        >
          <p className="note long">For the code's holder to type in, seeing this: it applies this change now.</p>
          <label>
            Code
            <input inputMode="numeric" autoComplete="off" maxLength={6} value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, ""))} />
          </label>
          <button className="button" disabled={code.length !== 6}>
            Apply
          </button>
        </form>
      ) : null}
      {said ? (
        <p className="problem" role="status">
          {said}
        </p>
      ) : null}
    </li>
  );
}

/** The settings that are shared state, a page at a time: what each takes is the server's to say, and a loosening waits there whichever client asks. */
export function SettingsPageView() {
  const load = useCallback(() => api.settings(), []);
  const { value: view, failed } = useLive(load);
  const [shown, setShown] = useState("about");
  if (!view) return failed ? <p className="problem">Settings couldn't be read. It tries again with the next change.</p> : null;
  const page = view.pages.find((one) => one.key === shown) ?? view.pages[0];
  if (!page) return null;
  return (
    <main className="settings-page">
      <header>
        <h1>Settings</h1>
        {view.canChange ? null : <p className="fresh">Read only for now: change these on the phone.</p>}
        {view.armed ? <p className="fresh">Protection is armed: a change to one marked † that loosens blocking waits {view.delayHours} hours.</p> : null}
      </header>
      {view.pending.length > 0 ? (
        <section className="waiting">
          <h2>Waiting</h2>
          <ul className="lines">
            {view.pending.map((line) => (
              <Waiting key={line.id} line={line} parentCode={view.parentCode} />
            ))}
          </ul>
        </section>
      ) : null}
      <div className="settings">
        <nav aria-label="Settings">
          {view.pages.map((one) => (
            <button key={one.key} aria-pressed={one.key === page.key} onClick={() => setShown(one.key)}>
              <span className="title">{one.title}</span>
              <span className="line">{one.says}</span>
            </button>
          ))}
        </nav>
        <div className="settings-body">
          <h2>{page.title}</h2>
          {page.key === "school" ? <School view={view} /> : null}
          {page.fields.length > 0 ? <Fields key={page.key} page={page} canChange={view.canChange} /> : null}
          {page.key === "felix" ? <Remembered view={view} /> : null}
          {page.key === "sources" && view.canChange ? <Credentials groups={view.credentials} /> : null}
        </div>
      </div>
    </main>
  );
}
