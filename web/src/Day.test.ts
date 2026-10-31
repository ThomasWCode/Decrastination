import { describe, expect, it } from "vitest";
import { cssColour, onColour } from "./Day";

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
