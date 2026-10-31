import { useCallback, useEffect, useRef, type CSSProperties } from "react";
import { Link, type Go } from "./App";
import { api } from "./api";
import { ago, useLive } from "./live";
import type { BlockView, DayView } from "./types";

// The grid has a pixel to the minute, which the server's lay-out of blocks side by side goes by.
const DAY_MINUTES = 24 * 60;
const HOURS = Array.from({ length: 25 }, (_, hour) => hour);

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

function placement(block: BlockView): CSSProperties {
  const share = 100 / block.columns;
  return {
    top: block.startMin,
    height: Math.max(block.endMin - block.startMin, 24) - 2,
    left: `${block.column * share}%`,
    width: `calc(${block.span * share}% - 4px)`,
  };
}

function Block({ block }: { block: BlockView }) {
  const colours: CSSProperties =
    block.kind === "event" && block.colour != null
      ? block.free
        ? { borderColor: cssColour(block.colour) }
        : { background: cssColour(block.colour), color: onColour(block.colour) }
      : {};
  const short = block.endMin - block.startMin < 36 ? "short" : null;
  const kinds = ["block", block.kind, block.state, block.outcome, block.urgent ? "urgent" : null, block.free ? "free" : null, short].filter(Boolean).join(" ");
  return (
    <div className={kinds} style={{ ...placement(block), ...colours }}>
      <span className="title">{block.title}</span>
      <span className="times">{block.times}</span>
      {block.note ? <span className="note">{block.note}</span> : null}
    </div>
  );
}

function Grid({ day }: { day: DayView }) {
  const scroller = useRef<HTMLDivElement>(null);
  const placed = useRef<string | null>(null);
  // Opened at the day's morning, or where it is now; once for a day, as the page is asked for again each minute and you may be reading elsewhere.
  useEffect(() => {
    if (placed.current === day.date) return;
    placed.current = day.date;
    scroller.current?.scrollTo({ top: Math.max((day.nowMin ?? day.opensAt) - 90, 0) });
  }, [day.date, day.nowMin, day.opensAt]);
  return (
    <div className="scroller" ref={scroller} data-testid="grid">
      <div className="grid" style={{ height: DAY_MINUTES }}>
        <div className="hours">
          {HOURS.slice(0, 24).map((hour) => (
            <span key={hour} style={{ top: hour * 60 }}>
              {String(hour).padStart(2, "0")}:00
            </span>
          ))}
        </div>
        <div className="day">
          {HOURS.map((hour) => (
            <i key={hour} className="hour-line" style={{ top: hour * 60 }} />
          ))}
          {day.windows.map((window) => (
            <div key={`${window.startMin}-${window.name}`} className="window" style={{ top: window.startMin, height: window.endMin - window.startMin }}>
              {window.name}
            </div>
          ))}
          {day.blocks.map((block) => (
            <Block key={block.key} block={block} />
          ))}
          {day.nowMin != null ? <i className="now" style={{ top: day.nowMin }} /> : null}
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

/** What to do now: the page's one loud thing, as it is on the phone. */
function Now({ day }: { day: DayView }) {
  return (
    <aside className="now-panel">
      {day.today ? (
        <>
          <h2>Do now</h2>
          {day.next ? (
            <>
              <p className="piece">{day.next.label}</p>
              {day.next.first ? <p>First: {day.next.first}</p> : null}
              {day.next.then ? <p className="then">Then: {day.next.then}</p> : null}
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
  const load = useCallback(() => api.day(date), [date]);
  const { value: day, failed } = useLive(load);
  if (!day) return failed ? <p className="problem">The Day couldn't be read. It tries again with the next change.</p> : null;
  return (
    <main className="day-page">
      <Now day={day} />
      <section className="agenda" aria-label={day.title}>
        <nav className="week" aria-label="Week">
          {day.week.map((d) => (
            <Link key={d.date} to={`/?date=${d.date}`} go={go} current={d.chosen}>
              <span className={d.today ? "weekday today" : "weekday"}>
                {d.weekday} {d.day}
              </span>
              <span className="load">{d.load ?? ""}</span>
            </Link>
          ))}
        </nav>
        {day.allDay.length > 0 ? (
          <ul className="all-day">
            {day.allDay.map((event) => (
              <li key={event.title} style={event.colour != null ? { background: cssColour(event.colour), color: onColour(event.colour) } : undefined}>
                {event.title}
              </li>
            ))}
          </ul>
        ) : null}
        <Grid day={day} />
      </section>
    </main>
  );
}
