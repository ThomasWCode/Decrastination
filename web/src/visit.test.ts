import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Visit } from "./visit";

describe("Visit", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("is seen once it's been in front long enough, unbroken", () => {
    const visit = new Visit(3_000);
    visit.shown();
    vi.advanceTimersByTime(3_000);
    expect(visit.left()).toBe(true);
  });

  it("isn't seen by passing through", () => {
    const visit = new Visit(3_000);
    visit.shown();
    vi.advanceTimersByTime(2_999);
    expect(visit.left()).toBe(false);
  });

  it("starts afresh after it's left, the time before not counting", () => {
    const visit = new Visit(3_000);
    visit.shown();
    vi.advanceTimersByTime(2_000);
    expect(visit.left()).toBe(false);
    visit.shown();
    vi.advanceTimersByTime(2_000);
    expect(visit.left()).toBe(false);
  });

  it("is seen at once by a message sent", () => {
    const visit = new Visit(3_000);
    visit.shown();
    visit.sent();
    expect(visit.left()).toBe(true);
  });

  it("forgets it was seen once it's left", () => {
    const visit = new Visit(3_000);
    visit.sent();
    expect(visit.left()).toBe(true);
    expect(visit.left()).toBe(false);
  });

  it("isn't made seen late by a timer left running after it's gone", () => {
    const visit = new Visit(3_000);
    visit.shown();
    visit.left();
    vi.advanceTimersByTime(3_000);
    expect(visit.left()).toBe(false);
  });

  it("keeps counting from its first showing when it's shown again", () => {
    const visit = new Visit(3_000);
    visit.shown();
    vi.advanceTimersByTime(2_000);
    visit.shown();
    vi.advanceTimersByTime(1_000);
    expect(visit.left()).toBe(true);
  });
});
