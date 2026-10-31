import { describe, expect, it } from "vitest";
import { ago } from "./live";

describe("ago", () => {
  const now = 1_000_000_000;

  it("says just now within the minute", () => {
    expect(ago(now - 30_000, now)).toBe("just now");
  });

  it("counts minutes, then hours, then days", () => {
    expect(ago(now - 5 * 60_000, now)).toBe("5 min ago");
    expect(ago(now - 3 * 3_600_000, now)).toBe("3 h ago");
    expect(ago(now - 50 * 3_600_000, now)).toBe("2 days ago");
  });
});
