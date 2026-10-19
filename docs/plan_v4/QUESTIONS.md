# Questions from building the stages

Questions that came up while the stages were built, each with a recommendation and a blank `ANSWER:` line, apart from the plan's settled ones in [ANSWERS.md](ANSWERS.md). Where one couldn't wait, the stage did what its recommendation says, and its PR says so.

---

### Q32. A source's finished task, once its source stops listing it (Stage 7, 4.0.13)

Stage 7 keeps a task finished a fortnight ago as its record alone (C-Q3): its text, reading, answers and photo go, and what its reading laid (its estimate, its steps and their ticks) stays. Your own tasks are now kept so until you delete one (03 §Stage 7). A source's task is as before: once its source stops listing it (handed in, an email unstarred), it's forgotten a fortnight after it finished (`Merge.KEEP_FINISHED_MS`), the moment it would be trimmed, unless it's the same work as one still open. The plan's "every finished task keeps its record" could also be read as keeping those for good.

- **A (recommended; what 4.0.13 does).** As today: a source's finished task is forgotten a fortnight after it finished, once its source stops listing it. The trim reaches those the sources still list (Teams' handed-in work, Power Planner's done items), those that are the same work as an open task, and your own. What the stats, the calibration and Felix learn from is the log's record, which keeps completions 120 days; a task's record kept for good would keep each email's subject and sender too, with nothing that reads them.
- **B.** Keep every source's finished task as its record for good, trimmed: `tasks.json` grows by each one's record, and [privacy.md](../spec/privacy.md) says so.
- ANSWER:
