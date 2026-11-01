import { useCallback, useState } from "react";
import { api, checkIn, telling } from "./api";
import { useLive } from "./live";
import type { Action, ReviewCard, ReviewLine, WeekView } from "./types";

/** The week's check-in (LR-02): what's typed is held here till it's kept, and asked for again by nothing. */
function CheckInForm({ view }: { view: WeekView }) {
  const [feel, setFeel] = useState(0);
  const [words, setWords] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [said, setSaid] = useState<string | null>(null);
  const save = () => {
    setBusy(true);
    setSaid(null);
    void checkIn({ week: view.week, feel, ...words }).then((answer) => {
      setBusy(false);
      if (!answer.done) setSaid(answer.said ?? "That couldn't be kept.");
    });
  };
  return (
    <form
      className="check-in"
      aria-label="This week's check-in"
      onSubmit={(event) => {
        event.preventDefault();
        save();
      }}
    >
      <div role="group" aria-label={view.feel}>
        <p className="question">{view.feel}</p>
        <div className="ways">
          {[1, 2, 3, 4, 5].map((n) => (
            <button key={n} type="button" aria-pressed={feel === n} onClick={() => setFeel(n)}>
              {n}
            </button>
          ))}
        </div>
        <p className="note">{view.feelNote}</p>
      </div>
      {view.questions.map((question) => (
        <label key={question.name}>
          {question.label}
          <textarea rows={2} value={words[question.name] ?? ""} onChange={(event) => setWords({ ...words, [question.name]: event.target.value })} />
        </label>
      ))}
      <button className="button" disabled={feel === 0 || busy}>
        {view.save}
      </button>
      {said ? (
        <p className="problem" role="status">
          {said}
        </p>
      ) : null}
    </form>
  );
}

export function WeekPage() {
  const load = useCallback(() => api.week(), []);
  const { value: view, failed } = useLive(load);
  if (!view) return failed ? <p className="problem">This week couldn't be read. It tries again with the next change.</p> : null;
  return (
    <main className="week-page">
      <h1>This week</h1>
      {view.done ? <p>{view.doneWords}</p> : view.canChange ? <CheckInForm key={view.week} view={view} /> : <p className="note">The check-in's on the phone for now.</p>}
      {view.latest ? (
        <section aria-label={view.latest.heading}>
          <h2>{view.latest.heading}</h2>
          <ul className="stat-lines">
            {view.latest.lines.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
          <p className="note">{view.latest.by}</p>
        </section>
      ) : null}
    </main>
  );
}

/** A line of a review with its Undo, while what it did stands. */
function Line({ line, say, busy }: { line: ReviewLine; say: (undo: Action) => void; busy: boolean }) {
  const undo = line.undo;
  return (
    <li>
      <span>{line.text}</span>
      {undo ? (
        <button className="quiet" disabled={busy} onClick={() => say(undo)}>
          Undo
        </button>
      ) : null}
    </li>
  );
}

function Card({ card, say, busy }: { card: ReviewCard; say: (undo: Action) => void; busy: boolean }) {
  const summary = card.summary;
  return (
    <section className="review-card" aria-label={card.week} data-testid="review">
      <h2>{card.week}</h2>
      <p className="note">{card.by}</p>
      {card.lines.map((line) => (
        <p key={line}>{line}</p>
      ))}
      {card.changes.length > 0 || card.tidy ? (
        <ul className="stat-lines">
          {card.changes.map((line) => (
            <Line key={line.text} line={line} say={say} busy={busy} />
          ))}
          {card.tidy ? <Line line={card.tidy} say={say} busy={busy} /> : null}
        </ul>
      ) : null}
      {summary ? (
        <>
          <p className="note">The week's summary</p>
          <p className="review-summary">{summary}</p>
          <div className="buttons">
            {card.mailto ? (
              <a className="quiet" href={card.mailto}>
                Send it
              </a>
            ) : null}
            <button className="quiet" onClick={() => void navigator.clipboard.writeText(summary)}>
              Copy
            </button>
          </div>
        </>
      ) : null}
      {card.checkIn ? <p className="note">{card.checkIn}</p> : null}
    </section>
  );
}

export function ReviewsPage() {
  const load = useCallback(() => api.reviews(), []);
  const { value: view, failed } = useLive(load);
  const [said, setSaid] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (!view) return failed ? <p className="problem">The reviews couldn't be read. It tries again with the next change.</p> : null;
  // One at a time: a second press of the same Undo would say it had changed since, over what the first came to.
  const say = (undo: Action) => {
    setBusy(true);
    void telling(undo).then((words) => {
      setBusy(false);
      setSaid(words);
    });
  };
  return (
    <main className="week-page">
      <h1>Reviews</h1>
      {said ? <p role="status">{said}</p> : null}
      {view.none ? <p>{view.none}</p> : null}
      {view.cards.map((card) => (
        <Card key={card.id} card={card} say={say} busy={busy} />
      ))}
    </main>
  );
}
