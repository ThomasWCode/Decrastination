// A stand-in for Anthropic's Messages API, for the browser tests: nothing here reaches Anthropic, so no test costs anything. An
// answer's made from the words of the message it answers, each test's own: the rest gets a plain reply. Nothing is kept or logged.
import { createServer } from "node:http";

const answer = (reply, more = {}) =>
  JSON.stringify({ reply, newTasks: [], changes: [], memories: [], suggestedReplies: [], actions: [], research: "", details: [], answered: [], ...more });

const WORKSHEET = { title: "A worksheet to add", kind: "Homework", subject: "", dueDate: "", dueTime: "", startDate: "", minutes: 30, notes: "", steps: [] };

const CARD_ASKED = "please add the worksheet";
const SLOW_ASKED = "please take a while";
const ONCE_ASKED = "please be unreadable";
const NEVER_ASKED = "please always fail";

/** How long a slow answer takes: past the ten seconds that offer Cancel. */
const SLOW_MS = 12_500;

/** How often each message has been answered, so one can fail the first time. */
const asked = new Map();

function text(said) {
  if (said.includes(NEVER_ASKED)) return "That isn't an answer.";
  if (said.includes(ONCE_ASKED)) {
    const times = asked.get(ONCE_ASKED) ?? 0;
    asked.set(ONCE_ASKED, times + 1);
    return times === 0 ? "That isn't an answer." : answer("Read you this time.");
  }
  if (said.includes(CARD_ASKED)) return answer("Added it.", { newTasks: [WORKSHEET] });
  return answer("I hear you.");
}

function message(words) {
  return JSON.stringify({
    id: "msg_stand_in",
    type: "message",
    role: "assistant",
    model: "claude-opus-5-5",
    content: [{ type: "text", text: words }],
    stop_reason: "end_turn",
    stop_sequence: null,
    usage: { input_tokens: 1, output_tokens: 1 },
  });
}

const server = createServer((request, response) => {
  if (request.method !== "POST") {
    response.writeHead(200).end("A stand-in for the Messages API");
    return;
  }
  let body = "";
  request.on("data", (chunk) => (body += chunk));
  request.on("end", () => {
    const said = JSON.stringify(JSON.parse(body).messages?.at(-1) ?? "");
    const reply = () => response.writeHead(200, { "Content-Type": "application/json" }).end(message(text(said)));
    if (said.includes(SLOW_ASKED)) setTimeout(reply, SLOW_MS);
    else reply();
  });
});

server.listen(Number(process.argv[2] ?? 8092), "127.0.0.1");
