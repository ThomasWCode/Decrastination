# Review v2: improvements

An improvements-only review of Decrastination 2.0.0 (October 2026): better prompting, cheaper and more robust use of Claude, more from each source, a planner that explains itself and spaces revision, a UI with fewer taps, an incentive the student can actually collect, and the features the app doesn't have yet. Nothing here is a bug report (those are in `docs/review/`), nothing is implemented, and nothing you declined in v1 (`docs/review/IMPROVEMENTS-EVALUATED.md`) is proposed again; where a v2 item is the cheaper half of a declined one it says so.

**Start with [QUESTIONS.md](QUESTIONS.md)**: 27 decisions, each with options and a recommendation. Everything else can be read as needed.

## The documents

| File | What's in it | Items |
|---|---|---|
| [01-ai-prompting.md](01-ai-prompting.md) | What Claude is asked, how its answers are checked, the chat, the photo check, the review's input | AI-01 … AI-21 |
| [02-ai-architecture-and-cost.md](02-ai-architecture-and-cost.md) | Effort and model per job, caching, what happens after a failure, the lock, a record of calls | AC-01 … AC-09 |
| [03-data-extraction.md](03-data-extraction.md) | Gmail, Teams, Power Planner, Anki, the calendar: what's left on the table and what a sync costs | DX-01 … DX-15 |
| [04-planner-and-analysis.md](04-planner-and-analysis.md) | Spaced revision, "won't fit", day limits, why-this-day, Stats by subject, export | PL-01 … PL-11 |
| [05-ui.md](05-ui.md) | Pickers, load lines, swipes, the widget, quick replies, voice, the sheet, the session view | UI-01 … UI-15 |
| [06-blocking-and-focus.md](06-blocking-and-focus.md) | When free time is spendable, weekend mornings, short starts, the reward made visible | BL-01 … BL-07 |
| [07-learning-review-notifications.md](07-learning-review-notifications.md) | A page for reviews, the check-in, nudges, fewer questions, calibration shown, notification rules | LR-01 … LR-07 |
| [08-architecture-efficiency-testing.md](08-architecture-efficiency-testing.md) | Golden prompts, a scored eval task, property tests, screen tests, crash record, nightly backup | AR-01 … AR-08 |
| [09-new-features.md](09-new-features.md) | Photo-to-task, a weekly summary, Anki auto-count, an Exams page, recurring work, feedback tasks | NF-01 … NF-08 |
| [QUESTIONS.md](QUESTIONS.md) | The 27 decisions, with an answer sheet | Q-01 … Q-27 |

Every item has the same shape: **Now** (what the code does, with `file:line`), **Why**, **Proposal**, **Final state**, **Caveats**, **Effort** (S: hours; M: a day; L: several days) and, where one is needed, **Decision** (a Q number). Each document ends with what was looked at and left alone, so you can see what isn't being proposed and why.

## How it was done

Every main source file was read (core, enrich, sources, block, learn, protect, data, ui, widget, sync, notify, debug), with the docs (`README`, `PLAN`, `docs/*`), the v1 review and its verdicts, the eval notes, and the tests. Claude API facts (prices, the 512-token cache minimum on Opus 5.5, effort defaults, SDK syntax) are from the current API documentation as of this review, not memory. Claims about behaviour are tied to a line; where I couldn't verify something (the Teams widget provider's columns beyond what the app reads) the item says so.

## If you do ten things

In the order I'd do them, by value against effort:

1. **BL-01** Free time spendable once today's plan is done (Q-18). The reward is currently almost never collectable on a school night.
2. **AI-01** Salvage a split with one bad date (Q-01), with **AI-03** the limits stated in the prompt and **AI-02** the self-note rule. Three eval failures, all cheap.
3. **AI-16** Give the photo check the instructions, not just the piece's label.
4. **AC-04** Rest by what failed, and **AC-05** stop the chat queueing behind enrichment. An overloaded minute shouldn't cost an hour.
5. **PL-02** Say when today can't fit, and **UI-02** the load line under each day.
6. **DX-07** List only the inbox's recent part each sync; **DX-01** attachment names from the structure already fetched.
7. **PL-01** Spread revision as soft pieces (Q-14), with **AR-04** property tests under it first.
8. **AI-05** The student profile (Q-02), then **AI-07** examples and **AC-01** caching, which it makes worthwhile.
9. **LR-01** A page for the reviews, with undo.
10. **NF-04** The Exams page (Q-27), before January.

## What's deliberately not here

- Bugs and inconsistencies: `docs/review/BUGS-AND-INCONSISTENCIES.md` was the place, and this review wasn't asked for them.
- Architecture rewrites (IMP-P2-023/024), search in Tasks (P2-010), a spend ledger (P2-013), the review proposing rather than applying (P2-015): declined in v1 and not reopened.
- Sending earlier thread messages (P2-003): left as you decided; AI-04 is the cheaper alternative.
- A tutor in the chat, social features, a web view: considered in 09 and not recommended.
