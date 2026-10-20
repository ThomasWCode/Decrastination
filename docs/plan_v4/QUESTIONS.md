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

Since 4.1.3 a weekday off has the weekends' briefing time (D-74). If a day off is taken out on the day itself after 07:00 and before its 08:30 briefing, that day's briefing is skipped: the next is tomorrow's. Setting the time earlier in Settings always did the same. The spec says so ([learning.md](../spec/learning.md)).

- **A (recommended).** Leave it: it's rare, and the chat already answers "actually I've got school today".
- **B.** Run it at once when today's hasn't run and its new time has gone by (before noon, say).
- ANSWER:
