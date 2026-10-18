# His voice

You want him to sound like a teenager: "alr cool dude let's do xxx then. what's the date gonna be for that?" instead of "I'll put this in as a task for: xx. When would you like…?". Today both his prompts ask for "plain UK English" in "one to three short sentences" and "no emojis"; his default line is "a strict friend who knows your week and wants the work done". This is a prompt change with a few fixed strings behind it, no new code paths, and the goldens and the doc page regenerated. Built in Stage 10b ([03](03-stages.md)), after the chat work has put every fixed line in one place (`FelixWords`, Stage 5a) and the readings' prompts have been renamed (Stage 9).

## What sets his voice

| Where | What it says now | Changes |
|---|---|---|
| `Settings.felixLine` (the default; yours to change, D-57) | "a strict friend who knows your week and wants the work done" | a new default in the register: "your mate who's on your case about the work" (Q29: A); your phone keeps the value it has, so the new default reaches you only if you take it |
| `ChatPrompts.system` (the chat golden `chat.txt`) | "Reply in one to three short sentences of plain UK English…" | a register paragraph (below), with examples |
| `FelixPrompts.system` (`felix.txt`) | "Be concrete… Two sentences at most, under 240 characters… No guilt, no sarcasm, no emojis, no praise for nothing…" | the same register paragraph, the limits kept |
| The fixed lines in his name (`byApp`, [05](05-the-chat.md) §1) | "Thanks: the week's review comes next.", the check-in's five questions, the summary's offer, how work went, a calendar event's question, a reminder's verbatim frame, the morning's list-only title (`Briefing.summary`'s) | reworded in the register, in the one place Stage 5a put them (`FelixWords`, a pure object), so the app's words in his bubble don't jar against the model's |
| `suggestedReplies` | "in the student's words" | as now: they're yours |
| The instructions reader, the web search, the readings, the review, the tidy | not him | unchanged |
| The week's summary email (`SummaryPrompts.SYSTEM`, `summary.txt`) | the student's voice, first person | about you, in the third person (Q2, Q25: below) |

## The register

A paragraph for both prompts, pinned by examples (AI-07), to be written with you before the goldens change (Q16: A, with no emojis and mild swearing allowed). The shape:

- **Like a sixth-former texting a mate**: lower case is fine, contractions and the odd "alr", "gonna", "tbh", "ngl"; short; no corporate politeness ("I'll put this in as a task for:"); says what he's done in a word ("done, friday 7pm"); asks the way a friend asks ("what day's that then?").
- **Still exact where it matters**: dates, times and minutes written plainly ("fri 24 oct, 19:00", "40 min"), one thing per sentence, never a joke in place of the answer; a task's id never in his words.
- **Still him** (D-57): strict about the work, no guilt, no sarcasm at you, no praise for nothing, no promises the app can't keep.
- **Emojis**: none (Q16). **Swearing**: mild and sparing, where a mate would ("that's a bloody long essay"), never at you and never in the fixed lines the app writes; one of the examples shows the line so the model knows where it is (Q30: A: mild, sparing, never at you, never in the fixed lines). **Caps**: none.
- **The app's fixed lines** match it: "nice, the week's review is next", "ok so, 1 to 5, how was the week?".

Three or four made-up exchanges in each prompt pin it: a task added ("alr, added it for fri 19:00, 40 min. want me to split it?"), a question ("which one, the physics or the chem?"), a slot's start ("the worksheet started 10 min ago and nothing's happened. starting? q1 first, underline what it asks"), and a vent answered in kind with nothing else (below).

## What else the register touches

- **The history he's shown**: a `byApp` message is marked "(the app wrote this for you)" in `ChatPrompts.turns` (from Stage 5b), so fixed words aren't taken for his own style; the morning's fallback list likewise.
- **The length limits** stay (240 and 480 characters, three sentences): a casual voice runs long if let.
- **`FelixAnswers.message`** cuts at a sentence end: with lower case and few full stops, it may find none and cut at a word with an ellipsis. Fine, but the prompt asks for full stops between thoughts.
- **The check-in's replies** (1 to 5; the put-off work) and `Assessment.ANSWERS` ("Harder", "As expected", "Easier") stay as they are: they're matched by the app on the way back. The chips show in his register ("harder", "about right", "easier") while the app matches either form: a small table in `FelixWords` (Q16: A).
- **The doc page** (`felix-inputs.md`, `FelixInputsDocTest`) is regenerated: every example's words change.
- **The prompt items reminders and memory added** (Stages 8a and 10a, plain at first) take the register here, in the same rewrite.

## Just talking

Some messages want nothing but an answer: "god isn't my chemistry teacher annoying with the homework", "that went badly", "lol ok". Today the prompt says "do only what they asked for" and that most messages need no memory, action or search, but nothing says a message may need nothing at all, and a model told what it can do tends to do something (a fact remembered about the teacher, a task offered). The change (your note of 18 Oct):

- A sentence in `ChatPrompts.system`'s closing paragraph: "Some messages are just talk: answer as a mate would, in a sentence or two, and give nothing else (no change, nothing remembered, no action), unless they've told you something lasting about their week." The memory sentence of his own prompt says the same of replies.
- One of the pinned examples is a vent, answered in kind, every array empty.
- The private chat eval gains a handful of such messages, each labelled "nothing but a reply", so the rate of unasked changes is measured with the rest ($2).
- On screen nothing's needed: a message with nothing in its tail has no chevron and no label ([05](05-the-chat.md) §6, from Stage 5c); his chips can still offer a reply ("fair", "anyway, what's next?").
- Where the vent names a task ("this essay is killing me"), the message's `about` still helps him answer; he changes nothing unless asked.

## The summary email

The week's summary for your dad isn't his voice, and shouldn't sound like him or like "I" (Q2's note; Q25, decided live on 18 Oct: the third person). Today `SummaryPrompts.SYSTEM` asks for it "in the student's voice, first person". It becomes: "Write the week's summary of the student for their dad, in the third person (as "they", or by the name the profile gives, never "I"), plainly, no excuses and no spin: what got done, what didn't and why, what changed. Short paragraphs and one small table. Under 300 words." His offer in the chat ("the week's summary's ready, want me to send it?", kind `Summary`, `byApp`, with the button that opens the email, [05](05-the-chat.md) §1) is in his register; the email isn't. `summary.txt` is rewritten; the readings' prompt version stays (not a reading's prompt); `Summary.input` and `Summary.email` are unchanged. The writer lives in the Learning service by then (Stage 6e).

## Checking it

- **The goldens** change on purpose (`-PupdateGolden=true`); `ReadingPrompts.VERSION` stays (no reading's prompt or profile changes here).
- **The private evals** (`private/eval-chat`, `private/eval-felix`, never committed) are run again (`EvalRunTest -PrunEval=true`, about $2): the parse rate, the changes made against the labels, the dates read right, the vents answered with nothing else. The voice must not cost a change or a date; if it does, the register's "still exact" lines are tightened and run again.
- **A read of the examples by you** before the goldens are written: the register is a matter of taste, and the examples are what pin it.

## Risks

- **Structured answers drift**: a looser voice may skip a field or put the reply's words in the wrong one. The schema holds the shape; the evals catch the rest.
- **Reading dates loosely** ("fri" when you meant next Friday): the prompt's date rules stand; the examples show relative dates resolved and said back ("fri 24 oct").
- **Too much**: a voice that's all slang tires. The examples carry a light touch; the line is yours to tune without a release (`felixLine`).

## Built in

[03](03-stages.md) §Stage 10b: the words first, read by you; then the prompts, the fixed lines, the summary, the evals.

## Tests

- `GoldenPromptsTest` with the new copies; `FelixInputsDocTest` regenerated.
- `FelixWords`: each fixed line, the chips' two forms matched (`Assessment.ANSWERS` and the register's), `CheckInFlow.answered` taking "4" and "4/5".
- `summary.txt` regenerated; a parse of a third-person answer unchanged (`SummaryPrompts.parse` reads a string).
- Nothing else: the voice is words.

## Phone check

- `chat-ask` three times (paid): the register, the dates said back, a change made; a fourth that's a vent: a reply and nothing else; `felix-say --es reason SlotStart`: two sentences, the register, under 240 characters; the briefing the next morning.
- `review` then the summary: the email in the third person, his offer in the chat in his register.
- You read a day's messages and say what's off; the examples are adjusted in a follow-up PR (goldens only).
