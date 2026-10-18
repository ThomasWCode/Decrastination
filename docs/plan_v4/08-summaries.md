# Summaries for him: an evaluation

You asked (18 Oct) whether constant summarisation calls would be a good idea: models summarise the emails, the chat log and so on, the summaries are stored, and he's sent those instead of the raw data. This is an evaluation, not a plan: what he reads raw today, what's summarised already, where a summary would help, what it would cost, and a recommendation. Q28 asks what to do with it; if taken, it becomes a document of its own with steps, tests and a phone check like the others.

## What he reads today, and how much of it

| Input | Where | Raw or condensed | Room |
|---|---|---|---|
| The open tasks: title, class, due, minutes, steps, their pace | `ChatPrompts.context` | structured by the app | a line a task |
| Each open task's own text (an assignment's instructions, an email) | `ChatPrompts.context` through `TaskText.brief` | **condensed already**: a text over about 600 characters gets the reading's summary (D-93, 3.2.0: `Enrichment.summary`, written by the reading at no extra call); a shorter one goes whole; the task a message is about and the ones he asks to read go whole | `BRIEF_TEXT` 600 each, `MAX_TEXTS` 12,000 in all; `WHOLE_TEXT` 8,000; `READ_TEXT` 16,000, three at most |
| The plan's next 14 days, why each piece is where it is | `ChatPrompts.plan` | structured | `PLAN_LIST` 20 lines |
| The calendar's fortnight, the changes in use, the settings, your finished tasks of the week | `ChatPrompts.context` | structured | short |
| The chat so far | `ChatPrompts.turns` | **raw, cut**: the last 12 messages (`HISTORY`), within a quarter of the room | a few thousand characters |
| What he remembers, and the profile | `Profile.block` | **condensed already**: the facts are a summary, and the weekly tidy rewrites them when they pass 70 % of their 6,000 characters | 2,000 + 6,000, within 16,000 |
| His own messages' context | `FelixPrompts.describe` | the piece's task text to `TEXT` (1,500, the reading's summary where there is one); his last six messages and your replies | short |
| The week, for the review and the summary email | `ReviewInput`, `Summary.input` | numbers and lists from the log | short, weekly |

So the biggest raw input, the emails and the assignments, is condensed already: each reading writes a summary of a long text in the call that reads it, and the chat's list gives the summary in place of the text. The chat history is the one raw stream that's cut rather than condensed, and with every message of his in the chat (4.0.0) it gets longer: a busy day is a dozen of his.

## Where a summary would help, and where not

| Candidate | Worth it? | Why |
|---|---|---|
| **The conversation** beyond the last 12 messages | **Yes: the one real gap** | He sees 12 messages. "You said Thursday for the essay" from last Tuesday is gone; so is what he asked and you didn't answer. A rolling digest ("Earlier in the chat: …", 600 characters) kept with the chat and sent before the history would hold what was agreed, asked and put off. Memory holds facts about you, not the thread of the talk. |
| Emails and assignments | No: done | D-93's summaries. The one improvement left is a summary for every text, not only long ones, which is a line in the readings' prompts, not a new call. |
| The plan and the calendar | No | Structured and short, and his actions need the exactness (times, ids, keys) a summary loses. |
| The week's record | No | The review's note is that summary, weekly, with its own call; his morning gets the day's shape from the plan. |
| His own messages | No | Six short messages; a summary would cost more than they do. |
| Memory | No: done | The tidy is a summariser, and expiry ([04](04-memory-that-expires.md)) prunes the rest. |

## What a conversation digest would be

- **Kept**: `ChatState.digest: String` (at most 600 characters) and `digestedTo: String` (the id of the last message it covers), in `chat.json`; shown on the debug page only; in the backup.
- **Written**: by a small call (`ModelEffort.Job.Digest`, the cheapest model, under the cap and the rests like every call; none while Claude's off): the digest so far and the messages since, "rewrite the digest in 600 characters: what they've agreed, asked for, put off and said about their week; drop what's done". Once a day at the nightly run when ten or more messages are new, or sooner when the window is about to drop a message the digest doesn't cover. About a cent a day.
- **Sent**: in `ChatPrompts.turns` before the history ("Earlier in the chat: …") and in `FelixPrompts.describe` ("Lately in the chat: …"), with a line saying it's a summary and the messages after it are what was said.
- **Risks**: a wrong digest is wrong for a day (the prompts say the messages win); the private evals get a few "from last week" questions; nothing of yours goes anywhere it doesn't already (the same messages go in the history today).
- **Size**: about a day: the pure parts (`ChatDigest.due`, `describe`, `after`), the call through the gateway, a golden, the two prompts' lines, the doc page, the tests.

## Recommendation

Not in 4.0.0 to 4.3.0. Build the digest as a small 4.4.0 once the chat holds everything and you've lived with the 12-message window a few weeks: by then it's clear whether what he forgets is the thread of the talk (the digest fixes that) or facts about you (memory's job, [04](04-memory-that-expires.md)). Everything else on the list is done already or isn't worth a call. Q28 asks for your call; if you'd rather have it with the chat work, it's a PR of its own after 4.0.0-C.
