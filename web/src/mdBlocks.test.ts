import { describe, expect, it } from "vitest";
import cases from "../../fixtures/web/markdown.json";
import { parse, runs, type MdBlock } from "./mdBlocks";

// The cases are those `MdBlocksCasesTest` holds the phone's reading to: the two can't drift apart.

describe("Markdown's blocks, as the phone reads them", () => {
  it.each(cases.parse)("$name", ({ text, blocks }) => {
    expect(parse(text)).toEqual(blocks as MdBlock[]);
  });

  it.each(cases.runs)("runs of $text", ({ text, runs: expected }) => {
    expect(runs(text)).toEqual(expected);
  });
});
