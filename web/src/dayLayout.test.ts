import { describe, expect, it } from "vitest";
import cases from "../../fixtures/web/day-layout.json";
import { dropStart, HOUR_MAX, HOUR_MIN, HOUR_PX, minuteAt, snap, yOf, zoomed } from "./dayLayout";

// The cases are those `DayLayoutCasesTest` holds the phone's maths to: the two can't drift apart.

describe("the grid's maths, as the phone's", () => {
  it.each(cases.minuteAt)("minuteAt($y, $hourPx) is $minute", ({ y, hourPx, minute }) => {
    expect(minuteAt(y, hourPx)).toBe(minute);
  });

  it.each(cases.yOf)("yOf($minute, $hourPx) is $y", ({ minute, hourPx, y }) => {
    expect(yOf(minute, hourPx)).toBe(y);
  });

  it.each(cases.snap)("snap($minute) is $snapped", ({ minute, snapped }) => {
    expect(snap(minute)).toBe(snapped);
  });

  it.each(cases.dropStart)("dropStart($minute, $minutes) is $start", ({ minute, minutes, start }) => {
    expect(dropStart(minute, minutes)).toBe(start);
  });
});

describe("zoomed", () => {
  it("goes up and down by a step, in whole pixels", () => {
    expect(zoomed(HOUR_PX, true)).toBe(66);
    expect(zoomed(HOUR_PX, false)).toBe(55);
  });

  it("stops at its ends", () => {
    expect(zoomed(HOUR_MAX, true)).toBe(HOUR_MAX);
    expect(zoomed(HOUR_MIN, false)).toBe(HOUR_MIN);
  });
});
