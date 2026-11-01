import { describe, expect, it } from "vitest";
import { cssColour, onColour, placement } from "./Day";
import type { BlockView } from "./types";

describe("a block's place", () => {
  const block = (startMin: number, endMin: number) => ({ startMin, endMin, column: 0, columns: 1, span: 1 }) as BlockView;

  it("goes by the hour's height", () => {
    expect(placement(block(60, 120), 60, 24)).toMatchObject({ top: 60, height: 58 });
    expect(placement(block(60, 120), 120, 24)).toMatchObject({ top: 120, height: 118 });
  });

  it("keeps two the server laid in one column from covering each other, at any zoom", () => {
    const first = placement(block(600, 610), 30, 24);
    const second = placement(block(624, 660), 30, 24);
    expect(Number(first.top) + Number(first.height)).toBeLessThanOrEqual(Number(second.top));
  });

  it("is as tall as the server's shortest at any zoom", () => {
    expect(placement(block(600, 605), 120, 24).height).toBe(46);
    expect(placement(block(600, 605), 30, 24).height).toBe(10);
  });
});

describe("an event's colours", () => {
  it("reads a calendar's ARGB, a signed number, as CSS", () => {
    expect(cssColour(0xff1e3a64 | 0)).toBe("#1e3a64");
    expect(cssColour(0xff000000 | 0)).toBe("#000000");
  });

  it("puts white words on dark and black on light, as the phone does", () => {
    expect(onColour(0xff1e3a64 | 0)).toBe("#ffffff");
    expect(onColour(0xffd7e3ff | 0)).toBe("#000000");
  });
});
