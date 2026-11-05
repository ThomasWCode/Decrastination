import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { Markdown } from "./Markdown";

const SUMMARY = `They kept less than planned.

| Measure | Result |
|---|---|
| Work kept | 45 min (1 piece) |
| Pieces missed | 6 |

- **two** pieces moved
- <b>not</b> read as HTML`;

describe("Markdown drawn", () => {
  afterEach(cleanup);

  it("draws a review's table as a table, its header as column headers", () => {
    render(<Markdown text={SUMMARY} />);
    const table = screen.getByRole("table");
    expect(within(table).getAllByRole("columnheader").map((cell) => cell.textContent)).toEqual(["Measure", "Result"]);
    expect(within(table).getAllByRole("row")).toHaveLength(3);
    expect(within(table).getByRole("cell", { name: "45 min (1 piece)" })).toBeTruthy();
    expect(screen.queryByText(/\|/)).toBeNull();
  });

  it("draws a list, bold as bold, and HTML in the words as words", () => {
    const { container } = render(<Markdown text={SUMMARY} />);
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
    expect(container.querySelector("strong")?.textContent).toBe("two");
    expect(container.querySelector("b")).toBeNull();
    expect(screen.getByText("<b>not</b> read as HTML")).toBeTruthy();
  });
});
