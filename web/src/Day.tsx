import { useCallback, useEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent } from "react";
import { Link, type Go } from "./App";
import { api, asking, dropping, saying } from "./api";
import { DAY_MINUTES, dropStart, HOUR_PX, minuteAt, yOf, zoomed } from "./dayLayout";
import { ago, useLive } from "./live";
import { TaskSheet } from "./TaskSheet";
import type { Action, BlockView, DayView, TaskAction } from "./types";

// The grid's blocks are laid out by the server, side by side where they overlap; how tall an hour is, the wheel's.
const HOURS = Array.from({ length: 25 }, (_, hour) => hour);

/** How far a block's dragged before it's lifted, not tapped. */
const LIFT_PX = 5;

const ZOOM = "day-hour";

/** An event's colour from the calendar, as CSS: ARGB in a signed 32-bit number. */
export function cssColour(argb: number): string {
  return `#${(argb & 0xffffff).toString(16).padStart(6, "0")}`;
}

/** Black or white words on [argb], whichever reads better, as the phone chooses (`Colours.onEvent`). */
export function onColour(argb: number): string {
  const channel = (shift: number) => {
    const value = ((argb >> shift) & 0xff) / 255;
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  };
  const luminance = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0);
  return luminance > 0.179 ? "#000000" : "#ffffff";
}

function placement(block: BlockView, hourPx: number): CSSProperties {
  const share = 100 / block.columns;
  return {
    top: yOf(block.startMin, hourPx),
    height: Math.max(yOf(block.endMin - block.startMin, hourPx), 24) - 2,
    left: `${block.column * share}%`,
    width: `calc(${block.span * share}% - 4px)`,
  };
}

/** A block lifted: where in it it was held, and where it is now, the day under it on the strip or its top's minute on the grid. */
interface Lifted {
  block: BlockView;
  grab: number;
  onto: string | null;
  minute: number | null;
}

function Block({ block, hourPx, lifted, open, lift }: { block: BlockView; hourPx: number; lifted: boolean; open: (id: string) => void; lift: (block: BlockView, event: PointerEvent<HTMLDivElement>) => void }) {
  const colours: CSSProperties =
    block.kind === "event" && block.colour != null
      ? block.free
        ? { borderColor: cssColour(block.colour) }
        : { background: cssColour(block.colour), color: onColour(block.colour) }
      : {};
  const short = block.endMin - block.startMin < 36 ? "short" : null;
  const kinds = ["block", block.kind, block.state, block.outcome, block.urgent ? "urgent" : null, block.free ? "free" : null, short, block.drag ? "movable" : null, lifted ? "lifted" : null].filter(Boolean).join(" ");
  const taskId = block.taskId;
  const key = (event: KeyboardEvent<HTMLDivElement>) => {
    if (taskId && (event.key === "Enter" || event.key === " ")) {
      event.preventDefault();
      open(taskId);
    }
  };
  return (
    <div
      className={kinds}
      style={{ ...placement(block, hourPx), ...colours }}
      data-testid={block.kind === "piece" ? "piece" : undefined}
      role={taskId ? "button" : undefined}
      tabIndex={taskId ? 0 : undefined}
      onKeyDown={key}
      onPointerDown={block.drag || taskId ? (event) => lift(block, event) : undefined}
    >
      <span className="title">{block.title}</span>
      <span className="times">{block.times}</span>
      {block.note ? <span className="note">{block.note}</span> : null}
    </div>
  );
}

function clock(minute: number): string {
  return `${String(Math.floor(minute / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
}

/** Whether the wheel's a zoom: over the grid with Ctrl or Cmd held, as a browser's page zoom is. Alone, it scrolls. */
function Grid({ day, hourPx, zoom, open, dropped }: { day: DayView; hourPx: number; zoom: (wheelUp: boolean) => void; open: (id: string) => void; dropped: (block: BlockView, onto: string | null, minute: number | null) => void }) {
  const scroller = useRef<HTMLDivElement>(null);
  const placed = useRef<string | null>(null);
  const [lifted, setLifted] = useState<Lifted | null>(null);
  // Opened at the day's morning, or where it is now; once for a day, as the page is asked for again each minute and you may be reading elsewhere.
  useEffect(() => {
    if (placed.current === day.date) return;
    placed.current = day.date;
    scroller.current?.scrollTo({ top: Math.max(yOf(day.nowMin ?? day.opensAt, hourPx) - 90, 0) });
  }, [day.date, day.nowMin, day.opensAt, hourPx]);

  // The browser's own Ctrl and wheel zooms the page: only a handler that isn't passive can keep it from that.
  useEffect(() => {
    const box = scroller.current;
    if (!box) return;
    const wheel = (event: globalThis.WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      zoom(event.deltaY < 0);
    };
    box.addEventListener("wheel", wheel, { passive: false });
    return () => box.removeEventListener("wheel", wheel);
  }, [zoom]);

  const lift = (block: BlockView, down: PointerEvent<HTMLDivElement>) => {
    if (down.button !== 0) return;
    const element = down.currentTarget;
    const box = scroller.current;
    if (!box) return;
    const startX = down.clientX;
    const startY = down.clientY;
    const grab = startY - element.getBoundingClientRect().top;
    let moved = false;
    const where = (event: globalThis.PointerEvent): Lifted => {
      const bounds = box.getBoundingClientRect();
      const under = document.elementFromPoint(event.clientX, event.clientY)?.closest("[data-date]");
      const onto = under?.getAttribute("data-date") ?? null;
      const onGrid = onto === null && event.clientY >= bounds.top && event.clientY <= bounds.bottom;
      return { block, grab, onto, minute: onGrid ? minuteAt(event.clientY - grab - bounds.top + box.scrollTop, hourPx) : null };
    };
    const move = (event: globalThis.PointerEvent) => {
      if (!moved && Math.hypot(event.clientX - startX, event.clientY - startY) < LIFT_PX) return;
      if (!block.drag) return;
      moved = true;
      event.preventDefault();
      setLifted(where(event));
    };
    const stop = () => {
      removeEventListener("pointermove", move);
      removeEventListener("pointerup", up);
      removeEventListener("pointercancel", stop);
      removeEventListener("keydown", cancel);
      setLifted(null);
    };
    const up = (event: globalThis.PointerEvent) => {
      const landed = moved ? where(event) : null;
      stop();
      if (landed) dropped(block, landed.onto, landed.minute);
      else if (block.taskId) open(block.taskId);
    };
    const cancel = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") stop();
    };
    addEventListener("pointermove", move);
    addEventListener("pointerup", up);
    addEventListener("pointercancel", stop);
    addEventListener("keydown", cancel);
  };

  const landing = lifted?.block.drag && lifted.minute !== null ? dropStart(lifted.minute, lifted.block.drag.minutes) : null;
  return (
    <div className="scroller" ref={scroller} data-testid="grid">
      <div className="grid" style={{ height: yOf(DAY_MINUTES, hourPx) }}>
        <div className="hours">
          {HOURS.slice(0, 24).map((hour) => (
            <span key={hour} style={{ top: yOf(hour * 60, hourPx) }}>
              {String(hour).padStart(2, "0")}:00
            </span>
          ))}
        </div>
        <div className="day">
          {HOURS.map((hour) => (
            <i key={hour} className="hour-line" style={{ top: yOf(hour * 60, hourPx) }} />
          ))}
          {day.windows.map((window) => (
            <div key={`${window.startMin}-${window.name}`} className="window" style={{ top: yOf(window.startMin, hourPx), height: yOf(window.endMin - window.startMin, hourPx) }}>
              {window.name}
            </div>
          ))}
          {day.blocks.map((block) => (
            <Block key={block.key} block={block} hourPx={hourPx} lifted={lifted?.block.key === block.key} open={open} lift={lift} />
          ))}
          {landing !== null && lifted?.block.drag ? (
            <div className="landing" data-testid="landing" style={{ top: yOf(landing, hourPx), height: yOf(lifted.block.drag.minutes, hourPx) }}>
              {clock(landing)}
            </div>
          ) : null}
          {day.nowMin != null ? <i className="now" style={{ top: yOf(day.nowMin, hourPx) }} /> : null}
        </div>
      </div>
    </div>
  );
}

function Pieces({ title, pieces }: { title: string; pieces: DayView["unslotted"] }) {
  if (pieces.length === 0) return null;
  return (
    <section>
      <h3>{title}</h3>
      <ul>
        {pieces.map((piece) => (
          <li key={piece.label} className={piece.urgent ? "urgent" : undefined}>
            {piece.label} <span className="minutes">{piece.minutes}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** The timer running, wherever it was started: its piece, its time and its Stop (D-112). */
function Timer({ day }: { day: DayView }) {
  const timer = day.timer;
  const [said, setSaid] = useState<string | null>(null);
  if (!timer) return null;
  return (
    <section className="timer" aria-label="Timer">
      <p className="piece">{timer.label}</p>
      <p className="says">{timer.says}</p>
      {timer.stop ? (
        <button className="action" onClick={() => timer.stop && void saying(timer.stop).then(setSaid)}>
          Stop
        </button>
      ) : null}
      {said ? <p className="said">{said}</p> : null}
    </section>
  );
}

/** What's to be done with what's next, as the server says: Done says what it came to, with its Undo. */
function NextButtons({ taskId, actions }: { taskId: string; actions: TaskAction[] }) {
  const [busy, setBusy] = useState(false);
  const [said, setSaid] = useState<{ text: string | null; undo: Action | null } | null>(null);
  const going = (work: Promise<{ text: string | null; undo: Action | null }>) => {
    setBusy(true);
    setSaid(null);
    void work.then((came) => {
      setBusy(false);
      setSaid(came.text || came.undo ? came : null);
    });
  };
  const press = (button: TaskAction) => {
    const { ask, action } = button;
    if (ask) going(asking(taskId, ask).then(({ done, said: words }) => ({ text: words ?? null, undo: done ? (button.undo ?? null) : null })));
    else if (action) going(saying(action).then((why) => ({ text: why, undo: null })));
  };
  return (
    <>
      <div className="next-actions" role="group" aria-label="Do">
        {actions.map((button) => (
          <button key={button.label} className="action" disabled={busy} onClick={() => press(button)}>
            {button.label}
          </button>
        ))}
      </div>
      {said ? (
        <p className="said" role="status">
          {said.text}{" "}
          {said.undo ? (
            <button className="quiet" onClick={() => said.undo && going(saying(said.undo).then((why) => ({ text: why, undo: null })))}>
              Undo
            </button>
          ) : null}
        </p>
      ) : null}
    </>
  );
}

/** What to do now: the page's one loud thing, as it is on the phone. */
function Now({ day }: { day: DayView }) {
  return (
    <aside className="now-panel">
      <Timer day={day} />
      {day.today ? (
        <>
          <h2>Do now</h2>
          {day.next ? (
            <>
              <p className="piece">{day.next.label}</p>
              {day.next.first ? <p>First: {day.next.first}</p> : null}
              {day.next.then ? <p className="then">Then: {day.next.then}</p> : null}
              {day.next.taskId ? <NextButtons key={day.next.label} taskId={day.next.taskId} actions={day.next.actions} /> : null}
            </>
          ) : (
            <p className="piece">Nothing to do</p>
          )}
        </>
      ) : (
        <h2>{day.title}</h2>
      )}
      <p className="load">{day.load}</p>
      {day.doneLine ? <p>{day.doneLine}</p> : null}
      {day.wontFit ? <p className="warning">{day.wontFit}</p> : null}
      <Pieces title="No time for these yet" pieces={day.unslotted} />
      <Pieces title="Done" pieces={day.done} />
      {day.asOf ? <p className="fresh">From the phone {ago(day.asOf)}</p> : <p className="fresh">The phone hasn't sent anything yet: pair it on the Phone page.</p>}
    </aside>
  );
}

export function DayPage({ date, go }: { date: string | null; go: Go }) {
  // Asked again as a timer's words turn: they change no store, so nothing else says so.
  const [turned, setTurned] = useState(0);
  const load = useCallback(() => api.day(date), [date, turned]);
  const { value: day, failed } = useLive(load);
  const [hourPx, setHourPx] = useState(() => Number(localStorage.getItem(ZOOM)) || HOUR_PX);
  const [opened, setOpened] = useState<string | null>(null);
  const [said, setSaid] = useState<string | null>(null);
  const shut = useCallback(() => setOpened(null), []);
  const zoom = useCallback((wheelUp: boolean) => {
    setHourPx((was) => {
      const next = zoomed(was, wheelUp);
      localStorage.setItem(ZOOM, String(next));
      return next;
    });
  }, []);
  const nextAt = day?.timer?.nextAt;
  useEffect(() => {
    if (nextAt === undefined) return;
    const timer = setTimeout(() => setTurned((times) => times + 1), Math.max(nextAt - Date.now(), 1_000));
    return () => clearTimeout(timer);
  }, [nextAt]);
  if (!day) return failed ? <p className="problem">The Day couldn't be read. It tries again with the next change.</p> : null;
  const dropped = (block: BlockView, onto: string | null, minute: number | null) => {
    if (!block.drag) return;
    void dropping({ key: block.drag.key, date: day.date, onto, minute }).then((came) => setSaid(came.said ?? null));
  };
  return (
    <main className="day-page">
      <Now day={day} />
      <section className="agenda" aria-label={day.title}>
        <nav className="week" aria-label="Week">
          {day.week.map((d) => (
            <Link key={d.date} to={`/day?date=${d.date}`} go={go} current={d.chosen} date={d.date}>
              <span className={d.today ? "weekday today" : "weekday"}>
                {d.weekday} {d.day}
              </span>
              <span className="load">{d.load ?? ""}</span>
            </Link>
          ))}
        </nav>
        {said ? (
          <p className="day-said" role="status">
            {said}
          </p>
        ) : null}
        {day.allDay.length > 0 ? (
          <ul className="all-day">
            {day.allDay.map((event) => (
              <li key={event.title} style={event.colour != null ? { background: cssColour(event.colour), color: onColour(event.colour) } : undefined}>
                {event.title}
              </li>
            ))}
          </ul>
        ) : null}
        <Grid day={day} hourPx={hourPx} zoom={zoom} open={setOpened} dropped={dropped} />
      </section>
      {opened ? <TaskSheet key={opened} id={opened} form={day.form ?? null} close={shut} /> : null}
    </main>
  );
}
