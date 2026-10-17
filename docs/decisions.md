# Decisions

The owner's decisions that govern how the app is developed, numbered so code and docs can point at them ("(D-21)"). Each gives the decision, what it means for the code, and where it applies; dates are 2026. Only the latest form of a decision is kept; "(not: …)" names an older form where it might be proposed again.

How the app works in detail is in [spec/](spec/); how the work is done is in [development.md](development.md). A new decision gets the next number in the PR that applies it; one that changes is edited in place, with its new date.

## Principles

- **D-1. An organiser first, a blocker second** (2.0.0, 12 Oct). The work (deadlines, minutes, days, steps) can always be changed at once; only switching blocking off is guarded (D-46).
- **D-2. The core stays deterministic.** Ranking, deadlines, allocation, quotas and "done" never depend on Claude, whose readings are advice the rules check and can stand in for. Applies: `core/`.
- **D-3. One phone, everything on it.** No PC or server at runtime; a sideloaded app for its one owner.
- **D-4. The app's rules apply to the app's own output; what you or Felix set explicitly is kept as set** (17 Oct). A limit or adjustment the app makes (sittings, the learned pace) never rewrites your or Felix's values; where the app's knowledge would help with them, Felix is told it instead. Applies: every enforcement or normalisation, with `TaskItem.yourSteps`, `yourMinutes` and the `user*` overlays exempt.
- **D-5. Quality over cost.** Claude is tuned for good readings and speed, not cheapness; but no call is wasted (D-90, D-91).

## Sources

- **D-6. Teams through the Teams Assignments widget app, kept as an app of its own** (17 Oct). Read-only, through its signature-protected provider (both apps share `app/debug.keystore`). The widget app must stay installed: it's the Teams reader; its home-screen widget itself is optional. Merging its backend into this one isn't worth it now; it's worth revisiting only if changes across both repositories become frequent. Changes to it follow its own repository; this app reads a new column only when it's there. Applies: `sources/teams/`.
- **D-7. Sources are only ever read.** The only thing the app writes outside itself is its own events in the calendar you chose.
- **D-8. Power Planner through its web API**, credentials encrypted, polled every 15 minutes (its rate limit accepted); its timetable is used, free periods being classes named "Study Period". Applies: `sources/powerplanner/`.
- **D-9. AnkiDroid through its provider.** Homework is matched to decks by textbook section, with a current-textbook setting (Q18). The quota: every due review, 20 new cards from the current textbook's lowest deck, and decks homework names, due 21:30; on a day homework vocabulary is due, reviews only; counted in cards. Applies: `sources/anki/`.
- **D-10. Gmail: only starred conversations, each a task** (3.0.1). IMAP with an app password; every starred conversation is something to do (an event's email included), read whole with its replies; unstarring finishes it (DX-03), and, as every starred email is Admin, that earns free time as any finished task does (17 Oct). (Not: the whole inbox, or only emails that ask for something.) Applies: `sources/gmail/`, `core/Merge.kt`.
- **D-11. Teams syncs** on a tap, on the first unlock after the evening start, and every 3 hours; each automatic one shows a 10-second banner with Cancel and Delay, only while the phone's in use and never in quiet hours (Q21).
- **D-12. The same work from two sources is linked by a tap, never automatically** (DX-15).
- **D-13. Other wellbeing apps on the phone are left alone.**

## Claude's readings

- **D-14. Claude is opt-in; the rules stand in when it's off.** Applies: `enrich/RuleEnricher`.
- **D-15. One model for every job**, thinking hard on the readings the plan is made from and less where someone waits (AC-02).
- **D-16. A task is read once, and again only when its content changes** (`Enrichments.inputHash`). A change to the prompts (`Prompts.VERSION`) reads open tasks again once, except those with a step of their reading done (AC-06).
- **D-17. A reading that fails a check is put back once, then salvaged, then dropped** (AI-01).
- **D-18. Only a real deadline can be missed:** a source's, one you gave, or an email's own words as Claude quotes them. Every date the app or Claude sets is a plan, never overdue (PL-14, AI-12).
- **D-19. A reading the text can't settle asks one question, through Felix** (AI-10).
- **D-20. Steps are as few as the work has** (3.1.1, 17 Oct): one step unless the instructions list separate pieces of work; dated blocks only for separately dated pieces; a few minutes' admin goes in the title of the step it belongs to, never a step of its own.
- **D-21. Nothing is split unless it's over an hour, then by time, never by content** (3.1.2, 17 Oct). Even sittings of an hour at most: the first the work, then "Continue: …", the last "Continue and finish: …"; at reading and again at planning, where the plan's minutes are longer. The app's own steps only (D-34). Applies: `core/Sittings.kt`, `Answers.fitParts`, `Planner.sittings`.
- **D-22. Micro-plans for every slot; photo checks take several pages, with no partial credit; a photo can make a task or re-estimate one.**
- **D-23. The profile tells Claude everything useful about you** (your year, subjects, week, Felix's memories) without crowding the context.
- **D-24. The API's model fallback stays on**, and a reading is credited to the model that answered.

## Planner

- **D-25. Work not due today or tomorrow is spread evenly to its deadline** by each day's free time, a piece at least once a week, with no daily maximum (PL-01). (Not: placed backwards from the deadline.)
- **D-26. Work due today or tomorrow comes before overdue work.**
- **D-27. A day's capacity is its free time times the share of it you really use**, learned per kind of day (between 0.5 and 1.0; 1.0 until seven such days) (PL-06). A weekday off school is a weekend's day, planned with the weekend's share and teaching it (17 Oct). Applies: `Planner.capacityOf`, `learn/Days.record`.
- **D-28. A weekend keeps one long window**; two weekend windows aren't built.
- **D-29. Revision goes a day apart**; there's no evening-start preference.
- **D-30. Work can be moved as often as you like, never past its due date**; overdue work can go to any day or time from now, and is planned there, still marked overdue (17 Oct). Applies: `Instructions.latePin`, `Planner.keptDay`, `pinnedTime`, `pinProblem`.
- **D-31. A deadline later the same day can use that day's school windows before it** (Q-52).
- **D-32. The planner stays pure**: everything it uses comes in through `Planner.Input`.
- **D-33. A piece's length (`boxMin`) is yours** (17 Oct): 60 minutes on your phone; the weekly review never changes it.
- **D-34. Steps you or Felix gave are planned exactly as given** (17 Oct): never cut into sittings, never stretched by the learned pace (`TaskItem.yourSteps`).
- **D-35. The learned pace stretches only the app's own estimates** (2.0.0; 17 Oct): your minutes, your own tasks and steps you or Felix gave are planned as given (`Calibration.multiplierFor`).
- **D-36. What isn't planned is listed with why** (the Plan's *Not planned*); there's no forecasting of work not yet open.
- **D-37. Days off and half-days are told to Felix** (or set in Settings): a day off takes the weekend's rules, a half-day's evening starts at the early finish.
- **D-38. No exams page, recurring work or streaks.**

## Slots and the calendar

- **D-39. Every piece gets a time** in the day, written to the calendar a week ahead and kept up to date as the plan changes (PL-12, NF-07).
- **D-40. Breaks:** five minutes between pieces, a quarter of an hour after an hour and a half of work. School windows are the Study Periods, break and lunch (less time to eat), at least 15 minutes. Each step says where it can be done (anywhere, or home), and you can change it (PL-15).
- **D-41. Weekends and days off get three 20-minute breaks outside**, given up in number (the midday one first) but never shortened.
- **D-42. Slots go to a calendar you made in Google Calendar** (one the app makes itself wouldn't sync). A slot you move there pins its piece at your time; an event you add takes the time from a slot; a slot's event you delete comes back only if its time is still free.
- **D-43. The app never deletes an event of yours that it can tell from its own** (3.1.1–3.1.2, 17 Oct). A copy the calendar's sync makes of one of its events is deleted only when it matches a version the app itself wrote earlier (fingerprints in `calendar.json`); an event already there under a new slot's exact title, times and wording is never taken for one. The limit: one you make later as an exact copy of an earlier slot can't be told from a sync's copy. Applies: `calendar/SlotCalendarDiff.kt`.
- **D-44. Calendar events count as busy time, judged one by one**: the app asks when it can't tell, and one answer covers every event of that name (Q7). Until you answer, an event asked about takes all its time (17 Oct). Applies: `learn/EventJudge.kt`.

## Blocking and protection

- **D-45. Blocking is an accessibility service and a full-screen block screen, with no bypass.**
- **D-46. Once armed, only loosening blocking is guarded** (2.0.0, 12 Oct): disarming; taking apps, sites or browsers off the lists; longer quiet hours; a later school-day start; fewer work minutes per free minute (the weekly review's own change aside, D-71); a shorter wait. Those take the 24-hour wait or the override code; everything about the work changes at once (due dates included). (Not: due-date changes needing the code.) Applies: `protect/`.
- **D-47. The override is a code from an authenticator app a parent holds** (Q17); nothing is ever emailed. Protection is friction, not security.
- **D-48. The self-repair permission is granted over adb** (Q19).
- **D-49. What's blocked:** video and social apps, their sites in Chrome and Brave, and Firefox and Tor outright while blocking applies. Never blocked: calls, messages, study apps, maps and travel, banking, authenticators, AI assistants, health and support apps.
- **D-50. When:** from 16:45 on school days and 10:00 at weekends and on days off, never in quiet hours (22:30–07:00) (Q6, Q6b); only today's slots keep it on.
- **D-51. Free time:** about a minute for every three of work, gone at midnight, never banked; blocked apps can't be used for study. It can be spent in the gaps between today's slots; a slot missed today keeps tonight's block on, a move you make lifts it.
- **D-52. A tick keeps any slot**; a piece under 20 minutes earns on its tick, a longer one with a photo (later the same day too), never more a day than its plan (BL-08). Finishing a task earns by the lower of your minutes and the app's estimate at the pace learned, each in its own scale.
- **D-53. A day with nothing planned opens with an hour of free time.**
- **D-54. Done on your own tasks earns nothing; a source's task can't be ticked done by hand**: its source says when it's done (Q28).
- **D-55. Focus sessions are gone and stay gone**: slot outcomes are the record (LR-09).
- **D-56. The blocker is checked by hand on the phone**; there's no emulator harness.

## Felix and the chat

- **D-57. Felix, a strict friend, writes every message** (Q-31); his name and line are yours to change, with no tone setting. "Felix" wherever you're spoken to; "Claude" only for the service, in Settings (Q-44).
- **D-58. He speaks only around scheduled work**, school's slots included, setting his own next check (20 minutes if he names none), with no daily ceiling; silent in quiet hours and busy events; a standing instruction can quiet him.
- **D-59. One chat, his, in place of the Instructions page** (Q29), his button on every screen.
- **D-60. Everything Felix gives is made at once, with an Undo** (3.1.0, 16 Oct): changes, new tasks, days off, busy times, limits, ticks, settings. (Not: cards you apply.)
- **D-61. What Felix may change** (16 Oct): any task's steps (not Anki's) and name; your own tasks (done, open again, delete); the settings about the work and about him (`data/FelixSettings.kt`). Never blocking's, protection's or Claude's settings. A source's task is done only when its source says so.
- **D-62. Felix can look things up on the web**, in a call of its own, up to three searches, with a switch in Settings (16 Oct).
- **D-63. Felix knows your pace; his times are planned exactly as he sets them** (3.1.3–3.1.4, 17 Oct). His context gives the learned pace and what each task's minutes come to in the plan by it; he allows for it where he estimates, and uses your minutes where you give them. A total he works out from the app's estimate at your pace is marked paced (D-69).
- **D-64. Felix remembers facts and standing instructions on his own**, each shown with an Undo, within a budget and tidied weekly; you never have to prune them.
- **D-65. The morning briefing is Felix's message alone**, with no list under it.
- **D-66. The weekly summary for home**: a button and a Sunday reminder; all in the email's body, in your voice, sent from the phone's email app, never automatically. Its address is set only on the phone.
- **D-67. The instructions screen reads your own words into changes, made as soon as they're read** and listed under *Your changes* for you to check and take back, as Felix's are; it's never told the pace, and its totals are always your own. (Not: a reading waiting for Apply.)

## Learning and review

- **D-68. The app measures and calibrates**: a self-assessment after each completion and a Sunday check-in; slot outcomes are what it learns from.
- **D-69. Your own reckoning teaches the pace nothing; a total Felix works out at your pace still does** (3.1.4, 17 Oct), measured against the app's estimate it came from, while the task's content is unchanged (`TaskItem.pacedTeaches`; a read for newer prompts keeps it, new content ends it). Steps you or Felix give without a new total are planned as given, and the task still teaches the pace against the app's estimate, its total.
- **D-70. A class's own multiplier counts from its third timed piece**; its kind's until then (IMP-P2-016).
- **D-71. The weekly review's bounded changes apply at once, armed or not**, and say what changed: the margin, undated minutes, the free-time ratio, school-day hours by up to an hour; never the piece length (D-33). Reviews that only propose were declined. The ratio can go anywhere in its range, so the review may loosen a stricter one you set without D-46's wait (17 Oct, as intended).
- **D-72. Reset**: a row per area and *Everything learned*, a backup first, no code; never the key, the sources or protection.

## Notifications

- **D-73. Channels by purpose; routine notifications at most four a day, none in quiet hours.** Felix is outside that count but silent in quiet hours and busy events.
- **D-74. The briefing at 07:00 on school days and 08:30 at weekends.**
- **D-75. While free time is spent, a silent countdown, and a heads-up a minute before the end.**

## Storage and backups

- **D-76. No backward compatibility between versions, but your data survives every upgrade**: a change of shape migrates once at start-up. Never delete your data to quiet a warning; quiet the warning.
- **D-77. A backup every night to `Documents/Decrastination`**, a fortnight kept, with no folder to pick (AR-07); a copy off the phone is yours to keep.
- **D-78. Backups never hold passwords or keys**; credentials go in over adb from the PC's environment, never printed or committed.

## Privacy and the public repository

- **D-79. The repository is public**: real data lives in the git-ignored `private/` and is never committed; the evaluation set stays there, only its harness committed. Nothing personal goes in code, tests, docs or PR text ([development.md](development.md#the-repository-is-public)).
- **D-80. What's already in the repository may stay** (1.7.0; 17 Oct): the existing fixtures' real teacher and class names, the class group link in an assignment's instructions there (not sensitive), and the real email, appointment and homework text, family references and first name in some code comments, tests, strings and the weekly summary's prompt (`GmailSource`, `Mime`, `PowerPlannerItems`, `WidgetModel`, `AnkiRulesTest`, `Totp` among them), none of it to be cleared. New fixtures are made up, and nothing more personal is added.
- **D-81. Rewriting the repository's history to remove data is your call**: answered in the cleanup plan's Q8 ([plan_targeted_cleanup/QUESTIONS.md](plan_targeted_cleanup/QUESTIONS.md)).

## Development

- **D-82. Each release is a PR, checked on the phone before it's merged**: backup first, `adb install -r`, never uninstalling ([development.md](development.md#the-phone)).
- **D-83. Codex rounds are your call per PR**: by default the round's findings fixed, then up to two more, stopping at a round with at most one P2; some PRs one round, some none. Every thread is answered and resolved; no `@codex review` may be left unanswered at merge.
- **D-84. Merged with a merge commit** (`Merge #N: <title>`), the branch deleted, a release tagged `vX.Y.Z`.
- **D-85. No attribution, co-author or session lines** in commits or PR descriptions.
- **D-86. Every change of behaviour has unit tests**; CI runs the tests, lint and assemble; no test is switched off.
- **D-87. A prompt or schema change updates the golden copies; one to what a reading is made by raises `Prompts.VERSION`.** A changed schema is compiled by the API before it's released (`SchemaCompileTest`, 17 Oct), and a list's fields are never nullable.
- **D-88. A new setting needs a default, a range, a label and a field on its screen**, and its place in protection's list if it can loosen blocking.
- **D-89. The docs change with the code**, in the same PR: the spec for behaviour, this file for decisions. Comments say what and why, and cite requirement ids and decisions.
- **D-90. Not built:** focus sessions, streaks, an exams page, recurring work, rules by sender, two weekend windows, task search, a free-time ledger, reviews that only propose. A rewrite of the app isn't wanted; its restructuring is planned instead ([plan_targeted_cleanup/](plan_targeted_cleanup/README.md)).

## Costs

- **D-91. No monthly cap by default** (the account is prepaid and stops by itself, with an alert); a cap can be set, in dollars.
- **D-92. Prompts are tried in Claude Code first; paid runs are deliberate and small** (an evaluation run about $2), and model calls in development are fine for testing but never in loops. A full re-read on the phone is about 40 calls, about 60 cents.

## Open

What's still waiting for you:

- **Returned work with feedback (NF-06)**: not to be built yet (17 Oct). This app's half is ready (`TeamsSource` reads the widget app's `/returned`); the widget app's isn't. A returned piece of work without feedback, like the old one in Teams, is enough for most of it: finding returned work (its card in Completed, its page's status and date, the Activity feed's "returned an assignment" row) and the widget app's `/returned` rows. Only one with feedback shows where the feedback sits on its page and how it's laid out (its heading, its text, points, whether a button hides it), so reading the feedback itself waits for one, or is built from a guess and checked when one comes.
- **Not yet tried on the phone**: a photo check of written homework and photographing homework into a task; re-arming with the authenticator kept, and the *Arm now?* list (needs the parent); the free-time countdown; the widget's red ! at its smallest size.
