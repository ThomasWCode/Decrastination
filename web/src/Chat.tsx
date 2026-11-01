import { useCallback, useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { api, readTo, saying, send, tryAgain } from "./api";
import { Link, type Go } from "./App";
import { useLive } from "./live";
import { NewTask } from "./NewTask";
import type { Action, ChangeRow, ChatButton, ChatCard, ChatChip, ChatQuote, ChatRow, ChatView, OwnTaskFormView } from "./types";
import { Visit } from "./visit";

/** How close to the top of the list scrolling back loads the archive's next page, and to the foot counts as being there. */
const NEAR_TOP = 120;
const NEAR_FOOT = 48;

const DRAFT = "chat-draft";

/** What a button came to, in a line under it where that wasn't what was asked. */
function Said({ said }: { said: string | null }) {
  return said ? (
    <p className="problem" role="status">
      {said}
    </p>
  ) : null;
}

/** The buttons of a card or a change: sent at a press, asked first where they say so, or by way of the form. */
function Buttons({ buttons, form }: { buttons: ChatButton[]; form: OwnTaskFormView | null }) {
  const [busy, setBusy] = useState(false);
  const [said, setSaid] = useState<string | null>(null);
  const [editing, setEditing] = useState<ChatButton | null>(null);
  const press = (button: ChatButton) => {
    if (button.edit) {
      setEditing(button);
      return;
    }
    if (!button.action) return;
    setBusy(true);
    setSaid(null);
    void saying(button.action).then((why) => {
      setBusy(false);
      setSaid(why);
    });
  };
  const carrying = editing?.action;
  return (
    <>
      <div className="chat-buttons">
        {buttons.map((button) => (
          <button key={button.label} className={button.primary ? "button" : "quiet"} disabled={busy} onClick={() => press(button)}>
            {button.label}
          </button>
        ))}
      </div>
      <Said said={said} />
      {editing?.edit && form ? (
        <NewTask
          form={form}
          edit={carrying ? undefined : editing.edit}
          from={carrying ? editing.edit : undefined}
          making={carrying ? (task) => ({ ...carrying, edited: task }) : undefined}
          close={() => setEditing(null)}
        />
      ) : null}
    </>
  );
}

function Card({ card, form, go }: { card: ChatCard; form: OwnTaskFormView | null; go: Go }) {
  return (
    <section className={card.aside ? "chat-card aside" : "chat-card"} aria-label={card.title ?? card.heading}>
      <p className="heading">{card.heading}</p>
      {card.title ? <p className="title">{card.title}</p> : null}
      {card.line ? <p>{card.line}</p> : null}
      {card.steps.length > 0 ? (
        <ul>
          {card.steps.map((step) => (
            <li key={step}>{step}</li>
          ))}
        </ul>
      ) : null}
      {card.notes ? <p className="notes">{card.notes}</p> : null}
      {card.lines.length > 0 ? (
        <ul>
          {card.lines.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      ) : null}
      {card.note ? <p className="problem">{card.note}</p> : null}
      {card.open ? (
        <button className="button" onClick={() => go(`/work?task=${encodeURIComponent(card.open ?? "")}`)}>
          Open
        </button>
      ) : null}
      {card.state ? <p className="state">{card.state}</p> : null}
      {card.buttons.length > 0 ? <Buttons buttons={card.buttons} form={form} /> : null}
    </section>
  );
}

function Did({ row }: { row: ChatRow }) {
  const [said, setSaid] = useState<string | null>(null);
  return (
    <>
      {row.lines.map((line, index) => (
        <p key={index} className="chat-did">
          <span className={line.undone ? "undone" : undefined}>{line.line}</span>
          {line.undone ? <span className="state">Undone</span> : null}
          {line.undo ? (
            <button className="quiet" onClick={() => line.undo && void saying(line.undo).then(setSaid)}>
              Undo
            </button>
          ) : null}
        </p>
      ))}
      <Said said={said} />
    </>
  );
}

/** What's under one of his messages: what he looked up, his cards, what he remembered and did, each with its Undo. */
function Under({ row, form, go }: { row: ChatRow; form: OwnTaskFormView | null; go: Go }) {
  return (
    <div className="chat-under">
      {row.searched ? <p className="chat-did">{row.searched}</p> : null}
      {row.sources.map((source) =>
        source.url ? (
          <a key={source.url} className="chat-source" href={source.url} target="_blank" rel="noreferrer noopener">
            {source.title}
          </a>
        ) : (
          <p key={source.title} className="chat-source">
            {source.title}
          </p>
        ),
      )}
      {row.cards.map((card) => (
        <Card key={card.id} card={card} form={form} go={go} />
      ))}
      <Did row={row} />
      {row.said ? <p className="chat-did">{row.said}</p> : null}
    </div>
  );
}

/** Your rating of one of his (D-119): your thumb, what you'd say of it, and which of the texts he didn't send you'd rather have had (D-120). */
function Rate({ row, up, max, close }: { row: ChatRow; up: boolean; max: number; close: () => void }) {
  const [words, setWords] = useState(row.feedback);
  const [preferred, setPreferred] = useState(row.preferred);
  const [said, setSaid] = useState<string | null>(null);
  const rated = (thumb: boolean | null, feedback: string, pick: string) => {
    void saying({ type: "RateMessage", id: row.id, up: thumb, feedback, preferred: pick }).then((why) => {
      if (why === null) close();
      else setSaid(why);
    });
  };
  return (
    <form
      className="chat-rate"
      aria-label={up ? "Thumbs up" : "Thumbs down"}
      onSubmit={(event) => {
        event.preventDefault();
        rated(up, words, preferred);
      }}
    >
      {row.feeling ? (
        <div>
          <p className="heading">His note as he wrote it</p>
          <p className="feeling">{row.feeling}</p>
        </div>
      ) : null}
      {row.unsent.length > 0 ? (
        <div>
          <p className="heading">He also wrote: tap one you'd rather have had</p>
          {row.unsent.map((text) => (
            <button key={text} type="button" className="unsent" aria-pressed={text === preferred} onClick={() => setPreferred(text === preferred ? "" : text)}>
              {text}
            </button>
          ))}
        </div>
      ) : null}
      <label>
        {up ? "What was good? (optional)" : "What's off? (optional)"}
        <textarea value={words} onChange={(event) => setWords(event.target.value)} maxLength={max} rows={3} />
      </label>
      <div className="chat-buttons">
        <button className="button">Save</button>
        {row.rating ? (
          <button type="button" className="quiet" onClick={() => rated(null, "", "")}>
            Remove
          </button>
        ) : null}
        <button type="button" className="quiet" onClick={close}>
          Cancel
        </button>
      </div>
      <Said said={said} />
    </form>
  );
}

interface Doing {
  reply: (row: ChatRow) => void;
  mark: (action: Action) => void;
  quoted: (id: string) => void;
}

function Message({ row, view, doing, go }: { row: ChatRow; view: ChatView; doing: Doing; go: Go }) {
  const [open, setOpen] = useState(false);
  const [rating, setRating] = useState<boolean | null>(null);
  const [said, setSaid] = useState<string | null>(null);
  const copy = () => void navigator.clipboard.writeText(row.texts.join("\n"));
  const again = () => void tryAgain().then(setSaid);
  const back = (action: Action) => void saying(action).then(setSaid);
  const rated = row.rating === "Up" ? "thumbs up" : row.rating === "Down" ? "thumbs down" : null;
  return (
    <article className={row.yours ? "chat-message yours" : "chat-message his"} id={`message-${row.id}`} data-testid="message">
      {row.texts.map((text, index) => (
        <div className="bubble" key={index}>
          {index === 0 && row.quote ? <Quoted quote={row.quote} open={doing.quoted} /> : null}
          {index === 0 && row.about ? <p className="about">{row.about}</p> : null}
          <p className="words">{text}</p>
        </div>
      ))}
      <p className="time">
        {row.time}
        {row.waiting ? <span className="waiting"> · waiting for your answer</span> : null}
        {rated ? (
          <button className="rated" onClick={() => setRating(row.rating === "Up")}>
            Rated {rated}: change
          </button>
        ) : null}
        <span className="tools">
          {view.canChange ? (
            <button className="quiet" onClick={() => doing.reply(row)}>
              Reply
            </button>
          ) : null}
          {row.mark ? (
            <button className="quiet" onClick={() => row.mark && doing.mark(row.mark)}>
              Mark answered
            </button>
          ) : null}
          <button className="quiet" onClick={copy}>
            Copy
          </button>
          {row.ratable ? (
            <>
              <button className="quiet" onClick={() => setRating(true)}>
                Thumbs up
              </button>
              <button className="quiet" onClick={() => setRating(false)}>
                Thumbs down
              </button>
            </>
          ) : null}
        </span>
      </p>
      {row.note ? <p className="problem">{row.note}</p> : null}
      {row.tryAgain || row.takeBack ? (
        <div className="chat-buttons">
          {row.tryAgain ? (
            <button className="quiet" onClick={again}>
              Try again
            </button>
          ) : null}
          {row.takeBack ? (
            <button className="quiet" onClick={() => row.takeBack && back(row.takeBack)}>
              Take back
            </button>
          ) : null}
        </div>
      ) : null}
      <Said said={said} />
      {rating !== null ? <Rate key={String(rating)} row={row} up={rating} max={view.maxFeedback} close={() => setRating(null)} /> : null}
      {row.shown.map((card) => (
        <Card key={card.id} card={card} form={view.form ?? null} go={go} />
      ))}
      {row.folds ? (
        <>
          <button className={row.problem ? "chat-fold problem" : "chat-fold"} aria-expanded={open} onClick={() => setOpen(!open)}>
            <span className="visually-hidden">{open ? "Hide what he did" : "Show what he did"}</span>
            {row.did ? <span>{row.did}</span> : null}
            {row.did && row.wrong ? <span aria-hidden="true"> · </span> : null}
            {row.wrong ? <span className="wrong">{row.wrong}</span> : null}
            <span className="chevron" aria-hidden="true" />
          </button>
          {open ? <Under row={row} form={view.form ?? null} go={go} /> : null}
        </>
      ) : null}
    </article>
  );
}

function Quoted({ quote, open }: { quote: ChatQuote; open: (id: string) => void }) {
  return (
    <button className="chat-quote" onClick={() => open(quote.id)}>
      <span className="who">{quote.who}</span>
      <span className="line">{quote.line}</span>
    </button>
  );
}

function Typing({ view }: { view: ChatView }) {
  const [long, setLong] = useState(false);
  const since = view.sentAt ?? null;
  useEffect(() => {
    if (since === null) return;
    const timer = setTimeout(() => setLong(true), Math.max(0, since + view.cancelAfterMs - Date.now()));
    return () => {
      clearTimeout(timer);
      setLong(false);
    };
  }, [since, view.cancelAfterMs]);
  return (
    <div className="chat-typing">
      <span className="dots" role="status" aria-label={`${view.name} is typing`}>
        <span />
        <span />
        <span />
      </span>
      {long && view.cancel ? (
        <button className="quiet" onClick={() => view.cancel && void saying(view.cancel)}>
          Cancel
        </button>
      ) : null}
    </div>
  );
}

/** One of your changes (docs/spec/felix.md §Your changes): Claude's reading of it, and what's to do with it. */
function Change({ row, max, form }: { row: ChangeRow; max: number; form: OwnTaskFormView | null }) {
  const [words, setWords] = useState<string | null>(null);
  const [said, setSaid] = useState<string | null>(null);
  return (
    <li className="chat-change" data-testid="your-change">
      <p className="heading">{row.about}</p>
      <p className="words">“{row.words}”</p>
      {row.says ? <p className={row.problem ? "problem" : row.reading ? "reading" : undefined}>{row.says}</p> : null}
      {row.heading ? <p className="heading">{row.heading}</p> : null}
      {row.lines.length > 0 ? (
        <ul>
          {row.lines.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      ) : null}
      {row.note ? <p className="problem">{row.note}</p> : null}
      {words !== null ? (
        <form
          aria-label="Write it again"
          onSubmit={(event) => {
            event.preventDefault();
            void saying({ type: "RereadInstruction", id: row.id, text: words }).then((why) => {
              setSaid(why);
              if (why === null) setWords(null);
            });
          }}
        >
          <label>
            In your words
            <textarea value={words} onChange={(event) => setWords(event.target.value)} maxLength={max} rows={3} autoFocus />
          </label>
          <div className="chat-buttons">
            <button className="button" disabled={!words.trim()}>
              Send to Claude
            </button>
            <button type="button" className="quiet" onClick={() => setWords(null)}>
              Cancel
            </button>
          </div>
        </form>
      ) : null}
      {row.rewrite && words === null ? (
        <button className="button" onClick={() => setWords(row.words)}>
          Write again
        </button>
      ) : null}
      <Said said={said} />
      {row.buttons.length > 0 ? <Buttons buttons={row.buttons} form={form} /> : null}
    </li>
  );
}

function Changes({ view }: { view: ChatView }) {
  const change = (row: ChangeRow) => <Change key={row.id} row={row} max={view.maxText} form={view.form ?? null} />;
  return (
    <aside className="chat-changes" aria-label="Your changes">
      <h2>
        Your changes {view.toCheck.length > 0 ? <span className="count">{view.toCheck.length}</span> : null}
      </h2>
      {view.toCheck.length > 0 ? (
        <>
          <h3>To check</h3>
          <ul>{view.toCheck.map(change)}</ul>
        </>
      ) : null}
      {view.inUse.length > 0 ? (
        <>
          <h3>In use</h3>
          <ul>{view.inUse.map(change)}</ul>
        </>
      ) : (
        <p className="empty">No changes in use. What you apply from the chat is listed here, and taken back here too.</p>
      )}
    </aside>
  );
}

interface Undoing {
  said: string;
  undo: Action;
}

/** The box you write in, with what it replies to over it. What's unsent stays through a move to another page. */
function Box({ view, replying, chosen, sent, letGo }: { view: ChatView; replying: ChatQuote | null; chosen: boolean; sent: () => void; letGo: () => void }) {
  const [draft, setDraft] = useState(() => sessionStorage.getItem(DRAFT) ?? "");
  const [said, setSaid] = useState<string | null>(null);
  // One that may have got there unheard goes again as itself, so it's the one message.
  const lost = useRef<{ text: string; id: string } | null>(null);
  const change = (text: string) => {
    setDraft(text);
    sessionStorage.setItem(DRAFT, text);
  };
  const go = () => {
    const text = draft.trim();
    if (!text) return;
    const id = lost.current?.text === text ? lost.current.id : crypto.randomUUID();
    change("");
    setSaid(null);
    sent();
    const replyTo = replying?.id ?? view.quiet ?? null;
    void saying({ type: "SendMessage", text, id, replyTo, takeItsSubject: replying !== null }).then((why) => {
      if (why === null) {
        lost.current = null;
        return;
      }
      lost.current = { text, id };
      setSaid(why);
      setDraft((typed) => {
        const back = typed ? `${text}\n\n${typed}` : text;
        sessionStorage.setItem(DRAFT, back);
        return back;
      });
    });
  };
  const key = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== "Enter" || event.shiftKey) return;
    event.preventDefault();
    go();
  };
  return (
    <form
      className="chat-box"
      aria-label={`Write to ${view.name}`}
      onSubmit={(event) => {
        event.preventDefault();
        go();
      }}
    >
      {replying ? (
        <div className="replying">
          <span>
            <span className="who">{replying.who === "You" ? "Replying to you" : `Replying to ${replying.who}`}</span>
            <span className="line">{replying.line}</span>
          </span>
          <button type="button" className="quiet" aria-label={chosen ? "Not replying" : "Not replying to this"} onClick={letGo}>
            ×
          </button>
        </div>
      ) : null}
      <div className="writing">
        <textarea aria-label={`Tell ${view.name}…`} placeholder={`Tell ${view.name}…`} value={draft} onChange={(event) => change(event.target.value)} onKeyDown={key} maxLength={view.maxText} rows={2} />
        <button className="button" disabled={!draft.trim()}>
          Send
        </button>
      </div>
      <Said said={said} />
    </form>
  );
}

function Foot({ children }: { children: ReactNode }) {
  return <div className="chat-foot">{children}</div>;
}

/** The chat, the website's home (D-121): the phone's, a message at a time, and what's still to come read as it's looked at (Q120). */
export function ChatPage({ go }: { go: Go }) {
  const [earlier, setEarlier] = useState(0);
  const [reach, setReach] = useState<string | null>(null);
  // Asked again once a wait's gone on: that a call's running changes no store, so nothing else says it has.
  const [looked, setLooked] = useState(0);
  const load = useCallback(() => api.chat(earlier, reach), [earlier, reach, looked]);
  const { value: view, failed } = useLive(load);

  // The line over what was new, kept for this visit: the chat's own moves as it's read.
  const [line, setLine] = useState<string | null | undefined>(undefined);
  const leftFrom = useRef<ChatView | null>(null);
  if (view && line === undefined && view !== leftFrom.current) setLine(view.firstUnread ?? null);

  const [chosen, setChosen] = useState<ChatRow | null>(null);
  const [declined, setDeclined] = useState<string | null>(null);
  const [undoing, setUndoing] = useState<Undoing | null>(null);
  const [asking, setAsking] = useState<ChatButton | null>(null);
  const [said, setSaid] = useState<string | null>(null);

  const list = useRef<HTMLDivElement>(null);
  const atFoot = useRef(true);
  const placed = useRef(false);
  const height = useRef(0);
  const loading = useRef(false);
  const target = useRef<string | null>(null);
  const visit = useRef<Visit | null>(null);
  const shown = useRef(view);
  useEffect(() => {
    shown.current = view;
  });

  const afterMs = view?.readAfterMs ?? null;
  useEffect(() => {
    if (afterMs === null) return;
    const here = new Visit(afterMs);
    visit.current = here;
    const leave = () => {
      const seen = here.left();
      const was = shown.current;
      if (seen && was?.canChange && was.newestAt) readTo(was.newestAt);
      leftFrom.current = was ?? null;
      setLine(undefined);
    };
    const moved = () => {
      if (document.hidden) leave();
      else here.shown();
    };
    if (!document.hidden) here.shown();
    document.addEventListener("visibilitychange", moved);
    return () => {
      document.removeEventListener("visibilitychange", moved);
      leave();
    };
  }, [afterMs]);

  const rows = view?.rows;
  const typing = view?.typing;
  const cancelAfterMs = view?.cancelAfterMs;
  useEffect(() => {
    if (!typing || cancelAfterMs === undefined) return;
    const timer = setTimeout(() => setLooked((times) => times + 1), cancelAfterMs);
    return () => clearTimeout(timer);
  }, [typing, cancelAfterMs]);
  useLayoutEffect(() => {
    const box = list.current;
    if (!box || !rows) return;
    const row = (id: string | null | undefined) => (id ? document.getElementById(`message-${id}`) : null);
    if (!placed.current) {
      placed.current = true;
      const first = row(line);
      if (first) first.scrollIntoView({ block: "start" });
      else box.scrollTop = box.scrollHeight;
    } else if (loading.current) {
      loading.current = false;
      box.scrollTop += box.scrollHeight - height.current;
    } else if (atFoot.current) {
      box.scrollTop = box.scrollHeight;
    }
    const wanted = row(target.current);
    if (wanted) {
      wanted.scrollIntoView({ block: "center" });
      target.current = null;
    }
    height.current = box.scrollHeight;
  }, [rows, typing, line]);

  if (!view) return failed ? <p className="problem">The chat couldn't be read. It tries again with the next change.</p> : null;

  const offered = view.offer && view.offer.id !== declined ? view.offer : null;
  const picked = chosen && view.rows.some((row) => row.id === chosen.id) ? chosen : null;
  const replying: ChatQuote | null = picked ? { id: picked.id, who: picked.yours ? "You" : view.name, line: (picked.texts.at(-1) ?? "").trim().split("\n")[0] ?? "" } : offered;
  const own = view.rows.find((row) => row.id === picked?.id)?.chips ?? [];
  const chips: ChatChip[] = own.length > 0 ? own : view.chips;

  const scrolled = () => {
    const box = list.current;
    if (!box) return;
    atFoot.current = box.scrollHeight - box.scrollTop - box.clientHeight < NEAR_FOOT;
    if (box.scrollTop < NEAR_TOP && view.more && !loading.current) earlierPage();
  };
  const earlierPage = () => {
    if (list.current) height.current = list.current.scrollHeight;
    loading.current = true;
    setEarlier(view.earlier + view.page);
  };
  const quoted = (id: string) => {
    target.current = id;
    const there = document.getElementById(`message-${id}`);
    if (there) {
      there.scrollIntoView({ block: "center" });
      target.current = null;
    } else {
      setReach(id);
    }
  };
  const mark = (action: Action) => {
    void send(action)
      .then((outcome) => {
        const undo = outcome.undo;
        if (!undo) return;
        const marked = undo.marked as { ids: string[] };
        setUndoing({ said: marked.ids.length === 1 ? "Marked answered" : `${marked.ids.length} marked answered`, undo });
        setTimeout(() => setUndoing((now) => (now?.undo === undo ? null : now)), view.undoMs);
      })
      .catch(() => setSaid("That didn't reach the server. Try again."));
  };
  const answered = (chip: ChatChip) => {
    if (picked) setChosen(null);
    visit.current?.sent();
    void saying(chip.send).then(setSaid);
  };
  const clear = () => {
    const action = asking?.action;
    setAsking(null);
    if (action) void saying(action).then(setSaid);
  };
  const doing: Doing = { reply: setChosen, mark, quoted };

  return (
    <main className="chat-page">
      <section className="chat" aria-label={view.name}>
        <header>
          <h1>{view.name}</h1>
          <div className="chat-buttons">
            {view.markAll ? (
              <button className="quiet" onClick={() => view.markAll && mark(view.markAll)}>
                Mark all answered
              </button>
            ) : null}
            <Link to="/settings" go={go}>
              What {view.name} remembers
            </Link>
            {view.clear ? (
              <button className="quiet" onClick={() => setAsking(view.clear ?? null)}>
                {view.clear.label}
              </button>
            ) : null}
          </div>
        </header>
        {asking ? (
          <div className="asking" role="alertdialog" aria-label={asking.confirm ?? asking.label}>
            <p className="title">{asking.confirm}</p>
            {asking.detail ? <p>{asking.detail}</p> : null}
            <div className="buttons">
              <button className="button" onClick={clear}>
                Clear
              </button>
              <button className="quiet" onClick={() => setAsking(null)}>
                Cancel
              </button>
            </div>
          </div>
        ) : null}
        <div className="chat-list" ref={list} onScroll={scrolled} data-testid="chat-list">
          {view.claudeOff ? <p className="problem">{view.claudeOff}</p> : null}
          {view.intro ? (
            <div className="chat-intro">
              <p>{view.intro}</p>
              <ul>
                {view.examples.map((example) => (
                  <li key={example}>“{example}”</li>
                ))}
              </ul>
            </div>
          ) : null}
          {view.more ? (
            <button className="quiet earlier" onClick={earlierPage}>
              Earlier messages
            </button>
          ) : null}
          {view.rows.map((row) => (
            <div key={row.id}>
              {row.day ? <p className="chat-day">{row.day}</p> : null}
              {row.id === line ? (
                <p className="chat-new">
                  <span>New messages</span>
                </p>
              ) : null}
              <Message row={row} view={view} doing={doing} go={go} />
            </div>
          ))}
          {view.typing ? <Typing view={view} /> : null}
        </div>
        {undoing ? (
          <Foot>
            <span role="status">{undoing.said}</span>
            <button
              className="quiet"
              onClick={() => {
                void saying(undoing.undo).then(setSaid);
                setUndoing(null);
              }}
            >
              Undo
            </button>
          </Foot>
        ) : null}
        <Said said={said} />
        {chips.length > 0 ? (
          <div className="chat-chips" role="group" aria-label="His replies">
            {chips.map((chip) => (
              <button key={chip.words} className="chip" onClick={() => answered(chip)}>
                {chip.words}
              </button>
            ))}
          </div>
        ) : null}
        {view.canChange ? (
          <Box
            view={view}
            replying={replying}
            chosen={picked !== null}
            sent={() => {
              visit.current?.sent();
              setChosen(null);
              atFoot.current = true;
            }}
            letGo={() => {
              if (picked) setChosen(null);
              else setDeclined(view.offer?.id ?? null);
            }}
          />
        ) : (
          <p className="chat-read-only">To write to {view.name}, use the phone for now.</p>
        )}
      </section>
      <Changes view={view} />
    </main>
  );
}
