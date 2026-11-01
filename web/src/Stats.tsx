import { useCallback, useState } from "react";
import { api } from "./api";
import { AskFirst } from "./AskFirst";
import { useLive } from "./live";
import type { DayBar, HeatCell, HeatRow, StatsView } from "./types";

function Tile({ label, value, note, warn }: StatsView["tiles"][number]) {
  return (
    <div className="stat-tile" data-testid="stat-tile">
      <span className="label">{label}</span>
      <span className={warn ? "value warn" : "value"}>{value}</span>
      <span className="note">{note}</span>
    </div>
  );
}

function Day({ day }: { day: DayBar }) {
  return (
    <li className={day.quiet ? "stat-day faded" : "stat-day"}>
      <span className={day.today ? "date today" : "date"}>{day.label}</span>
      <span className="stat-bar" aria-hidden="true">
        <i style={{ width: `${day.share * 100}%` }} />
      </span>
      <span className="line">{day.line}</span>
    </li>
  );
}

function cellClass(cell: HeatCell): string | undefined {
  if (cell.missedMore) return "missed";
  return cell.kept > 0 ? "kept" : undefined;
}

function Cell({ row, hour, cell }: { row: HeatRow; hour: number; cell: HeatCell }) {
  const says = `${row.day} ${String(hour).padStart(2, "0")}:00: ${cell.kept} kept, ${cell.missed} missed`;
  const shade = cell.kept > 0 && !cell.missedMore ? { background: `color-mix(in srgb, var(--accent) ${Math.round(25 + 75 * cell.share)}%, transparent)` } : undefined;
  return (
    <td className={cellClass(cell)} style={shade} title={says}>
      <span className="visually-hidden">{says}</span>
    </td>
  );
}

/** When slots are kept (PL-09): a row a day, a cell an hour, darker for more kept, and hatched as well as red where more were missed, so it isn't told by colour alone. */
function Heat({ view }: { view: StatsView }) {
  return (
    <section className="stat-section" aria-labelledby="stat-heat">
      <h2 id="stat-heat">When you work</h2>
      <table className="stat-heat">
        <thead>
          <tr>
            <td />
            {view.heatHours.map((hour) => (
              <th key={hour} scope="col">
                {hour % 3 === 1 ? hour : <span className="visually-hidden">{hour}</span>}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {view.heat.map((row) => (
            <tr key={row.day}>
              <th scope="row">{row.day}</th>
              {row.cells.map((cell, index) => {
                const hour = view.heatHours[index] ?? index;
                return <Cell key={hour} row={row} hour={hour} cell={cell} />;
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="stat-key">
        <span>{view.heatNote}</span>
        <span>
          <i className="kept" aria-hidden="true" /> darker for more kept
        </span>
        <span>
          <i className="missed" aria-hidden="true" /> more missed
        </span>
      </p>
      {view.hours ? <AskFirst button={view.hours} /> : null}
    </section>
  );
}

export function StatsPage() {
  const [period, setPeriod] = useState<string | null>(null);
  const [allDays, setAllDays] = useState(false);
  const load = useCallback(() => api.stats(period), [period]);
  const { value: view, failed } = useLive(load);
  if (!view) return failed ? <p className="problem">Stats couldn't be read. It tries again with the next change.</p> : null;
  const days = allDays ? view.days : view.days.slice(0, view.foldAfter);
  return (
    <main className="stats-page">
      <header>
        <h1>Stats</h1>
        <div className="ways" role="group" aria-label="Period">
          {view.periods.map((choice) => (
            <button key={choice.key} aria-pressed={choice.chosen} onClick={() => setPeriod(choice.key)}>
              {choice.label}
            </button>
          ))}
        </div>
        <a className="quiet" href="/api/record.zip" download>
          Export the record as CSV
        </a>
      </header>
      <section className="stat-section" aria-labelledby="stat-tiles">
        <h2 id="stat-tiles">{view.heading}</h2>
        <div className="stat-tiles">
          {view.tiles.map((tile) => (
            <Tile key={tile.label} {...tile} />
          ))}
        </div>
      </section>
      <section className="stat-section" aria-labelledby="stat-days">
        <h2 id="stat-days">
          Day by day <span className="count">bars: focus time</span>
        </h2>
        <ul className="stat-days">
          {days.map((day) => (
            <Day key={day.label} day={day} />
          ))}
        </ul>
        {view.days.length > view.foldAfter ? (
          <button className="quiet" onClick={() => setAllDays(!allDays)}>
            {allDays ? "Show the last week only" : `Show all ${view.days.length} days`}
          </button>
        ) : null}
      </section>
      {view.subjects.length > 0 ? (
        <section className="stat-section" aria-labelledby="stat-subjects">
          <h2 id="stat-subjects">By subject</h2>
          <ul className="stat-lines">
            {view.subjects.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        </section>
      ) : null}
      <Heat view={view} />
      <section className="stat-section" aria-labelledby="stat-learned">
        <h2 id="stat-learned">What the app has learned</h2>
        {view.nothingLearned ? <p>{view.nothingLearned}</p> : null}
        <ul className="stat-lines">
          {view.learned.map((row) => (
            <li key={row.line}>
              <span>{row.line}</span>
              {row.forget ? <AskFirst button={row.forget} /> : null}
            </li>
          ))}
        </ul>
      </section>
      <details className="stat-section">
        <summary>In full</summary>
        <ul className="stat-lines">
          {view.inFull.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      </details>
    </main>
  );
}
