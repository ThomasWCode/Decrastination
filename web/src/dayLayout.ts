// The grid's maths in the browser, as `DayLayout.kt` has it, with its cases in fixtures/web/day-layout.json: a drop's own rule is the
// server's, and this only shows where a block being dragged will land.

export const DAY_MINUTES = 24 * 60;

/** How tall an hour's drawn: a pixel to the minute, as the page opens, and how far the wheel takes it. */
export const HOUR_PX = 60;
export const HOUR_MIN = 30;
export const HOUR_MAX = 240;

/** A move of the wheel, in steps of this. */
const ZOOM_STEP = 1.1;

export function yOf(minute: number, hourPx: number): number {
  return (minute * hourPx) / 60;
}

export function minuteAt(y: number, hourPx: number): number {
  return Math.trunc((y * 60) / hourPx);
}

/** To the nearest five minutes. */
export function snap(minute: number): number {
  return Math.trunc((minute + 2) / 5) * 5;
}

/** Where a block [minutes] long lands with its top at [minute]: on a five, and in the day. */
export function dropStart(minute: number, minutes: number): number {
  return Math.min(Math.max(snap(minute), 0), Math.max(0, DAY_MINUTES - minutes));
}

/** The hour's height after a turn of the wheel, up to zoom in. */
export function zoomed(hourPx: number, wheelUp: boolean): number {
  const next = wheelUp ? hourPx * ZOOM_STEP : hourPx / ZOOM_STEP;
  return Math.min(Math.max(Math.round(next), HOUR_MIN), HOUR_MAX);
}
