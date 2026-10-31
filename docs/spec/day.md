# The Day

The Day tab (UI-17, D-118), opened from the chat's Plan button (D-121, [felix.md](felix.md) §The app's home): the week ahead in a strip, today's DO NOW card, the chosen day's header, and the day as a calendar, as Google Calendar's Day view draws one. `ui/DayScreen.kt` puts it together; `ui/DayModel.kt` gives the chosen day as it's shown (pure); `ui/MissedActions.kt` says what a missed slot's sheet offers (pure); `ui/DayLayout.kt` says where things go on the grid (pure); `ui/DayGrid.kt` draws the grid and its blocks; `ui/DayDrag.kt` holds a drag; `ui/Moves.kt` makes a move by hand, as the move sheet (`ui/MoveSheet.kt`) and the drag both make it. What the plan leaves out, and the sources, are on Work.

## Top to bottom

- **The first-run hints**: the Day's own, and "Hold a piece to move it: drag it to a time, or onto a day above.", each shown till put away (`Hint`).
- **The week strip** (UI-02): seven days from today, each with its load as a bar and a red dot where work is behind; a tap picks the day.
- **Today's DO NOW card** (`NextCard`), on today only. Unfolded, it shows in full, up to the whole Day below the strip (Q96), scrolling within that only where it holds more; the day's header and the grid go on below it, under the screen's edge where it fills the screen. It folds into one line ("DO NOW · Essay: plan") as it's dragged up (what it holds beyond its room scrolling to its end first), a drag on it stopping at its line, or as the day's header is dragged up. It unfolds as the day's header is dragged down, wherever the grid's scrolled, at a tap on the line, or, while it's only part-folded, as it's dragged down. Let go part-folded, it never stays between: the card and the day below it are two screens it snaps between (4.9.1, D-122, `DayLayout.settled`). A flick of 125 dp a second or more goes its way, so a small flick up folds it to its line and a small one down on the header unfolds it; a slower drag goes on the way it moved once it's moved 24 dp, and back where it started if less. The grid's own scrolls never fold it or bring it back (your word, replacing Q97). The Day opens with it folded, its calendar at the top, upright as in landscape (4.9.1, D-122); folded as it opens, it's measured once out of sight, so the header dragged down unfolds it under the finger. Where there's no room even for its line, and on a phone on its side however tall its screen (Q92), it stays a line, and a tap on the line opens the piece's sheet, with the card's actions, instead of unfolding it.
- **The day's header**: its weekday over its date in the gutter's width (today's date in a filled circle of the primary colour); its name and load (`Format.load`); its all-day events as chips in their calendar's colours, each opening its event sheet; and chips for what won't fit (today's, PL-02, in the error colour), the pieces with no time, what's done (UI-08) and its instructions (UI-11, "Instruction…" where there's none), each opening a sheet that holds what the Day's list held under its rows. "Nothing planned." where the day has no slot, record or piece. On today, upright, it's the card's handle: dragged up it folds the card, and dragged down it pushes the hours down, bringing the card back, wherever they're scrolled; let go part-folded, the card settles folded or unfolded (D-122).
- **The grid**, the rest of the screen (none while the card fills it), scrolling on its own; upright, its scrolls move only its hours, never the card. On a phone on its side there's no rest under what's above it, so the Day scrolls as one, the grid a screen tall (Q94): as the grid scrolls on to later hours, what's above it goes first, and as the grid scrolls back it slides back in first (Q95). A piece held at the grid's top in a drag scrolls the grid to earlier hours, and once that's at midnight brings what's above back too, the strip with it, so a day can be dropped on.

## The grid

- 00:00 to 24:00 down a gutter of hours, as the app writes times ("15:00"), every hour but midnight's, a thin line across at each. The day's windows are bands behind the blocks, school's tinted, each named at its top ("Study period", "Break", "Lunch", "Your time"); the hours outside every window are shaded.
- Each slot, record, timed event and busy time of yours is a **block** from its start, as tall as it lasts but never under 24 dp. Blocks at the same time stand side by side, each in the first column free at its start and widened right over the columns free for all its time (`DayLayout.columns`); a block under 40 dp wide shows its first word only.
- The grid is the day's wall clock (`DayLayout.minuteOf`): on the day the clocks go back, the hour repeated is drawn once, two things in it side by side; on the day they go forward, the missing hour is empty. An event across midnight is drawn on each day it covers, from 00:00 or to 24:00.
- Today's moment is a line across in the primary colour with a dot at the gutter's edge, moved each minute.
- It opens, on today, an hour before now; on another day, half an hour before its first window or block (`DayModel.opensAt`); picking a day scrolls so again.
- Two fingers pinch it between 40 and 120 dp an hour (60 at first), about the point between them; the zoom is kept in the settings (`Settings.dayHourDp`), saved as the pinch ends, so it's the same next time. One finger only scrolls.
- The free time between slots is the empty grid.

## The blocks

| Block | How it looks | Tap | Swipe (UI-03) | Hold |
|---|---|---|---|---|
| A piece ahead | `primaryContainer`: its label, its times, then as its height allows its reason (`Chunk.whyLine`), its first step ("First: …", AI-15) and the last thing said about its task; urgent, a red edge on its left; a ✓ where it's at least 32 dp tall and 120 dp wide | its piece's sheet (`PieceSheet`) | right to left: Done, with Undo; left to right: later today (today's only) | drag |
| The piece under way | the same in `primary`, its times followed by " · now" | as above | as above | as above |
| A piece gone by, not yet moved | faded, " · gone by" | as above | as above | as above |
| A break outside | a pale strip, "Outside" | none | none | drag within its day, not under way |
| A slot kept, or kept late | grey, its label struck through, its outcome | none | none | none |
| A slot missed | outlined and worded in the error colour, "Missed" | its sheet (`MissedSheet`, below) | none | none |
| An event | its colour (the event's own, else its calendar's, `CalendarEvent.colour`); its words black or white, whichever reads better on it (`Colours.onEvent`); outlined only where the plan counts it free; a small "?" where the plan needs your answer about it, not asked yet | its event sheet (`EventSheet`) | none | none |
| A busy time of yours (D-117) | outlined, "Busy: Football", or "Busy" | none | none | none |

For TalkBack, each piece's block says its times and label, and offers *Done*, *Later today* and *Move it* (the move sheet), so nothing needs the drag or a swipe; a break outside is moved without the drag by asking Felix (D-116). The DO NOW card offers *Fold it* on its first line, so the grid can be reached while the card fills the Day and leaves it no room; folded, its line unfolds it at a double tap. A missed slot's block offers *Done* too, where its sheet has it.

### A missed slot's sheet

A tap on a missed slot opens its sheet: its label, "Missed, 16:00–16:45", and what can be done about it, which `MissedActions.of` says.

- **Done** keeps it, late (`Focus.keepSlot`, LR-09): the record is *Kept late*, in the missed slot's place and times, and the miss gives way; its free time is earned as a Done on its piece planned again would (BL-08's limits). It's Day's own Done, so the sheet closes and Day's bar says it with an Undo, which makes it *Missed* again. It's there while its piece is in the plan, and only on the last miss of its key today, as that's the miss a keep gives way (`Focus.keep`): a task in boxes of time has one key for every box, so with two of its boxes missed today, the earlier's sheet says "Done goes on its later miss" in its place, and has *Done* once the later's kept. Tapped on a Day drawn before the later was kept, it's refused as changed since, and taken once Day's drawn again, within the minute.
- **Open**: the task in its source (`TaskOpener.open`), as on a piece's sheet.
- **Check it's done** asks its source (a sync of that source alone) and says whether it agrees (`checkDone`); for one of your own, **All of it done** in its place, as on a piece's sheet.
- **Ask Felix about it** (below).

Where its task is done since, or its piece is no longer in the plan (ticked in the chat, changed), the sheet says "Done since, or planned again" and has only *Open*. Each closes the sheet and says how it went in Day's bar, as a record has no line of its own for a message.

### Ask Felix about it

*Ask Felix about it* (`AskFelixButton`, UI-16) is on Day's sheets for one task, event or day, as it is on the Work tab's task sheet: it opens the chat about that, sends nothing, and wears his unread count.

| Sheet | About | Shown |
|---|---|---|
| A piece's (`PieceSheet`) | its task | always |
| A missed slot's (`MissedSheet`) | its task | while its piece is in the plan and its task open |
| An event's (`EventSheet`), all-day ones too | the event, as *An instruction about it* has | always |
| The day's *Instructions* | the day, as *An instruction about this day…* has | always |

It isn't on the list sheets (*Won't fit* has *What gives?*, *No time*'s rows open a piece's sheet, *Done* lists records), nor on the DO NOW card or the block screen, which aren't Day's sheets.

## Moving a piece by hand (UI-04)

- **Hold a piece**: it lifts, with a tick of haptics, and follows the finger over the whole Day. A ghost on the grid marks where it would go, in five-minute steps, with its times ("17:15–17:40"); near the grid's top or bottom edge, the grid scrolls.
- **Let go on the grid**: its task's next piece (PL-04) takes that time on that day, as the move sheet's time does (`Moves.toTime`): an `AtTime` change of yours, "At Thu 29 17:15", undone in the chat's *Your changes*, kept to the minute (D-114). A piece dropped where it would run past midnight starts so it's over by it (`DayLayout.dropStart`).
- **Refused**, it springs back and the snackbar says why, in the move sheet's words: the time's gone; it can't be started till then; it's due before its piece would be over (a real deadline still to come; overdue work goes anywhere from now, D-30).
- **A later piece of its task** can't take a time (PL-04): let go on the grid, it springs back, "Only its next piece takes a time: drag it onto a day above".
- **Let go on a day of the strip**: all of its task on that day (`Moves.toDay`, an `OnDay`), as the move sheet's *All of it, any time that day*, with its check (a time you gave in the chat holding it on that day is taken back there first). While a piece is dragged, the day under the finger is outlined and the days it can't go to are dimmed, as the move sheet's chips are (`Moves.dayAllowed`); let go on one, it springs back and says why; on its own day, nothing changes.
- **A break outside** drags up and down its own day only (`Moves.outsideTo`): let go on the grid, it takes that time, an `OutsideAt` change of yours for that date ("Outside at Sat 31 14:30", D-116), refused where its time's gone, it's begun, it would run past midnight or it would be over another of the day's breaks; let go on the strip, it springs back, "A break outside stays on its day"; under way, it doesn't lift.
- **Let go anywhere else**, nothing changes. The drop is judged on the plan as it then is, by the piece's key: one no longer there is "That piece has changed since (done, or planned again).". A drag under way isn't kept: rotated, or the process killed, it's dropped; the day chosen and the zoom are kept.
- **A sideways movement before the hold's time is a swipe**; a hold, then a movement, is a drag; an upward or downward movement before it is the grid's scroll.
- **The move sheet stays**: TalkBack's *Move it* opens it, for the fortnight and a time by the clock; the task sheet's *Change* keeps its days (UI-09). Events and records don't move: events are the calendar's own, moved in Google Calendar; records are what happened.
