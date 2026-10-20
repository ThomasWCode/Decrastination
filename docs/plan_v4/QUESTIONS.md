# Questions from building the stages

Questions that came up while the stages were built, each with a recommendation and a blank `ANSWER:` line, apart from the plan's settled ones in [ANSWERS.md](ANSWERS.md). Where one couldn't wait, the stage did what its recommendation says, and its PR says so.

---

### Q33. "Check Teams" in the chat: what the widget has, or its own sync too (Stage 8b, 4.1.1)

G2's `sync` reads a source through the Sync service's `syncNow`, as the plan wires it (03 §Stage 8b, 06 G2). For Teams that reads the Teams widget's list as it stands: what its last sync found, and any hand-in it saw while Teams was open on the phone. A hand-in made elsewhere, on a computer, isn't in that list until the widget syncs again, so "I've handed it in, check Teams" can find nothing new, and the quarter-hour watch ends with nothing said (Codex's seventh round on #92). Asking the widget to sync, as its refresh does on a tap (`Sync.refreshAll(teams = true)`), takes over the screen for a minute, and D-11 says when that happens: on a tap, at the first unlock after the evening start and every three hours, each automatic one with its banner.

- **A (what 4.1.1 does till you answer).** As built: the chat reads what the widget has and never starts its sync. A hand-in made elsewhere comes with the widget's next sync, by D-11.
- **B (recommended).** With the chat on screen, as G3's open has it, "check Teams" also asks the widget to sync, as a tap on its refresh would: you asked, at the phone. From his notification's reply, or with the chat left, it reads what the widget has, and says so. D-11 gains the chat's ask. A small change, before 8b merges or in a release after it.
- ANSWER:
