# Questions from building the stages

Questions that came up while the stages were built, each with a recommendation and a blank `ANSWER:` line, apart from the plan's settled ones in [ANSWERS.md](ANSWERS.md). Where one couldn't wait, the stage did what its recommendation says, and its PR says so.

---

### Q35. The tasks' text cut to 250 characters (found running 8c's eval; for a release after 4.1.4)

8c's rule ([15](15-sent-or-asked-for.md) §The eval): the text sent with every message is cut from 600 to 250 characters only where the cut's cases score at least the baseline's in both runs after, at no more rounds. On a copy of your data, seven cut cases, each asked twice before and twice after:

- With item 7 as first written, two cases fell short: each a long email whose reading's summary is shown whole at 600 and at 250 alike, so the cut changed nothing he saw of them; he answered from the summary instead of asking for the text.
- With a line added to item 7 (where what's asked isn't in what he's shown, ask for the text rather than say it isn't there), every answer scored 1.0, at 250 and at 600 alike. One case, a long email, now takes a round in both runs where the baseline took one in two (and its answer without the round was wrong): more rounds, so by the rule 4.1.4 keeps 600. At 600 it takes the same round, so the round is that line's, not the cut's.

- **A (recommended).** A small release making it 250 (5,000 in all), on these figures: the cut cost nothing measurable, and it pays for each line saying what its text holds.
- **B.** Keep 600 till real use says otherwise.
- ANSWER:

### Q36. A briefing whose time moves to one already gone by (from 4.1.3's review)

Since 4.1.3 a weekday off has the weekends' briefing time (D-74). If a day off is taken out on the day itself after 07:15 and before its 08:30 briefing, that day's briefing is skipped: the next is tomorrow's (within a quarter of an hour of 07:00, it runs at once). Setting the time earlier in Settings always did the same. The spec says so ([learning.md](../spec/learning.md)).

- **A (recommended).** Leave it: it's rare, and the chat already answers "actually I've got school today".
- **B.** Run it at once when today's hasn't run and its new time has gone by (before noon, say).
- ANSWER:

### Q37. 10a's last phone check: a reply asking him to leave you alone for the night (from 10a's phone check)

10a's phone check ([08](08-memory-that-expires.md) §Phone check) ends with a reply "leave me alone tonight" to one of his messages: a standing instruction till 07:00 the next day, said under his answer; `felix-check` then writes nothing; the next day it has gone. It wasn't run with the rest of the check: such an instruction could have quieted his words for a reminder of his due at the next day's first slot, itself still to be checked ([14](14-your-part.md)).

- **A (recommended).** From the PC, once the day's last slot is over (a `felix-say` and the reply by `chat-ask --es replyTo`: two paid calls, a few cents), looked at the next day.
- **B.** You reply so to one of his messages yourself, and say so; it's looked at the next day.
- **C.** Drop it: `MemoryTest` holds an instruction's until, and the check's fact with an until went as it should.
- ANSWER:

### Q38. Copies of the chat archive's months left behind (found in the docs review after 4.2.0)

A store brought to a new version keeps the file it came from as `<name>.v<n>` for a week (D-94, `Stores.letGoOfOldShapes`). The chat archive's month files (`chat-archive-<yyyy-MM>.json`) are brought the same way (`JsonStore.keepOldShape`), but the week's let-go looks only for the main stores' names, so a month's copy is never let go of; and *Clear the chat* and the chat's reset delete only the month files, so a copy keeps what was cleared. 4.2.0 raises the months to version 5, so each month there is gets a copy as it's next read. The docs say what D-94 wants, not this.

- **A (recommended).** A small fix release before 10b: the let-go takes the months' copies too, and a clear or a chat reset deletes them with the months, with tests.
- **B.** Leave it to Stage 12's sweep.
- ANSWER:

### Q39. Teams' automatic syncs on a weekday off (found in the docs review after 4.2.0)

Blocking and, since 4.1.3, the briefing go by the school calendar (D-74). The Teams widget's automatic syncs still take every weekday as a school day (`TeamsAutoSync.allowed`), so on a weekday off they wait for the school day's blocking time, as [sources.md](../spec/sources.md) says.

- **A (recommended).** By the school calendar too: a weekday off as a weekend day.
- **B.** Leave it.
- ANSWER:
