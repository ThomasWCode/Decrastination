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

  it("with a subject given, still replies to a message chosen", () => {
    const chosen: ChatQuote = { id: "mine", who: "You", line: "earlier" };
    expect(messageOf("hi", "1", chosen, "felix-b", about)).toMatchObject({ replyTo: "mine", about });
  });
});
