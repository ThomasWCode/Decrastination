# Requirement ids

Every requirement or decision id the code cites in its comments, such as `(PL-12)` or `(Q-31)`, with what it requires of the app now, so those references keep their meaning once the old plans and reviews are gone.
Grouped by prefix and sorted; where a later decision changed an item, its line gives today's rule, not the first version.

## AC: Claude's calls and their cost

- AC-01: Each call sends its job's instructions (with their limits and examples) first, marked to be cached for an hour; the student's profile follows, uncached, as it changes.
- AC-02: How hard the model thinks is set per job: high for the readings the plan is made from (emails, assignments, estimates, instructions, the weekly review, a photo read as a task or as context); medium for answers someone waits for (the chat, replies, Felix, first steps, photo checks, the summary, the memory tidy, web searches).
- AC-04: After a failed call, background reading rests for a time set by what failed (busy, the server, a timeout, no network, a refused request) and by how many failed in a row, at least an hour once too many fail within an hour; a task whose request the API refuses keeps the rules' reading until it changes; interactive calls still go through a rest.
- AC-05: Interactive calls never queue behind background reading: the lock covers only checking and counting, the monthly cap counts calls in flight at their dearest, and overlapping calls never move the record back a month.
- AC-06: Every reading records the prompts' version; open tasks read by older prompts are read again once (unread ones first, then the oldest prompts', soonest due first, within each run's call limit), except a task with a step done; *Read again with Claude* on a sheet does one at once.
- AC-07: The last 100 model calls are kept on record (job, what it was about, effort, tokens in, out and from the cache, latency, stop reason, outcome, cost) and can be read on the phone.
- AC-08: While an answer is awaited the chat shows it's coming and, after 10 seconds, offers a way to stop waiting; a call already sent still comes back and is counted, its answer unused.
- AC-10: After each plan, work is done ahead: Felix's next message is written before it's due and sent the moment it is if it still fits, and today's and tomorrow's pieces get their first steps written once for each task as it stands.

## AI: what Claude is asked, and how its answers are checked

- AI-01: A reading that fails the app's checks is asked again once, with its own answer and the exact complaint; meanwhile, and if that fails too, the reading stands with only what can be left out (an offending step's dates) left out; one that can't be put right is dropped, said on the task and in an alert.
- AI-02: The Email job treats every starred email as something to do: its only kind is Admin (an older answer's Event is read as Admin), always with a next step, and a starred notification or newsletter becomes "read it and decide".
- AI-03: Each job's prompt states the limits its answer is checked against, generated from the checks' own values so the two can't drift.
- AI-04: The model is told when it is reading a whole conversation (how many messages) and which attachments it can't see, so it doesn't guess at what they ask.
- AI-05: Every model call carries the student's profile as a second system block: year group, subjects and class codes, the school day and timetable, working hours, days off coming, the textbook, the student's own lines, standing instructions and remembered facts, each within a fixed character budget.
- AI-06: The student's year group is worked out from a setting and the date, rolling over each September, never written into a prompt.
- AI-07: Each job's system prompt carries a few made-up worked examples, an input and the exact answer expected, never real names or addresses.
- AI-08: A task read again is shown its steps as planned before, so work that hasn't changed keeps its step titles (and their ticks) and a done step is kept as it is.
- AI-09: Each reading quotes the phrase in the text that gave its deadline, kind or test date (at most 160 characters), shown on the task's sheet.
- AI-10: When the text can't settle a reading, the model may ask one question; Felix puts it to the student, and the answer is kept with the task, which is read again with it.
- AI-12: Dates the model sets are soft: only an email's own stated deadline, marked as quoted, can be a real deadline; a date it suggests or guesses is a plan (PL-14).
- AI-13: The chat is told the time now and whether each of today's slots has gone by, is under way or is to come, so a time later today isn't taken as past.
- AI-14: The chat's answer carries actions as well as changes (a step ticked or redone, a task shown with Open or opened where it lives, renamed, done, reopened or deleted, a setting set, a piece kept or taken back, a source read now, a reminder cancelled, a note deleted), each made at once and said under the answer with its Undo (a task opened or a source read has none), folded behind the answer's chevron till it's opened; a source's task is still done only when its source says so.
- AI-15: Each of today's and tomorrow's pieces gets a short micro-plan (the first action, the next ones, what done looks like), written ahead and shown on its card, in Felix's messages and on its sheet.
- AI-16: The photo check is given the task's title, subject, instructions and the steps already done, says what it sees, and judges only the piece in question.
- AI-17: One photo check takes up to three pages; there is no partial credit.
- AI-18: The weekly review sees last week's review and what it changed, the day records, where in the day the work went (moves included) and the week by subject; its changes are kept with their old values, for the next review to read and the Reviews page to undo.
- AI-19: A Steps change replaces any task's steps but an Anki deck's (which are its cards): made on a task of the student's own, laid over a source's task, ticks kept by name; a split that doesn't add up to the task's minutes needs a new total with it.
- AI-20: Each schema field's description states what it's for and its limit, from the same values the checks use.
- AI-21: Felix remembers, on his own, facts (one per topic, the newer replacing the older) and up to 20 standing instructions on how he should behave, from the chat and from replies, each for as long as it's true: till a date or, a fact, with its task, then forgotten silently (D-103); each is shown as a "Remembered:" line with its Undo, saying how long it holds; the facts are kept within a budget of about 1,500 tokens, the oldest going first, and tidied weekly (Q-51).
- AI-22: Every message the app sends about the work is Felix's, a strict friend whose name and one-line description can be changed in Settings: his own written by Claude, the app's fixed lines in his name with no call (`byApp`, D-95); each answer is a short message, up to three quick replies, when he'll check again, and what to remember.
- AI-23: A photo of the work can be added to a task Claude reads (`Enrichments.jobFor`): the task is read again with it, the photo kept with the task (one each) and let go when the task goes, and the reading says it came from the photo.
- AI-24: A reply to Felix's notification is a reply in the one chat, as one in the chat is (D-98): to a question of his, answered with no call; else a turn of the chat, his answer his next message, in his notification while the chat isn't on screen; what he does is made at once, with its Undo.

## AR: architecture and testing

- AR-01: Golden copies of every system prompt and schema, made from fixed inputs, live in the test resources; any difference fails the tests with its diff until they are written again on purpose, the prompts' version raised where a reading's prompt changed.
- AR-02: A scored evaluation run asks Claude the private case set exactly as the app asks it, through the API with a key from the environment and a spend cap per run; never in CI, and its data is never committed.
- AR-03: Debug builds have a Debug page: recent calls, a task's prompt as it would be sent, the chat's context, Felix's state, the stores' sizes and ages, each source's last read and the watchdog's findings, each copyable.
- AR-04: Seeded property tests hold the planner's invariants for any input: capacity, deadlines, the weekly minimum touch, slots that don't overlap and sit inside windows, home work never at school, determinism and stability.
- AR-06: Crashes are written down on the phone as they happen (the last few kept), the last shown in Settings with Copy; nothing leaves the phone.
- AR-07: Every night everything the app keeps is saved as a dated JSON file in the phone's Documents/Decrastination folder, a second run that day replacing it and the newest fortnight kept; a backup holds what the app keeps (memories, Felix's state, today's slots and the chat included), never passwords or keys.
- AR-08: A sync that changes nothing does no more than it must: an Anki deck isn't asked again until its counts or options change, and slots already written to an unchanged calendar aren't written again.
- AR-09: There are no focus sessions: no Start, no session notification, alarm or credit, no box-length experiment; slots and their outcomes are the record.

## BL: blocking and free time

- BL-01: While today's work isn't done (pieces still to do today, or a slot missed today that went to tomorrow), blocking is strict; tomorrow's work doesn't gate it.
- BL-02: Blocking starts at the weekday block-from time on school days and at its own weekend time on weekends and days off school (PL-16); before then, and in sleep hours, nothing is blocked.
- BL-04: Where blocking meets the student (the block screen, the Day's now card, the widget, Felix's day-done message), one computed line says what finishing today earns: the pieces left, their minutes and the free time, naming those that need a photo.
- BL-07: A day with nothing planned and nothing overdue starts, once, with a free-time allowance (a setting), unless a slot of today's was kept, missed or moved.
- BL-08: The slot is the unit: a tick keeps any slot; a kept piece shorter than the photo threshold (20 minutes by default) earns its free time on the tick, a longer one only when its photo check passes (later the same day still counts); credit is capped at the day's planned minutes, and work ahead of today's plan earns nothing today.
- BL-09: The phone's rules at school don't change when a slot is planned there: school hours stay unblocked on school days.
- BL-10: Between today's slots, once the slot before a gap is kept, earned free time can be spent until the next slot starts.
- BL-11: A slot missed today that goes to tomorrow keeps today's work undone, and so blocking strict, until the day's hours end; a move the student makes lifts it.

## BUG: fixes from the first repository review

- BUG-P1-003: With Claude off, the rules keep an email that asks for something ("please complete", "return the form by…") as work, whoever sent it, never hiding it as an event or a notification.
- BUG-P2-002: A backup is checked whole before anything is restored, and one holding what the app never writes (negative minutes, a setting or multiplier out of its bounds) is refused whole.
- BUG-P2-004: A reading that gives blocks of work makes its task something to do whatever else it says, so dropping those blocks never hides both the task and its warning.
- BUG-P2-007: A source read that comes back incomplete (a deck's counts unreadable, the agenda's list missing, a row without its key) fails the read, never being taken as work finished.
- BUG-P2-010: An automatic Teams sync checks again when its countdown ends that it may still take the screen: in a call or locked, it's offered again in five minutes; in quiet hours or with automatic syncs switched off, not at all.
- BUG-P2-011: A photo check's piece and the pages taken so far survive the screen being made again while the camera is open.
- BUG-P2-012: At arming, a new authenticator's secret is saved only once a code from it checks out, so a turn of the phone can't leave a scanned code matching nothing.
- BUG-P2-013: Settings being edited and an instruction being written survive the screen being made again.
- BUG-P2-014: Saving Settings writes only the fields changed, over what is set then, so a change made elsewhere meanwhile stands.
- BUG-P2-015: Every job's input is held to one maximum size, so the dearest possible call bounds them all and a monthly cap is never passed by a call it let through.
- BUG-P2-018: The Anki day turns over at the right local time on the days the clocks change.
- BUG-P2-019: A slow Gmail read keeps what it has: conversations are fetched within a time budget, each whole or not at all, and the rest wait for the reads after.
- BUG-P2-020: A source read that overruns is cancelled and left to wind down, and no other read of that source starts until it has, so a stuck provider holds one read, not one a sync.
- BUG-P2-021: A Gmail connection that fails while being set up closes its socket.
- BUG-P2-022: An email part longer than one read takes is marked as cut short, and the model and the student are told; it is never taken as read whole.
- BUG-P2-023: Setup looks again at the app's permissions on coming back, so one granted in Android's settings shows at once.
- BUG-P2-027: A settings field that doesn't parse keeps its words and its error through scrolling and a turn of the phone, and Save works again once it's put right.
- BUG-P3-001: A reading is credited, and costed, to the model that actually answered, not the one asked for, as the API's fallback is on.

## DX: reading the sources

- DX-01: An email's attachments (name, type, size) are kept with its task, named to the model and shown on the sheet.
- DX-02: A calendar invite in an email gives its task the invite's exact time and place, the email being that event unless Claude reads work to do by a date the email gives; the sheet shows it with *Add to calendar*.
- DX-03: Only starred Gmail conversations are read: a starred message makes its whole conversation one task, every message read, starred or not, the student's own replies included; unstarring finishes it (the star is the state).
- DX-06: Quoted earlier messages and signatures are cut before the model sees a message, and the cut is said; a forward keeps what it forwards.
- DX-07: A Gmail sync fetches only what's new: a known conversation with nothing new is built from what's stored, and a full read follows when the folder's validity changes.
- DX-08: A Teams assignment's attached files and links (as the Teams widget gives them) are shown on its sheet, links opening in the browser, and named to the model.
- DX-09: Power Planner's timetable is read and kept from its last good read; the school days' study periods come from it, and its lessons shape the profile's school day.
- DX-10: Anki deck descriptions are read, so homework named by topic finds its deck (one whole-phrase match), and the model is given the decks by topic.
- DX-11: Seconds per card are learned per deck from the counts read around time in AnkiDroid (a median over recent stretches) and used for the decks' minutes in place of the usual figure.
- DX-12: A calendar event's location and description (cut short) are read: one saying it's optional or tentative is asked about, and its sheet says where it is and what it says.
- DX-13: The calendars read for busy time are chosen in Settings; the one written to is never read.
- DX-14: The calendar is read as far ahead as the plan reaches; questions about events keep to the week ahead, and an unanswered long event beyond it counts as a few hours of each of its days meanwhile.
- DX-15: Open tasks from two sources that look like the same work (same subject, due within a day, titles sharing most words) are offered as a pair; linked (a SameAs change), one is hidden and kept as the other's, and a pair said to differ isn't asked about again.

## G: what Felix can do in the chat

- G1: Felix keeps a piece done that has no step to tick, as Day's *Done* does, and takes one back (`keepPiece`, `unkeepPiece`; D-101).
- G2: Felix asks a source to read now, saying so (`sync`; D-101).
- G3: Felix opens a task where it lives: its sheet here, or its source's own page or app (`open` with value `app` or `source`).
- G5: Felix is told whether blocking applies, the pieces it waits on, and the free time left (after the plan's "Now:").
- G6: Felix asks for the week's numbers where a message needs them, rather than being sent them every time (`details` with `stats`: this week so far and last, `Stats.weekLines`; D-102).
- G7: Felix asks for the calendar past the fortnight (`details` with `calendar`: the four weeks after it, or a month; `ChatPrompts.calendarBlock`; D-102).
- G8: Felix is told each class's next three lessons, from Power Planner's timetable, looked for as far as the plan reaches (D-101).

## IMP: improvements from the first repository review

- IMP-P1-001: The plan and the block screen share one set of actions for a piece, with the same rules.
- IMP-P1-002: The open work the plan leaves out is listed, each with why (it can't start yet, waits for another task, opens past the plan's reach, or has no room), folded on the Work view.
- IMP-P1-003: A saved file that can't be read whole keeps every field that reads, never disarms protection, is set aside under its own name and shown in Setup until seen; saved secrets that can't be decrypted are told apart from none saved.
- IMP-P1-004: A private evaluation set of real items with their right answers lives only in the git-ignored `private/` folder; the harness that builds the prompts and scores the answers is committed, the data never.
- IMP-P1-005: A checked browser whose address bar can't be read ten times running is reported as a protection problem, with the fix: block that browser outright.
- IMP-P1-006: Arming lists anything in *What's in place* that isn't, and arms all the same.
- IMP-P1-007: The app and every widget size show when the plan is less than the whole picture (a source failing, Teams not synced for a day, protection off, Claude's key); where the warning strip doesn't fit, a red ! opens the app.
- IMP-P2-001: A task can be changed without Claude (not a task, can't start before, due on, minutes), kept as a quick change already read and applied at once.
- IMP-P2-009: Settings lists the blocked apps first, then a search over the other installed apps.
- IMP-P2-011: While earned free time is being spent, a silent notification counts it down, and a heads-up comes a minute before it runs out.
- IMP-P2-012: Arming again keeps the existing authenticator, checked with a code from it, unless a new one or none is asked for.
- IMP-P2-014: A photo is turned upright by its EXIF orientation before it's sent.
- IMP-P2-015: The weekly review's bounded changes (the safety margin, undated minutes a day, the free-time ratio, a school day's hours within an hour) apply at once, armed or not; any other change that loosens blocking still waits.
- IMP-P2-016: A class's own estimate multiplier is used only from its third timed piece, its kind's until then, and Stats says what each figure rests on.
- IMP-P2-019: Notifications go on separate channels by purpose, so a routine one can be silenced and a question kept.

## LR: Felix, learning, reviews and notifications

- LR-01: A Reviews page shows each week's note, its changes each with Undo, the check-in, the week's summary, and the memory tidy's before and after with Undo.
- LR-02: Felix asks the Sunday check-in in the chat, a question a message, each reply its answer (1 to 5 first, then what was put off, offered from the week's record); the form stays for answering all at once.
- LR-03: Felix speaks only around today's scheduled work (a slot's start with nothing done after a grace, its end, the photo ask, the day's last slot kept, the morning, a question), never in quiet hours or a busy event, at the pace he sets himself, with no daily ceiling; and the Sunday check-in at the time it's set for, out of quiet hours.
- LR-04: The morning briefing is Felix's message whole (up to 480 characters), with no list of the day under it; without his words, the day's list as his message in the app's words.
- LR-05: "How did it go?" is Felix's question: at a slot's end for its piece, and once in the evening for the day's work done outside the app's knowledge; with Claude off, a message of his in the app's words for each completion.
- LR-06: Stats says what the calibration has learned and what each figure rests on, and a kind's or class's figure can be forgotten, starting afresh from its kind's.
- LR-07: The app's routine notifications (a calendar move put back, the check-in's reminder, Claude's) go at most four a day and never in quiet hours, those held back going in the morning; Felix's and protection's sit outside that ceiling. Of Felix's messages, the week's review, its summary and a calendar event's question are in the chat at once and in his notification after quiet hours, the morning's briefing (at its own time, D-74) making it again.
- LR-08: Settings → Reset clears, one at a time or all together (*Everything learned*): the record and what was learned from it, what was learned only, what Felix remembers with the profile lines, the chat and Felix's history, and the changes in use; it needs no override code, saves a full copy to Documents/Decrastination first, and never touches the key, the sources or protection.
- LR-09: Slot outcomes are the learning signal: kept (with the minutes worked, more if it took longer), kept late, or missed; the multipliers learn from worked over estimated, the day record's done minutes are the kept slots', and each kind of day's yield is done over planned.

## NF: new features

- NF-01: A photo of homework (a sheet, or the board) is read into tasks of the student's own, each to check before it's kept; the reading is held by the app, so leaving the screen loses nothing.
- NF-02: After each weekly review Claude writes the week's summary about the student, in the third person (D-66), all of it in the email body with no attachments; Felix's message (its *Send it*) opens it in the phone's email app to the address set in Settings, nothing is sent by itself, and the text is on the Reviews page.
- NF-03: Time in AnkiDroid counts as work without touching this app: the decks' counts, read as AnkiDroid comes to the front and leaves it, tick a deck's pieces and keep their slots, the minutes being the time spent there.
- NF-06: Work handed back with feedback becomes a short Admin task to read it, Open going to the assignment, done by its tick as no source can say it's read; it waits on the Teams widget listing returned work.
- NF-07: The next seven days of slots, school windows and outside breaks included, are written to the one calendar picked in Settings, never read as busy time; a slot moved there by hand is taken as that piece's time where its deadline allows, an event added over a slot displaces it, and a deleted slot event comes back only if its time is still free.

## PL: the planner and its analysis

- PL-01: Overdue work and work due today or tomorrow go first; every other dated task is spread over its window (its first possible day to its last usable day, margin included) in proportion to each day's free time, at least a piece a week, revision a day apart.
- PL-02: When a task's real deadline can't be met by the free time before it, the app says so the day it becomes true: on the Day view, from Felix, and with *What gives?* into the chat.
- PL-04: A task can be pinned to a day (OnDay) or a piece to a time (AtTime), by hand or through Felix; a time that isn't free goes to the first free time after it, and the chat says where; a pin past a real deadline is refused, while overdue work may go at any time from now.
- PL-06: Each day's capacity is its free time times the learned yield for its kind of day (weekday evenings; weekends and days off; school windows): the median of done over planned on that kind's recent days with work, between a half and all of it, and all of it until enough such days exist.
- PL-07: Every piece records why it's on its day (due soon, overdue, spread, kept within a week, a day apart, the day with most room, undated, pinned, re-fitted), shown on its sheet and given to the chat with the plan.
- PL-08: Stats and the weekly review show each subject's kept minutes and pieces, its deadlines met, and its time against the estimates, with the timed pieces each figure rests on.
- PL-09: Stats shows when slots are kept and missed, by weekday and hour over the last eight weeks, with the working hours they suggest.
- PL-10: Each reviewed week's totals are kept as a rollup past the log's retention, so Stats can show longer periods such as a term.
- PL-11: The record can be exported as CSV files (what was finished, slots, days, blocks, Felix's messages, the calibration), in local time, to share.
- PL-12: Every piece of work gets a time: pieces (of a set length where a task has no steps of its own; one of the app's steps over an hour in even sittings of at most an hour, never a step the student or Felix gave) are laid as slots into the day's windows in its order, with short breaks between and a longer one after 90 minutes of work; weekends and days off get up to three 20-minute outside breaks, laid first and dropped one at a time, never shortened, when the work needs their time, none starting after 18:00; a slot on a real deadline's day keeps before it.
- PL-13: The plan adjusts live: a slot not kept by its end plus 10 minutes' grace is missed and re-fitted later today or on a later day, and other slots keep their times unless something now overlaps them (the stability rule).
- PL-14: Only a source's deadline (or an email's own quoted date) is a real deadline that can be overdue; dates the app or Claude set are plans that move when they pass and are never shown as overdue.
- PL-15: On school days the timetable's study periods, break and lunch (settings; the time spent eating excluded) are planning windows with no buffer, less calendar events, unused below 15 minutes free, and absent on a day off or after a half-day's end; only work that can be done anywhere goes in them, and a deadline later the same day is usable in the windows before it.
- PL-16: A school calendar of days off and half-days, told to Felix or added in Settings: a day off takes the weekend's hours, outside breaks and block-from time, and a half-day's school windows end, and its evening starts, at the early finish.

## Q: decisions from the first build's questions (no hyphen)

These come from the questions answered for v1 and 2.0.0; Q31 here is not Q-31 below. An id written with its answer's letter, as "(Q31: A)" or "(Q13: B)", is the 4.x plan's own question, not one of these: Q1 to Q31 in [plan_v4/ANSWERS.md](../plan_v4/ANSWERS.md), Q33 in D-11.

- Q6: Work is planned in set working hours, a school-day evening window and a longer weekend window (Settings); sleep hours are never blocked.
- Q6b: On school days blocking starts at a set time after school, leaving school hours alone; sleep hours are unchanged.
- Q7: The phone's calendar counts as busy time, judged event by event; an event the rules can't judge is asked about, taking all its time until it's answered, and the answer holds for every event of that name; the app writes only to the one calendar picked for its slots (NF-07).
- Q8: What's blocked: video and social apps; in browsers whose address bar is read, the same services' sites; the browsers listed as blocked outright, while blocking applies; calls, messages, school and study apps, maps, banking, authenticators and health apps never.
- Q12: Claude may split any task (an email, a planner item, an assignment) into blocks of work, each with its own dates where it has them and each planned in its own window.
- Q13: The morning briefing comes at set times (Settings), earlier on a school day, by the school calendar, than on any other day, a weekday off included (D-74).
- Q16: Written work can be shown done by a photo check, made by Claude when it's on.
- Q17: The override is a one-time code from an authenticator app (TOTP) on the override holder's phone, scanned at arming (which can be skipped, leaving only the wait); a code applies one waiting change at once; nothing is ever emailed, and a protection-off alert stays on the phone.
- Q18: A current-textbook setting says which textbook's deck a section number means; Claude may override it where an assignment names the book or a topic only one has, and the daily quota walks the current textbook first.
- Q19: WRITE_SECURE_SETTINGS, granted once over adb at setup, lets the armed watchdog switch the focus service back on and take it off the accessibility shortcuts itself.
- Q20: Protection is off until armed: unarmed, there's no settings guard or device admin, every change applies at once and the watchdog only warns; arming asks for the device admin and the override's authenticator.
- Q21: Teams syncs come on a tap, on the first unlock after a set evening time and every three hours after, only while the phone is in use and never in quiet hours; each automatic one first shows a 10-second banner with *Cancel* and *Delay 5 min*.
- Q22: Work earns free time at about one minute per three (a setting), and what's left goes at midnight.
- Q28: Tasks of the student's own are made in the app, with the form or through Felix; only they have *Done*, which earns no free time of itself (their kept slots earn as any do).
- Q29: One chat, Felix, for everything about the work (adding and changing tasks, days off, limits, questions answered from the plan as it stands), in place of an Instructions page.
- Q30: The task list (the Work view) is grouped by due date (Overdue, Today, Tomorrow, This week, Later, No date), each task's source a tag; a tap opens its sheet.
- Q31: The plan lists a task once a day, with its pieces that day under it, and the widget's list is a line a task.

## Q-: decisions from the review v2 questions (with a hyphen)

- Q-31: Felix stays quiet when nothing is scheduled, and when he names no next check he checks again after a default (20 minutes).
- Q-32: Spreading keeps a task's pieces at most a week apart over its window (the minimum touch).
- Q-44: The chat is Felix too, not only the notifications.
- Q-51: What Felix remembers has no hard cap but a budget, and a weekly tidy (once the facts with neither an until nor a task pass 70 % of the room the others leave them) rewrites those into fewer, current lines, shown with Undo, the others kept as they are; pruning is never the student's job.
- Q-52: A deadline later the same day is usable in the school windows before it (without windows, an afternoon or evening deadline's day), and the calibrated margins still apply.

## UI: the screens and the widget

- UI-01: Times are picked on a clock, and a stretch of the day as its two ends with its length shown, never typed.
- UI-02: Each day in the week strip shows its load, minutes planned against free time, as a bar, with a mark where work is behind.
- UI-03: A piece swiped one way is done (its slot kept), the other way moved later today, each with Undo.
- UI-04: A piece held and moved goes by hand to another time or another day of the next fortnight; there's no limit on moves, but none past its real deadline.
- UI-05: The widget shows the slot under way or next with its time and a ✓ that keeps it as the app's *Done* does, its badge and minutes, the *Then:* line, today's slots kept and the time left, and ↻; a tap opens the task.
- UI-06: Felix offers up to three quick replies under his last message; a tap sends one.
- UI-07: The chat takes speech: the words go into the box to check, not sent.
- UI-08: The Day view keeps kept slots in place, and shows what was done at its foot.
- UI-09: A task's sheet is in three parts: Do (its next piece done, its steps, the task where it lives, a photo), About (when it's due, how long, when it can start, what it says, its steps, when and why it's planned) and Change (later today, tomorrow, a day, not a task, Felix, and editing one of the student's own).
- UI-10: The Work view groups by due date or by subject and filters by source; the finished, the hidden and what the plan leaves out are folded at its foot.
- UI-11: There's no Calendar page: events live on the Day timeline, each with its sheet and answers, and Settings → Calendar picks the calendars read and the one written to.
- UI-12: The app is dark blue (navy on paper by day, near-black with navy by night), the widget and notifications alike; the launcher icon keeps the logo's pink, and overdue keeps the standard red.
- UI-13: Each setting shows one sentence, worked out from its value, on what it does to the plan.
- UI-15: The form for a task of the student's own edits its steps one by one (add, remove, rename, minutes, order, where each can be done), a renamed step keeping its tick.
- UI-16: Felix's chat button, Material's standard 56 dp, is on every screen, the block screen included, opening the chat about what that screen shows, and wearing the count of his messages unread.
- UI-17: Two screens for the work and one for the record: Day (when: a timeline of the chosen day's windows, events, slots and free time, under the week ahead) and Work (what: every open task), and Stats; a first-run hint says *Day is when, Work is what*.
- UI-18: The menu holds only the week's check-in, the Reviews and Settings, which gathers Setup, protection, the calendar and Reset; a mark opens Setup when something there needs the student.
