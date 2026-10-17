# His voice

You want him to sound like a teenager: "alr cool dude let's do xxx then. what's the date gonna be for that?" instead of "I'll put this in as a task for: xx. When would you like…?". Today both his prompts ask for "plain UK English" in "one to three short sentences" and "no emojis"; his default line is "a strict friend who knows your week and wants the work done". This is a prompt change with a few fixed strings behind it, no new code paths, and the goldens and the doc page regenerated.

## What sets his voice

| Where | What it says now | Changes |
|---|---|---|
| `Settings.felixLine` (the default; yours to change, D-57) | "a strict friend who knows your week and wants the work done" | a new default in the register ("your mate who's on your case about the work", say: Q16); your phone keeps the value it has, so the new default reaches you only if you take it |
| `ChatPrompts.system` (the chat golden `chat.txt`) | "Reply in one to three short sentences of plain UK English…" | a register paragraph (below), with examples |
| `FelixPrompts.system` (`felix.txt`) | "Be concrete… Two sentences at most, under 240 characters… No guilt, no sarcasm, no emojis, no praise for nothing…" | the same register paragraph, the limits kept |
| The fixed lines in his name (`byApp`, [01](01-the-chat.md) §1) | "Thanks: the week's review comes next.", the check-in's five questions, "Your last message to me is still waiting for its answer…", a reminder's frame, "Couldn't unstar it…" | reworded in the register, in one place (`FelixWords`, a pure object), so the app's words in his bubble don't jar against the model's |
| `suggestedReplies` | "in the student's words" | as now: they're yours |
| The instructions reader, the web search, the readings, the review, the summary, the tidy | not him | unchanged |

## The register

A paragraph for both prompts, pinned by examples (AI-07), to be written with you (Q16 asks the specifics). The shape:

- **Like a sixth-former texting a mate**: lower case is fine, contractions and the odd "alr", "gonna", "tbh", "ngl"; short; no corporate politeness ("I'll put this in as a task for:"); says what he's done in a word ("done, friday 7pm"); asks the way a friend asks ("what day's that then?").
- **Still exact where it matters**: dates, times and minutes written plainly ("fri 24 oct, 19:00", "40 min"), one thing per sentence, never a joke in place of the answer; a task's id never in his words.
- **Still him** (D-57): strict about the work, no guilt, no sarcasm at you, no praise for nothing, no promises the app can't keep.
- **Emojis, swearing, caps**: your call (Q16); recommended none of the three, as now, so the voice is in the words.
- **The app's fixed lines** match it: "nice, the week's review is next", "ok so, 1 to 5, how was the week?".

Three or four made-up exchanges in each prompt pin it: a task added ("alr, added it for fri 19:00, 40 min. want me to split it?"), a question ("which one, the physics or the chem?"), a slot's start ("the worksheet started 10 min ago and nothing's happened. starting? q1 first, underline what it asks").

## What else the register touches

- **The history he's shown**: a `byApp` message is marked "(the app wrote this for you)" in `ChatPrompts.turns`, so fixed words aren't taken for his own style; the morning's fallback list likewise.
- **The length limits** stay (240 and 480 characters, three sentences): a casual voice runs long if let.
- **`FelixAnswers.message`** cuts at a sentence end: with lower case and few full stops, it may find none and cut at a word with an ellipsis. Fine, but the prompt asks for full stops between thoughts.
- **The check-in's replies** (1 to 5; the put-off work) and `Assessment.ANSWERS` ("Harder", "As expected", "Easier") stay as they are: they're matched by the app on the way back. The chips can show in his register ("harder", "about right", "easier") while the app matches either form: a small table, Q16.
- **The doc page** (`felix-inputs.md`, `FelixInputsDocTest`) is regenerated: every example's words change.

## Checking it

- **The goldens** change on purpose (`-PupdateGolden=true`); `Prompts.VERSION` stays (no reading's prompt or profile changes here).
- **The private evals** (`private/eval-chat`, `private/eval-felix`, never committed) are run again (`EvalRunTest -PrunEval=true`, about $2): the parse rate, the changes made against the labels, the dates read right. The voice must not cost a change or a date; if it does, the register's "still exact" lines are tightened and run again.
- **A read of the examples by you** before the goldens are written: the register is a matter of taste, and the examples are what pin it.

## Risks

- **Structured answers drift**: a looser voice may skip a field or put the reply's words in the wrong one. The schema holds the shape; the evals catch the rest.
- **Reading dates loosely** ("fri" when you meant next Friday): the prompt's date rules stand; the examples show relative dates resolved and said back ("fri 24 oct").
- **Too much**: a voice that's all slang tires. The examples carry a light touch; the line is yours to tune without a release (`felixLine`).

## Tests

- `GoldenPromptsTest` with the new copies; `FelixInputsDocTest` regenerated.
- `FelixWords`: each fixed line, the chips' two forms matched (`Assessment.ANSWERS` and the register's), `CheckInFlow.answered` taking "4" and "4/5".
- Nothing else: the voice is words.

## Phone check

- `chat-ask` three times (paid): the register, the dates said back, a change made; `felix-say --es reason SlotStart`: two sentences, the register, under 240 characters; the briefing the next morning.
- You read a day's messages and say what's off; the examples are adjusted in a follow-up PR (goldens only).
