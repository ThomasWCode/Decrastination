import { describe, expect, it } from "vitest";
import { messageOf } from "./Chat";
import type { About, ChatQuote } from "./types";

const about: About = { taskId: "own:a", taskTitle: "A" };
const offered: ChatQuote = { id: "felix-b", who: "Felix", line: "How did B go?" };

describe("the box's message", () => {
  it("answers his nudge by itself with no subject given", () => {
    expect(messageOf("hi", "1", null, "felix-b", null)).toEqual({ type: "SendMessage", text: "hi", id: "1", replyTo: "felix-b", takeItsSubject: false });
    expect(messageOf("hi", "1", offered, "felix-b", null)).toMatchObject({ replyTo: "felix-b", takeItsSubject: true });
  });

  it("with a subject given, replies to nothing it didn't choose", () => {
    expect(messageOf("hi", "1", null, "felix-b", about)).toEqual({ type: "SendMessage", text: "hi", id: "1", replyTo: null, takeItsSubject: false, about });
  });

  it("carries its photos, each by its name and whether it's kept, and none where there are none", () => {
    expect(messageOf("", "1", null, null, null, [{ hash: "1a2b3c4d5e6f7a8b", kept: true }])).toEqual({ type: "SendMessage", text: "", id: "1", replyTo: null, takeItsSubject: false, photos: [{ hash: "1a2b3c4d5e6f7a8b", kept: true }] });
    expect(messageOf("hi", "1", null, null, null)).not.toHaveProperty("photos");
  });

  it("with a subject given, still replies to a message chosen", () => {
    const chosen: ChatQuote = { id: "mine", who: "You", line: "earlier" };
    expect(messageOf("hi", "1", chosen, "felix-b", about)).toMatchObject({ replyTo: "mine", about });
  });
});
