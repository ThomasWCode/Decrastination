# Review v2: improvements

An improvements-only review of Decrastination 2.0.0 (October 2026), revised after your answers and notes in [QUESTIONS.md](QUESTIONS.md). Nothing here is a bug report (those are in `docs/review/`), nothing is implemented, and nothing you declined in v1 (`docs/review/IMPROVEMENTS-EVALUATED.md`) is proposed again.

**Start with [QUESTIONS.md](QUESTIONS.md)**: your first-round answers are recorded there with how each was applied, and a second round (Q-28 to Q-40) asks what the notes opened up. Everything else can be read as needed.

## What changed after your answers

The first version added around the app as it is. Your notes change four things at its centre, and most documents follow from them:

- **No sessions** (note 1). You read the job and do it. Start buttons, the session notification, the box experiment and session credit go (AR-09); the slot's end asks "Done / Not yet / Took longer" instead (LR-09); what earns free time without sessions is Q-28 (BL-08).
- **Every piece has a time** (notes 2–3, Q-16). Work not due today or tomorrow is spread evenly to its deadline by the time each day really has (PL-01), laid into the evening and the school's free periods as slots (PL-12, PL-15), written to your Google Calendar (NF-07) and adjusted live when a slot is kept or missed (PL-13). Dates the app or Claude set are elastic; only a source's deadline can be overdue (PL-14).
- **A person, not an app** (note 6). Claude writes the nags from your slots, your profile and what you've said before; you reply from the notification and it's a conversation (LR-03, AI-22, AI-24); a chat button is on every screen (UI-16); the morning message is in the same voice (LR-04).
- **Only starred email** (Q-04). The star in Gmail is the decision; the model reads what, when and how long (DX-03, AI-02). Three items about inbox noise are withdrawn.

Plus: the logo's colours (UI-12), two screens instead of three and a shorter menu (UI-17, UI-18), a reset (LR-08), a photo that creates or re-estimates a task (NF-01, AI-23), memories that update (AI-21), the Power Planner timetable used now (DX-09), cost taken off the table so effort is tuned for accuracy and speed (AC-02, AC-10).

## The documents

| File | What's in it | Items |
|---|---|---|
| [01-ai-prompting.md](01-ai-prompting.md) | What Claude is asked and how its answers are checked; the chat, the nag prompt, the photo readings | AI-01 … AI-24 |
| [02-ai-architecture-and-cost.md](02-ai-architecture-and-cost.md) | Effort per job, the rest after failures, the lock, a record of calls, working ahead | AC-01 … AC-10 |
| [03-data-extraction.md](03-data-extraction.md) | Starred-only Gmail, Teams, the Power Planner timetable, Anki, the calendar | DX-01 … DX-15 |
| [04-planner-and-analysis.md](04-planner-and-analysis.md) | Even spreading, slots, live adjustment, elastic dates, school windows, Stats, export | PL-01 … PL-15 |
| [05-ui.md](05-ui.md) | The Day timeline and the Work view, the menu, the logo's colours, the chat button, swipes, the widget | UI-01 … UI-18 |
| [06-blocking-and-focus.md](06-blocking-and-focus.md) | When free time is spendable, what's done without sessions, the phone at school | BL-01 … BL-09 |
| [07-learning-review-notifications.md](07-learning-review-notifications.md) | The voice and its nags, slot outcomes as the learning signal, reviews, the reset | LR-01 … LR-09 |
| [08-architecture-efficiency-testing.md](08-architecture-efficiency-testing.md) | Golden prompts, the eval task, property tests for spread and slots, removing sessions safely | AR-01 … AR-09 |
| [09-new-features.md](09-new-features.md) | Photo-to-task, the weekly summary, Anki counts, the calendar write | NF-01 … NF-08 |
| [QUESTIONS.md](QUESTIONS.md) | Round one answered and applied; round two to answer | Q-01 … Q-40 |

Every item has the same shape: **Now** (what the code does, with `file:line`), **Why**, **Proposal**, **Final state**, **Caveats**, **Effort** (S: hours; M: a day; L: several days), and a **Status** in the summary table: unchanged, Changed (with the question or note that changed it), New, Withdrawn or Deferred. Withdrawn items keep a line saying why, so the record is complete. 117 items in all, 12 withdrawn, 2 deferred.

## The order to build it in

Dependencies, not priorities; the second-round answers may reorder within each step.

1. **Property tests first** (AR-04), because every step below changes the planner.
2. **Starred-only email** (DX-03, DX-07, AI-02): small, independent, and it simplifies the model's job before anything else touches the prompts.
3. **Spreading and elastic dates** (PL-01, PL-14, PL-06, DX-14): the plan's shape, still as a list.
4. **Slots** (PL-12, PL-15, DX-09) and **the calendar write** (NF-07), then **live adjustment** (PL-13).
5. **Sessions out, slot outcomes in** (AR-09, LR-09, BL-08, BL-01/02/04/07): the incentive on the new unit.
6. **The voice** (AI-22, AI-24, LR-03, LR-04, AC-10, AC-04, AC-05, UI-16): nags need slots to nag about.
7. **The screens** (UI-17, UI-18, UI-12, UI-03/04/05, UI-08/09/10): the Day timeline needs slots to show.
8. **The readings** (AI-01, AI-03, AI-05, AI-21, AI-15, AI-16, AI-23, NF-01, NF-03): better prompts and the photo paths, in any order.
9. **The record** (LR-01, LR-08, PL-08, PL-11, NF-02, AC-07, AR-01, AR-02).

## How it was done

Every main source file was read (core, enrich, sources, block, learn, protect, data, ui, widget, sync, notify, debug), with the docs, the v1 review and its verdicts, the eval notes and the tests. Claude API facts are from the current API documentation as of this review. Claims about behaviour are tied to a line; where something couldn't be verified (the Teams widget provider's columns beyond what the app reads; Gmail's Starred folder's name in a non-English account) the item says so. The first-round answers and the extra notes were read as written by the student who uses the app, in the first person; the documents address you as that student and as the app's developer.

## What's deliberately not here

- Bugs and inconsistencies: not asked for.
- Architecture rewrites (v1's IMP-P2-023/024), search in Tasks (P2-010), a spend ledger (P2-013), the review proposing rather than applying (P2-015): declined in v1 and not reopened.
- Sending earlier thread messages (P2-003): left as decided; AI-04 is the cheaper alternative.
- A tutor in the chat, social features, a web view: considered in 09 and not recommended.
- Exams and recurring work (NF-04, NF-05): deferred by you (Q-27); their first-version text is in the git history.
