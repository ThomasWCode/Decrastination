# Decrastination 6.3.0: an event's minutes taken from its own time

The plan for one release, written before it's built (6 Nov 2026), its questions answered on 7 Nov 2026. Nothing here is built yet; the specs say how the app works now.

## What's wrong now

You can tell the app, or Felix, that an event takes some of its time rather than all or none: a 2 h 20 min train that "will take half the event time" is kept as `load:70`. The plan then lays more than half the train with work: on the phone, 1 h 45 min of slots inside 2 h 21 min of train, with 70 minutes said to be taken.

Why:

- An answer of minutes (`EventJudge.Judgement.Load`) is "this much of its day, at no set time" (`EventJudge.kt`). `EventJudge.time` adds it to `dayLoads` for the day the event starts and adds nothing to `busy`, so none of the event's own time is taken.
- `Planner.capacityOf` takes `dayLoads` off the day's free time, the evening's first: the day is given 70 minutes less work.
- `Slotter` lays pieces into the free stretches `busy` leaves and never sees `dayLoads`, so to it the train is free time, and it fills it.
- So the two halves of the planner disagree: how much work the day gets counts the 70 minutes, and where the work goes doesn't. "At no set time" was right for what it was made for, an all-day event (a van hire that takes a few hours of the day, somewhere); for a timed event it loses where the event is.
- The words say "of its day": the event's sheet ("1 h 10 min of the day"), *Your changes* ("takes 1 h 10 min of its day") and Felix's list of changes (`EventTime`'s "hours": "that many minutes of its day, at no set time"). Felix had no way to say "of its own time": he worked out half the train and gave it as minutes of the day.

## The change

A timed event's minutes come out of its own time: `EventJudge.time` lays them as busy time at its ends, half at its start and the rest at its end (`load:70` on a 15:00 to 17:20 train: busy 15:00 to 15:35 and 16:45 to 17:20, the 70 minutes between free to work in). Minutes as long as the event take all of it, as *busy* does; minutes longer than it take all of it and the rest as busy time straight after it (`load:180` on a 19:00 to 21:00 concert: busy 19:00 to 22:00). An all-day event's minutes stay as they are, at no set time on each of its days.

As it's busy time, everything downstream agrees with no change of its own: `capacityOf`, `freeIn`, the slotter, Day's free time, the week strip's bars and the plan Felix is told. One that runs past midnight takes its end's half, or the time after it, from the next day, as busy time does.

- `EventJudge.time`: a timed event's `Load` becomes one or two `Busy` blocks inside it, or one from its start to past its end by what's over; `dayLoads` is left to all-day events. Its comment and `Judgement.Load`'s say so.
- The words: the event's sheet "1 h 10 min of its time" (`DayScreen.verdict`); *Your changes* "takes 1 h 10 min of its time" (`InstructionRules.describe`, which doesn't know whether the event is timed, so the words fit both); `EventTime`'s "hours" in `InstructionPrompts.CHANGE_TYPES`: "that many minutes of its time: a timed event's from within it, at its start and end, and what's more than it straight after it; an all-day event's, of its day, at no set time".
- The middle answer on a timed event under four hours is *Half of it*, on its sheet and in Felix's question alike (Q149); *A few hours* stays on all-day events and timed ones of four hours or more.
- No store changes shape and no answer is written differently: `load:<minutes>` means what it did for an all-day event and the above for a timed one. No migration, no new answer for the backup's checks (`Backup.answer`), the server or the website to learn.
- The answers already kept: on the phone, the timed events with minutes as their answer are all over but the one that showed this, so nothing that's to come is counted differently but it.

## The owner's answers (7 Nov 2026)

1. **Change what minutes mean for a timed event, or add a fourth answer beside them?** Recommended: change them, as above. Minutes at no set time on a timed event leave the plan free to fill the event, which is never what's meant; a fourth answer ("of its own time") would keep that and add a stored form the phone, the server, the website, the backup's checks and Felix's schema all have to learn. **Answer: change them.**
2. **Where in the event the minutes go.** Recommended: half at its start, the rest at its end: on a train or a flight the time that's lost is getting on and settling, and packing up before getting off, and the middle is the one stretch long enough to work in. Offered too: all at its start; anywhere in it, the slotter keeping no more than the rest of it for work (a budget per event the slotter and `capacityOf` would both have to keep, much more code and its own tests, for a choice that matters little). **Answer: half at its start, the rest at its end.**
3. **Minutes longer than the event** ("the concert takes 3 hours of my evening", for a 2-hour concert, the journey there and back counted). Recommended: all of it, and no more; the journey is a busy time of yours (`BusyTime`) where it matters. Offered too: the event's time, and what's over at no set time on its day, as now. **Answer: all of it, and what's over as busy time straight after it, in the plan alone** (nothing written to the calendar): what was said is kept, and where it goes is known.
4. **The event's sheet's buttons.** *All of it*, *A few hours* and *None*: on a timed event under three hours *A few hours* (180 minutes) now takes all of it, the same as *All of it*. Recommended: on a timed event under four hours, the middle button is *Half of it*, kept as half the length of the event tapped, for every event of that name (`CalendarTime.ANSWERS` gains it, the sheet picks by the event); *A few hours* stays on all-day events and timed ones of four hours or more. Offered too: the three buttons as now, minutes other than those told to Felix. The question first said Felix's question could keep its three chips, as he asks only about events of four hours or more; he doesn't: he asks about a timed event marked optional or tentative whatever its length (`EventJudge.judge`), with the same answers. **Answer: *Half of it* on a timed event under four hours, on its sheet and in Felix's question alike.** As it's kept as minutes, another event of that name and a different length takes the same minutes.

These go into `docs/plan_v4/answers.md` as Q146 to Q149, and the decision into `docs/decisions.md` as D-151, when the release is built.

## Decided without asking

1. **Odd minutes**: the start's half is rounded down, the end's takes the rest (70 on a 141-minute train: 35 and 35; 71: 35 and 36).
2. **No new call to Claude, and no live check of the prompt.** Its words change but its schema doesn't (`eventTime` keeps `free`, `busy` and `hours`), so there's nothing new for the API to compile, and Felix already gives the minutes right: it's the planner that used them wrong.
3. **A minor version**, as it changes how the plan counts your answers.
4. ***Half of it* on an odd length** is rounded down (70 on a 141-minute train).
5. **A reply of *half of it* to Felix's question.** `Replies.route` turns a chip back into its answer from `CalendarTime.ANSWERS` alone, and the question's message knows the event by its name and start (`About`), not its length. So the answer is worked out from the event as last read (`EventJudge.Time.events`), found by its name and start, by the same `CalendarTime` answers the question was asked with; one no longer in the calendar can't be, and the reply goes to Felix as words. No message changes shape, so the chat's store needs no migration.

## The release

- Branch `v6.3.0` from `main`; `versionCode` 127, `versionName` "6.3.0".
- Commits, each building and passing its tests:
  1. `EventJudge.time`: a timed event's minutes as busy time at its ends; its tests in `LearnTest` (a timed load at its ends; odd minutes; minutes as long as the event as all of it; longer, all of it and what's over straight after it; one across midnight, at its end and after it; an all-day load unchanged; one asked about further off, a few hours, inside it once timed) and one in `PlannerTest`: a 141-minute event with `load:70` holds no more than 71 minutes of work, and the day's capacity is what the slotter lays.
  2. The words: `DayScreen.verdict`, `InstructionRules.describe` (`InstructionRulesTest`'s "takes 2 h of its day" changes with it), and `CHANGE_TYPES`, with its goldens (`chat.txt`, `chat-gmail-off.txt`, `instruction.txt`) and `felix-inputs.md` written again by `FelixInputsDocTest`. `ReadingPrompts.VERSION` stays: no reading's prompt changes.
  3. *Half of it*: `CalendarTime`'s answers by the event, on the event's sheet (`DayScreen`) and as Felix's chips (`Sync.ask`, `FelixWords.eventReplies`), and a reply of *half of it* worked out from the event (`Replies.route`); their tests in `LearnTest`, `ConversationTest` and `RepliesTest`.
  4. The specs: `slots-and-calendar.md` §Your calendar as busy time (what `EventJudge.time` gives), `planner.md` (time at no set time is an all-day event's alone).
  5. The version, the answers in `answers.md`, D-151 in `decisions.md`, and this folder deleted.
- `PlanSnapshotTest` gives the planner its busy time and day loads ready-made, not through `EventJudge.time`, so the snapshot doesn't change; if it does, the diff goes in the PR and is explained.
- Checks while building: `./gradlew compileDebugUnitTestKotlin`, then `./gradlew :engine:test --tests '*LearnTest*' --tests '*PlannerTest*' --tests '*InstructionRulesTest*' --tests '*ConversationTest*' --tests '*RepliesTest*' --tests '*FelixInputsDocTest*' --tests '*GoldenPromptsTest*'`; before the push, CI's command once: `./gradlew testDebugUnitTest lintDebug assembleDebug :server:buildFatJar`.
- The merge deploys the server as well, which plans with the same `EventJudge`: the phone and the box count an event the same once both run it. The phone gets the build before the merge.
- `CLAUDE.md`'s *Where things are said* names this folder while it's in use.

## At the phone

What the tests can't see, in order (the install checks first):

1. A timed event that's to come, answered with minutes (or one made for the check, told to Felix "it takes half of it"): on Day, the pieces inside it sit in its middle, none in its first or last quarter, and the day's free time is what's left.
2. Its sheet: "The plan counts it: … of its time".
3. *Your changes* in the chat: the instruction reads "takes … of its time".
4. A timed event under four hours shows *All of it*, *Half of it* and *None*; *Half of it* leaves its middle half free on Day.
5. A timed event told to take more than its length (one made for the check): on Day, no piece in it or in the time over just after it.
6. A short timed event marked tentative, made for the check: Felix's question offers *all of it*, *half of it* and *none*; replying *half of it* leaves its middle half free on Day.
7. An all-day event with an answer of minutes (or *A few hours*): the plan as before, its minutes off the day, no piece moved.
8. Anything made for the check deleted, and its instruction and answers taken back.
