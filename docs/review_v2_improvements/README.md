# Review v2: improvements

An improvements-only review of Decrastination 2.0.0 (October 2026), revised after three rounds of your answers and notes in [QUESTIONS.md](QUESTIONS.md). Nothing here is a bug report (those are in `docs/review/`), nothing is implemented, and nothing you declined in v1 (`docs/review/IMPROVEMENTS-EVALUATED.md`) is proposed again.

**Start with [PLAN-OVERVIEW.md](PLAN-OVERVIEW.md)**, the whole design walked through a school day, a weekend day and a week, with the gaps it found; then [QUESTIONS.md](QUESTIONS.md), where all three rounds are recorded with how each answer was applied, and round four (Q-47 to Q-55) asks about those gaps. Everything else can be read as needed.

## What the review now proposes

The first version added around the app as it is. Your notes changed four things at its centre, and round two filled in their details:

- **No sessions** (note 1). You read the job and do it. Start buttons, the session notification, the box experiment and session credit go (AR-09). The slot's end is a question from Felix: Done, Not yet, Took longer (LR-09). A tick keeps any slot; a short piece earns free time on the tick, a long one earns when its photo check passes (BL-08, Q-28).
- **Every piece has a time** (notes 2–3, Q-16, Q-30, Q-32). Work not due today or tomorrow is spread evenly to its deadline by the time each day really has (PL-01); pieces are 30 minutes; they're laid into the evening and the school's study periods, break and lunch (PL-12, PL-15), written to your Google Calendar (NF-07) and adjusted live when a slot is kept or missed (PL-13). Weekends get three 20-minute outside breaks a day that give way to work when the day is short; holidays and half-days you tell Felix (PL-16); weekend hours become two windows (PL-17, Q-49). Dates the app or Claude set are elastic; only a source's deadline can be overdue (PL-14). The phone's rules at school don't change (BL-09, Q-29).
- **Felix** (note 6, Q-31, Q-42, Q-44). A strict friend who writes every message in the app from your slots, your profile, the facts he remembers and your standing instructions about how he should behave; speaks only around scheduled work; sets his own gap to the next check; applies a reply that alters an existing task at once and offers a button for anything new (LR-03, AI-22, AI-24, AI-21); is the chat on every screen (UI-16); gives the morning message (LR-04) and the Sunday reminder for the summary to your dad (NF-02).
- **Only starred email** (Q-04). The star in Gmail is the decision; the model reads what, when and how long (DX-03, AI-02).

Plus: the logo's colours (UI-12), Day and Work screens with a shorter menu (UI-17, UI-18), a reset (LR-08), a photo that creates or re-estimates a task (NF-01, AI-23), memories and standing instructions that update on their own, under a budget rather than a cap (AI-21), the Power Planner timetable used now (DX-09), and effort tuned for accuracy and speed with cost off the table (AC-02, AC-10).

## The documents

| File | What's in it | Items |
|---|---|---|
| [01-ai-prompting.md](01-ai-prompting.md) | What Claude is asked and how its answers are checked; the chat, Felix's prompt, the photo readings | AI-01 … AI-24 |
| [02-ai-architecture-and-cost.md](02-ai-architecture-and-cost.md) | Effort per job, the rest after failures, the lock, a record of calls, working ahead | AC-01 … AC-10 |
| [03-data-extraction.md](03-data-extraction.md) | Starred-only Gmail, Teams, the Power Planner timetable, Anki, the calendar | DX-01 … DX-15 |
| [04-planner-and-analysis.md](04-planner-and-analysis.md) | Even spreading, slots and outside breaks, live adjustment, elastic dates, school windows, the school calendar, weekend windows, Stats, export | PL-01 … PL-17 |
| [05-ui.md](05-ui.md) | The Day timeline and the Work view, the menu, the logo's colours, the chat button, swipes, the widget | UI-01 … UI-18 |
| [06-blocking-and-focus.md](06-blocking-and-focus.md) | When free time is spendable, what's done and what earns without sessions, the phone at school, gaps and missed slots | BL-01 … BL-11 |
| [07-learning-review-notifications.md](07-learning-review-notifications.md) | Felix and his pacing, slot outcomes as the learning signal, reviews, the reset | LR-01 … LR-09 |
| [08-architecture-efficiency-testing.md](08-architecture-efficiency-testing.md) | Golden prompts, the eval task, property tests for spread and slots, removing sessions safely | AR-01 … AR-09 |
| [09-new-features.md](09-new-features.md) | Photo-to-task, the weekly summary, Anki counts, the calendar write | NF-01 … NF-08 |
| [PLAN-OVERVIEW.md](PLAN-OVERVIEW.md) | The design end to end: a school day, a weekend day, a week; assumptions; the gaps found | – |
| [QUESTIONS.md](QUESTIONS.md) | Rounds one to three answered and applied; round four to answer | Q-01 … Q-55 |

Every item has the same shape: **Now** (what the code does, with `file:line`), **Why**, **Proposal**, **Final state**, **Caveats**, **Effort** (S: hours; M: a day; L: several days), and a **Status** in the summary table: unchanged, Changed (with the question or note that changed it), New, Withdrawn, Deferred, or settled by a round-two answer. Withdrawn items keep a line saying why. 121 items in all, 12 withdrawn, 2 deferred.

## The order to build it in

Dependencies, not priorities.

1. **Property tests first** (AR-04), because every step below changes the planner.
2. **Starred-only email** (DX-03, DX-07, AI-02): small, independent, and it simplifies the model's job before anything else touches the prompts.
3. **Spreading and elastic dates** (PL-01, PL-14, PL-06, PL-17, DX-14): the plan's shape, still as a list.
4. **Slots** (PL-12, PL-15, PL-16, DX-09) and **the calendar write** (NF-07), then **live adjustment** (PL-13).
5. **Sessions out, slot outcomes in** (AR-09, LR-09, BL-08, BL-01/02/04/07, BL-10, BL-11): the incentive on the new unit.
6. **Felix** (AI-22, AI-24, AI-21, LR-03, LR-04, LR-05, AC-10, AC-04, AC-05, UI-16): he needs slots to speak about.
7. **The screens** (UI-17, UI-18, UI-12, UI-03/04/05, UI-08/09/10): the Day timeline needs slots to show.
8. **The readings** (AI-01, AI-03, AI-05, AI-15, AI-16, AI-23, NF-01, NF-03): better prompts and the photo paths, in any order.
9. **The record** (LR-01, LR-08, PL-08, PL-11, NF-02, AC-07, AR-01, AR-02).

## How it was done

Every main source file was read (core, enrich, sources, block, learn, protect, data, ui, widget, sync, notify, debug), with the docs, the v1 review and its verdicts, the eval notes and the tests. Claude API facts are from the current API documentation as of this review. Claims about behaviour are tied to a line; where something couldn't be verified (the Teams widget provider's columns beyond what the app reads; Gmail's Starred folder's name in a non-English account) the item says so. Your answers and notes were read as written by the student who uses the app; the documents address you as that student and as the app's developer.

## What's deliberately not here

- Bugs and inconsistencies: not asked for.
- Architecture rewrites (v1's IMP-P2-023/024), search in Tasks (P2-010), a spend ledger (P2-013), the review proposing rather than applying (P2-015): declined in v1 and not reopened.
- Sending earlier thread messages (P2-003): left as decided; AI-04 is the cheaper alternative.
- A tutor in the chat, social features, a web view: considered in 09 and not recommended.
- Exams and recurring work (NF-04, NF-05): deferred by you (Q-27); their first-version text is in the git history.
