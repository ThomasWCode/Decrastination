// @vitest-environment node
import { describe, expect, it } from "vitest";
import { photoHash } from "./photos";

describe("a photo's name", () => {
  it("is the first eight bytes of its SHA-256, as the server and the phone name it", async () => {
    expect(await photoHash(new Blob(["abc"]))).toBe("ba7816bf8f01cfea");
  });
});
