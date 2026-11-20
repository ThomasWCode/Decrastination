import { describe, expect, it } from "vitest";
import { targetKey } from "./PhotoCheck";
import type { PhotoCheckTarget } from "./types";

const first: PhotoCheckTarget = { taskId: "teams:kin", key: "teams:kin#-1#1", step: null, record: null, side: 1568, pages: 10 };

describe("a photo check's key", () => {
  it("is the same for the same piece, whatever else is said of it", () => {
    expect(targetKey({ ...first, side: 1000 })).toBe(targetKey(first));
  });

  it("differs for its task's next piece, another step, or a kept slot's record", () => {
    expect(targetKey({ ...first, key: "teams:kin#-1#2" })).not.toBe(targetKey(first));
    expect(targetKey({ ...first, step: "Plan it" })).not.toBe(targetKey(first));
    expect(targetKey({ ...first, record: { key: first.key, start: 1, at: 2 } })).not.toBe(targetKey(first));
  });
});
