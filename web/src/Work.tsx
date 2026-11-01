import { useCallback, useState } from "react";
import { api, saying } from "./api";
import { ago, useLive } from "./live";
import { NewTask } from "./NewTask";
import { TaskSheet } from "./TaskSheet";
import type { Action, TaskGroup, TaskTile, WorkView } from "./types";

type Open = (id: string) => void;

/** Whether two tasks are the one piece of work: yours to say, and the server's to act on. */
function SameLine({ task }: { task: TaskTile }) {
  const [said, setSaid] = useState<string | null>(null);
  const answer = (action: Action) => {
    setSaid(null);
    void saying(action).then(setSaid);
  };
  return (
    <span className="same">
      Looks like the same work as {task.sameAs}.
      {task.same && task.different ? (
        <>
          {" "}
          Same?{" "}
          <button className="quiet" onClick={() => task.same && answer(task.same)}>
            Same
          </button>{" "}
          <button className="quiet" onClick={() => task.different && answer(task.different)}>
            Different
          </button>
        </>
      ) : null}
      {said ? (
        <span className="problem" role="status">
          {" "}
          {said}
        </span>
      ) : null}
    </span>
  );
}

function Task({ task, open }: { task: TaskTile; open: Open }) {
  return (
    <li className="task" data-testid="task">
      <button className="title" onClick={() => open(task.id)}>
        {task.title}
      </button>
      <span className="minutes">{task.minutes}</span>
      <span className={task.overdue ? "due overdue" : "due"}>{task.due}</span>
      <span className="line" data-testid="task-line">
        {task.line}
      </span>
      {task.tags.length > 0 ? (
        <ul className="tags">
          {task.tags.map((tag) => (
            <li key={tag}>{tag}</li>
          ))}
        </ul>
      ) : null}
      {task.sameAs ? <SameLine task={task} /> : null}
    </li>
  );
}

function Column({ group, only, open }: { group: TaskGroup; only: string | null; open: Open }) {
  const tasks = group.tasks.filter((task) => only === null || task.source === only);
  if (tasks.length === 0) return null;
  return (
    <section className="column" data-testid="work-column">
      <h2>
        {group.title} <span className="count">{tasks.length}</span>
      </h2>
      <ul>
        {tasks.map((task) => (
          <Task key={task.id} task={task} open={open} />
        ))}
      </ul>
    </section>
  );
}

function Folded({ view, only, open }: { view: WorkView; only: string | null; open: Open }) {
  // Each fold is of one source's too, where one is chosen.
  const mine = <T extends { source: string }>(items: T[]) => items.filter((item) => only === null || item.source === only);
  const notPlanned = mine(view.notPlanned);
  const hidden = mine(view.hidden);
  const finished = mine(view.finished);
  return (
    <div className="folded">
      {notPlanned.length > 0 ? (
        <details>
          <summary>
            Not planned <span className="count">{notPlanned.length}</span>
          </summary>
          <ul>
            {notPlanned.map((gap) => (
              <li key={gap.id} className="task">
                <button className="title" onClick={() => open(gap.id)}>
                  {gap.title}
                </button>
                <span className="minutes">{gap.minutes}</span>
                <span className="line">{gap.why}</span>
              </li>
            ))}
          </ul>
        </details>
      ) : null}
      {hidden.length > 0 ? (
        <details>
          <summary>
            Hidden, as you said <span className="count">{hidden.length}</span>
          </summary>
          <ul>
            {hidden.map((task) => (
              <Task key={task.id} task={task} open={open} />
            ))}
          </ul>
        </details>
      ) : null}
      {finished.length > 0 ? (
        <details>
          <summary>
            Finished or missed lately <span className="count">{finished.length}</span>
          </summary>
          <ul>
            {finished.map((task) => (
              <Task key={task.id} task={task} open={open} />
            ))}
          </ul>
        </details>
      ) : null}
      <details>
        <summary>Sources</summary>
        <ul>
          {view.read.map((line) => (
            <li key={line.source} className="task">
              <span className="title">{line.source}</span>
              <span className="minutes">{line.open} open</span>
              <span className="line">{line.read}</span>
              {line.error ? <span className="due overdue">Last read failed: {line.error}</span> : null}
              {line.note ? <span className="line">{line.note}</span> : null}
            </li>
          ))}
        </ul>
      </details>
    </div>
  );
}

export function WorkPage() {
  const load = useCallback(() => api.work(), []);
  const { value: view, failed } = useLive(load);
  const [bySubject, setBySubject] = useState(false);
  const [only, setOnly] = useState<string | null>(null);
  const [opened, setOpened] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const shut = useCallback(() => setOpened(null), []);
  if (!view) return failed ? <p className="problem">Work couldn't be read. It tries again with the next change.</p> : null;
  const groups = bySubject ? view.bySubject : view.byDue;
  const shown = groups.some((group) => group.tasks.some((task) => only === null || task.source === only));
  return (
    <main className="work-page">
      <header>
        <h1>Work</h1>
        <div className="ways" role="group" aria-label="Group by">
          <button aria-pressed={!bySubject} onClick={() => setBySubject(false)}>
            By due date
          </button>
          <button aria-pressed={bySubject} onClick={() => setBySubject(true)}>
            By subject
          </button>
        </div>
        <div className="ways" role="group" aria-label="From">
          <button aria-pressed={only === null} onClick={() => setOnly(null)}>
            All
          </button>
          {view.sources.map((source) => (
            <button key={source} aria-pressed={only === source} onClick={() => setOnly(only === source ? null : source)}>
              {source}
            </button>
          ))}
        </div>
        {view.form ? (
          <button className="button" onClick={() => setAdding(true)}>
            New task
          </button>
        ) : null}
        <p className="fresh">{view.asOf ? `From the phone ${ago(view.asOf)}` : "The phone hasn't sent anything yet: pair it on the Phone page."}</p>
      </header>
      {adding && view.form ? <NewTask form={view.form} close={() => setAdding(false)} /> : null}
      {shown ? (
        <div className="columns">
          {groups.map((group) => (
            <Column key={group.title} group={group} only={only} open={setOpened} />
          ))}
        </div>
      ) : (
        <p className="empty">Nothing to do. New tasks appear as the sources are read, or add your own.</p>
      )}
      <Folded view={view} only={only} open={setOpened} />
      {opened ? <TaskSheet key={opened} id={opened} form={view.form ?? null} close={shut} /> : null}
    </main>
  );
}
