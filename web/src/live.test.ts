import { describe, expect, it } from "vitest";
import { ago, newestOnly } from "./live";

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

describe("newestOnly", () => {
  function waiting() {
    const answers: ((value: string) => void)[] = [];
    const load = () => new Promise<string>((resolve) => answers.push(resolve));
    return { answers, load };
  }

  it("drops a slow answer that a newer one has overtaken", async () => {
    const { answers, load } = waiting();
    const shown: string[] = [];
    const loads = newestOnly(load, (value) => shown.push(value), () => undefined);
    loads.run();
    loads.run();
    answers[1]?.("the day chosen");
    answers[0]?.("the day before");
    await Promise.resolve();
    await Promise.resolve();
    expect(shown).toEqual(["the day chosen"]);
  });

  it("hears nothing once cancelled, as a page that's moved on", async () => {
    const { answers, load } = waiting();
    const shown: string[] = [];
    const loads = newestOnly(load, (value) => shown.push(value), () => undefined);
    loads.run();
    loads.cancel();
    answers[0]?.("too late");
    await Promise.resolve();
    await Promise.resolve();
    expect(shown).toEqual([]);
  });

  it("says a failure only of the newest", async () => {
    let fails = 0;
    const rejections: ((why: Error) => void)[] = [];
    const load = () => new Promise<string>((_, reject) => rejections.push(reject));
    const loads = newestOnly(load, () => undefined, () => fails++);
    loads.run();
    loads.run();
    rejections[0]?.(new Error("old"));
    await Promise.resolve();
    await Promise.resolve();
    expect(fails).toBe(0);
    rejections[1]?.(new Error("new"));
    await Promise.resolve();
    await Promise.resolve();
    expect(fails).toBe(1);
  });
});
