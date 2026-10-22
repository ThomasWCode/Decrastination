# Slots and the calendar

How each day's pieces get their times (`core/Slotter.kt`), how a slot is kept, missed or moved (`block/Focus.kt`, `block/SlotAlarms.kt`), and how the plan is written to your calendar (`calendar/CalendarWriter.kt`, through `CalendarProvider`: `calendar/SlotCalendar.kt` on the phone).
Also how your calendar's events become busy time for the planner (`sync/Sync.kt`'s `refreshCalendar`, `learn/CalendarTime.kt`, `learn/EventJudge.kt`).

## When the plan is made

`PlanWatcher` makes the plan again whenever the tasks, the settings, your instructions, the calendar's busy time, the school calendar, today's slot starts or the calibration change, once they've been quiet 2 seconds (PL-13), and when Felix's notes change, so a piece's first steps are written with them.
After each plan: today's slots are saved (`Planning.keepSlots`), the slot alarms are set (`SlotAlarms.arm`), your reminders it brings due or lets go are sent and their alarm set (`Looks.sweepReminders`, `SlotAlarms.armReminder`), the widget is drawn again, the pieces' first steps and Felix's next message are written ahead (`plan/Ahead.kt`), and the calendar is written once plans have been quiet 5 seconds. A slot's start or end alarm whose work fails is logged, not a crash, and his look is queued all the same (`SlotReceiver`).

## The slotter (PL-12)

`Slotter.slot` lays each day's pieces, in the day's order ([planner.md](planner.md)), into its windows (`DayBucket.slots`). A piece no free time holds whole is `unslotted`: still the day's work, with no time.

A day's free time is its windows less busy time (the calendar's and yours); today's starts from now, rounded up to five minutes. A school stretch under `minSchoolSlotMin` (15) is dropped, an evening one under 5.
A piece goes at the first free time it fits whole, where it may be done:
- work for home only in the evening, never in a school window (PL-15);
- a piece due that day at a real deadline only in a slot over by then;
- not before its task opens (a deck's next cards at 04:00), on five minutes;
- a step not yet begun after its task's steps before it that day (D-108): no earlier than the end of the last of them, and not at all while one of them has no time, a gap only it would fit going to other work. A pinned step is where you put it, and one under way stays where it is.

Breaks are kept around each piece within its window: `slotBreakMin` (5) before and after it, or after it `slotLongBreakMin` (15) once `slotLongBreakAfterMin` (90) of work has run without a long one. Past a window's edges there's nothing to keep apart.

What's laid, in turn:
1. Outside breaks, on a weekend or a day off school.
2. Pins: pieces at a time you gave (PL-04).
3. Today's starts kept from the last plan (PL-13): a step's only where it follows the steps before it; a task whose kept starts aren't in its steps' order (as a plan before D-108 could lay them) is laid afresh.
4. The rest, in order, each task's steps in theirs whatever the day's order between tasks: a step at its kept start where that's still free and follows the steps before it (one of them moved, a missed one say), else at the first free time after them.

## Outside breaks

On a weekend or a day off, `outsideBreaks` (3) breaks of `outsideBreakMin` (20) are laid first, one at or after each of `outsideTimes` (09:30, 13:00, 16:00), each over by the next one's time and none starting after 18:00 (`Slotter.OUTSIDE_LATEST_MIN`, D-41): one with no room by then isn't laid. On a day under way, one whose time has gone is laid from now while it can be over by the next one's time, and not at all once it can't.
When the day's work needs their time (with its breaks, as they'd be laid), the midday one is given up first, then the afternoon's, then the morning's; and fewer still while more of the work would go unslotted around them than with none.

## Pins (PL-04)

A piece at a time you gave is laid at that time, to the minute, where it's free and the piece may be done then (a window for its kind of work, over by its deadline, its task open); one under way stays.
Otherwise it goes at the next free start at or after its time, after the pins laid at theirs, so it never takes another pin's time. It's laid before today's kept starts: a piece the last plan had there is laid afresh. Once its time and grace have gone by with the piece undone, it's laid as any piece is.

## Kept starts (PL-13)

After each plan, today's work slots' starts are saved by key (`SlotState.previous`, in `SessionState.slots`). The next plan lays each piece there again where that's still free, one under way or in its grace included, so a replan doesn't shuffle the day. A piece kept today also stays today's when the plan chooses its day, dated or undated, and one under way even with less than its length left, so work with days to spare isn't moved to a day with more room once its slot has begun; it says so (`Why.Kept`) where keeping it changed its day.
A piece's key is its task and its step's place (`Chunk.key`: sittings counted from the end), so ticking a step or a sitting doesn't renumber the next and move it. A box goes by its part, counted over what's left: keeping one hands its key to the next box, laid afresh. A new day starts empty (`SlotState.on`).

## Grace, and missed slots (PL-13)

A slot just over stays where it is for `Slotter.GRACE_MS` (10 minutes), its piece still to tick.
- `SlotAlarms.arm` sets an alarm at the next slot's start, which makes the plan again, and one at the next slot's end plus its grace (`Slots.onEnd`).
- At the end, a piece still planned is missed: recorded once (`Focus.missSlot`, `SlotOutcome.Missed`), and its start no longer kept (`SlotState.missed`), so the plan moves it to the next free time and says so (`Why.Refitted`).
- Whether that took it to another day is noted (`MissedSlot.spilled`, by `SlotState.reconciled`): its task still in the plan and the piece in none of today's slots. A slot missed today whose piece is left with no slot today (gone to a later day, or out of the plan's room) keeps today blocked till the day's hours end (BL-11, [blocking-and-protection.md](blocking-and-protection.md)); one whose task the plan no longer holds (finished since, or deleted) went to no day, and isn't kept as having gone once it finishes.
- A slot whose alarm couldn't ring (the phone off) is found at the next plan (`Slots.sweep`).
- A time, a day or a start you then give the task is a move on purpose: the slots of today's it moves are recorded moved (`InstructionRules.movedSlots`: a time, its next piece's; another day, all of today's; a start, those before it; `Focus.moveSlot`, `SlotOutcome.Moved`), and its missed slots forgotten (`SlotState.moved`).

## The next thing to do

`Plan.next` is the slot under way, else today's next slot, else today's work with no slot (today's before tomorrow's), else tomorrow's first slot, else the first piece that can be started. The widget and the block screen show it.

## Keeping a slot (LR-09)

`Focus.keepSlot(key)` keeps a piece: from Day's tick, the widget's ✓, Felix, or AnkiDroid's cards counted as you leave it. A photo check keeps one through `Focus.photoChecked`, by the same keep.
- A step's piece ticks its step and adds its minutes to the task's worked minutes; a box, or a sitting before its step's last, adds its minutes only (`Chunk.ticks`). Minutes you say it took past its own are added too.
- A step's last sitting ticks the step, its worked minutes including the step's other sittings still in the plan (kept out of order).
- The slot's `SlotRecord` says how it went: `Kept`, or `KeptLate` once its grace is over or after a miss. A slot missed today and kept later is recorded as that slot, at its own time, and the miss gives way.
- A piece ticked ahead of its slot, or with none, is kept all the same; one of a later day's earns nothing today.
- A second tap keeps nothing: a tick on a piece kept since its screen's plan was made (`seenAt`: the Day screen's, the block screen's, a sheet's, the widget's and the plan Felix was shown, the plan's minute, not a time read as the screen's drawn again), or kept in the last 30 seconds whenever that was (`Focus.AGAIN_MS`), is that Done again; a photo taken within those 30 seconds of a Done earns that Done's slot. Two ticks at once keep one, under a lock.
- A Done that leaves all of a task's work done but its source's word (the task as the plan takes it, what's left rounding to none) says its last minutes stay planned until the source says it's done (`stillPlanned`), so the piece shown again isn't taken for a Done that didn't take.
- One of your own, or feedback done by its tick (NF-06), whose minutes a keep finishes is finished, as *Done* does.

What a kept slot earns, and when, is BL-08 ([blocking-and-protection.md](blocking-and-protection.md)); its record says whether it did (`SlotRecord.earned`).

## The Keeping journal

Each keep is saved as a `Keeping` (in `SessionState.keeping`) before anything changes. `Focus.finish` carries it through, each part once however often it runs: the task's tick and minutes, then the slot's record, then, in one write, its free time and its start let go.
A stop part-way is finished at the next start (`Focus.finishKeeps`), before an empty day is judged. A task that has changed meanwhile, or is done, lets it go. Photo checks have the same journal (`PhotoDone`, `Focus.finishPhotos`).

## Undo

`Focus.unkeepSlot` takes back a piece's last keep (Day's *Undo*; Felix's untick through `Focus.untickBlock`, his `unkeepPiece` and his keep's Undo), unless its task is finished, but for one of your own, or one done by its tick (NF-06), that the keep itself finished, which Felix's opens again:
- its record goes; one kept late becomes a miss again, on record and in today's misses;
- a step's key unticks its step, and its minutes come off the task; a sitting before its step's last, or a box, gives back its minutes only;
- kept in its slot today, its start is kept again, so the next plan lays it at that start again;
- its free time comes back as far as it's still unspent (`Credit.takeBack`).

## Moving a slot by hand

On Day, a slot swiped right to left is done. Left to right, it's later today (`Instructions.laterToday`, its task's next piece only): at the first free time after it with its minutes, around today's other slots, as an `AtTime` change.
Held, it goes to a day of the next fortnight, at a time or any time that day (`ui/MoveSheet.kt`): not a day before its task can start, nor one after a real deadline still to come, while overdue work can go to any (D-30). Each move is a change of yours, taken back in *Your changes*; one the plan wouldn't keep is refused first (`Planner.pinProblem`).

## Writing the calendar (NF-07)

With `WRITE_CALENDAR`, the plan's slots are written to the calendar you pick in Setup or Settings → Calendar (`Settings.calendarWriteId`); none is written to till you pick one.
Pick one you made at calendar.google.com: a calendar an app makes stays on the phone.
`CalendarWriter` writes it, built like `Focus`: its stores, the clock, and `CalendarProvider` for the calendar's events (`SlotCalendar` on the phone). It alone writes what the app knows of its writes (`calendar.json`: `CalendarState.calendarWritten`, `calendarWrittenTo`, `calendarsToClear` and its versions) and your answers about events, but for a backup restored, which merges its answers in (`Stores.replace`, as for every store); `CalendarWriterTest` drives it on temporary stores, against a calendar in memory.
- **What**: the next seven days' slots (today and the six after, `SlotCalendarDiff.DAYS`), pieces of work and outside breaks.
- **Each event**: the piece's label as its title ("Outside" for a break); its source and why as its description ("Teams. Due tomorrow."), with its first action where one's written (AI-15), or "A break outside.".
- **Marked**: free, with no reminders, and with the app's package and its URI (`CUSTOM_APP_PACKAGE`, `CUSTOM_APP_URI`: `decrastination://slot/<date>/<key>`).
- **Cheap**: two queries (its events, and those worded like them) and at most one batch a write (`CalendarWriter.writeSlots`), nothing written when nothing changed; slots as last written aren't even read back unless the calendar has changed after its last read (AR-08).
- **Once quiet**: written after plans have been quiet 5 seconds (`CalendarWriter.CALENDAR_WRITE_QUIET_MS`), so a burst of changes is one write, not an event changed again while the calendar's sync still has it.

`SlotCalendarDiff.diff` compares the slots with the app's events, those starting in the week and any with a slot's URI wherever it is now:
- a slot with no event is inserted, so one you deleted is written again;
- an event whose times or words differ is updated;
- an event no longer a slot, a second event of one URI, and copies (below) are deleted;
- a piece's event you moved is a hand move.

## Hand moves

A piece's event whose start is neither where the app last wrote it (`CalendarState.calendarWritten`) nor where the plan has it now is one you moved.
`SlotCalendarDiff.judge` takes moves earliest first, each as your time for its task's next piece (an `AtTime` change, "Moved in the calendar", made at once), unless:
- its task is no longer there;
- the time is at or after the task's real deadline, still to come ("it's due before then"; overdue work takes any time);
- another of its task's pieces moved in the same write (one at a time).

A move refused is written back, and a notification says why (`SlotCalendar.sayRefused`). A move taken counts as written only once its change is in (`SlotCalendarDiff.pending`).
Only the start counts: the slot keeps its piece's length. A break outside moved by hand is put back.

## Copies left by a calendar's sync

A calendar's sync, run while the app is changing an event it's still sending, can bring the event back as a new one without the app's package and URI. Once its slot moves on, such a copy is stale, drawn over the plan.
`SlotCalendarDiff.isCopy` finds one only by both:
- the app's words in its description (`SlotCalendarDiff.looksWritten`: a source's name first, or "A break outside."); and
- a title and times the app wrote to that calendar in an earlier write, as a fingerprint (`SlotCalendarDiff.version`: the calendar's id, title, start and end, hashed).

The fingerprints of what each write made are remembered once it's in, in `calendar.json` (`data/CalendarState.kt`), the last 2,000 (`SlotCalendarDiff.MAX_VERSIONS`).
A copy is matched only against earlier writes' fingerprints, never the write at hand's; a move taken isn't remembered, its event being at your time.
A version an event of yours already has there, before the app has ever written it, is never remembered (`SlotCalendarDiff.yours`), so a lookalike of yours that's there first is never taken for a copy.
An event of yours is deleted only if it has both the app's words and the very title and times of an earlier write to that calendar.

## A calendar no longer written to

Picking another calendar, or none, puts the old one on `CalendarState.calendarsToClear`. `CalendarWriter.clearOldCalendars` deletes the app's events there (by its package) and copies of them (by their fingerprints).
It's tried again at each write till it works (the permission back, the provider well). A backup restored keeps this phone's calendar choices, as their ids are this phone's.

## Your calendar as busy time

`Sync.refreshCalendar` reads the phone's calendar (`READ_CALENDAR`) from a day back to the end of the plan's last day, 90 days on (`CalendarTime.readUntil`, DX-14).
- Only the calendars ticked in Settings → Calendar (`Settings.calendarsExcluded`, DX-13), never the one written to; events you've declined are left out.
- Each event's location and description (its first 500 characters) are read too (DX-12).
- It's read at the app's start, when the calendar changes (once quiet 5 seconds) or the calendars picked do, after a sync that adds, finishes, reopens or misses a task, when an answer about an event or a change of yours is laid again, and at the morning briefing, among others (a time-zone change, the calendar permission granted in Setup, Settings' Calendar section); a failed read judges the last events read again, keeping from when to when that read ran, as Felix's calendar past the fortnight says ([felix.md](felix.md)).

`EventJudge.judge` judges each event:
- your answer for events of that name comes first (an instruction's over one given to Felix's question);
- marked free, in a holidays, birthdays, observances or week numbers calendar, or travel (a train, bus, coach, flight, plane, ferry, tube or metro): free;
- a timed event whose description says it's optional or tentative: asked about;
- all day, or four hours or more: asked about;
- anything else: busy, taking its time.

`EventJudge.time` gives the planner what each takes:
- busy: its time (`Planner.Input.busy`); all day, all of each of its days;
- a few hours: 180 minutes of its day at no set time (`dayLoads`; all day, each of its days; timed, its first);
- to ask, starting within the week: asked once, as Felix's message in the chat, its chips *All of it*, *A few hours* and *None* (in quiet hours it waits for the morning to be in his notification); it takes all its time till you answer (all day, all of each of its days), so the plan counts on no time it may not have (D-44);
- to ask, further off: a few hours of its day (of each, all day) meanwhile, so the spread before it leaves it room.

Your answer holds for every event of that name (`CalendarState.eventAnswers`, kept by `CalendarWriter.answer`); the plan, and today's record of it, are made again with it.
