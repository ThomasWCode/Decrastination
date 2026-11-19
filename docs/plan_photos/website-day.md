# The website's Day: each item its own menu (6.6.0)

On the phone, a tap on Day opens what was tapped: a piece's sheet (`PieceSheet`, its buttons `PieceActions`), a missed slot's (`MissedSheet`, by `MissedActions`), an event's (`EventSheet`). On the website a click on a piece opens its task's sheet, and nothing else opens anything.

## What's wrong now

- **The wrong piece.** `Day.tsx` opens `TaskSheet` for the block's task, and `TaskViews` builds that sheet's *Done*, *Start* and *Later today* from the task's next piece (`TaskSheetModel.next`), not the block clicked. Click a task's second piece, on Thursday, and *Done* keeps Tuesday's.
- **Nothing to build a menu from.** `BlockView` has a title, its times and how it looks. A piece's key is sent only as its drag's; a recorded slot carries no task, key or piece; an event carries no key its answers are kept under, and none of what its sheet says of it.
- **The page may hold no rule** (D-137): it draws what the server sends, and sends back what the server gave it. So the page can't work a menu out; the server sends each block its own.
- **`DayViews.of` isn't given the tasks**, which a missed slot's rule (`MissedActions.of`) and a piece's buttons (its source, whether it's your own) need.

## What's built

**Each block carries what can be done with it**, as *Do now* and a task's sheet already do (`TaskAction`: a label, what it sends, what to ask first, its undo). `ServerGraph.day` hands `DayViews.of` the tasks; a block with anything to open says so, and what its sheet holds.

**A piece**: its label, its times, why it's on this day, its first steps where it has them, and

- *Done* (the block's own key, with its *Undo*), *Start* or *Stop*, *Later today*, *All of it done* for one of your own;
- *Ask Felix about it*;
- *Check with a photo* in 6.7.0 ([photo-checks.md](photo-checks.md));
- *Its due date, minutes…*, which opens the task's sheet as a click does now.

**A missed slot**: its label, "Missed, 15:00–15:40", *Done* (kept late), *All of it done*, *Ask Felix about it*, and the phone's two lines where they hold ("Done goes on its later miss"; "Done since, or planned again"), all by `MissedActions.of`, the rule the phone's sheet uses. The server's *Done* already refuses a key the plan no longer has.

**A calendar event**, an all-day one too: when, where, what it says, how the plan counts it, and its answers (*All of it*, *Half of it* or *A few hours*, *None*, as `CalendarTime.answers` gives them for that event).

- On the phone an answer is written to the phone's calendar store; the server can't keep one there. An answer on the website is kept as your quick change about the event, an instruction the phone pulls (D-153), as a reply to Felix's question on the website is. The same action, built by the server and sent with the block.
- Where an instruction of yours already says how it counts, the sheet says so, as the phone's does.
- *An instruction about it* and *Ask Felix about it*.

**A kept slot**: its sheet comes with 6.5.0's on the phone ([photo-checks.md](photo-checks.md)), and the website's with 6.7.0.

***Ask Felix about it*.** `SendMessage` already carries what a message is about (`About`: a task, an event, a day); the website's box never sets it. The chat page takes one from its address, shows it over the box as the phone's chip does, and sends it with the message.

**Not on the website**, as now, with no way to them from a server: *Open* (the task in its Android app), *Refresh Teams* (the Teams widget on the phone). *Check it's done*: README, *Still to answer* 1.

## How it fits the page

- Day's pointer handling is its own: a press lifts a block once it's moved a few pixels (`LIFT_PX`), or opens it if nothing moved. Events and recorded slots get the same press and the same keys (Enter, Space), and a button's role, without a drag.
- The website has one sheet, the task's, a panel at the right with no backdrop. The new sheets are the same panel, one open at a time, Escape closing it and the focus going back to the block.
- **While the server mirrors the phone**, nothing on Day can be pressed to change anything, as its tests hold: a sheet opens to read, its buttons absent.
- *Do now* and the task's sheet stay as they are; a task's sheet opened from Work is still about its next piece, which is what's meant there.

## Tests and docs

- `DayViewTest`: each kind of block's actions, a missed slot's by each of `MissedActions`' cases, an event's answers by its length, none while mirroring. Its "opens its task" and "opens no task" cases change to what's opened now.
- `TaskAskTest` and `ApiTest`: *Done* on a later piece keeps that piece; an event's answer from Day kept as an instruction.
- The website's end-to-end tests: a click on each kind, *Done* and its *Undo* on a second piece, a missed slot kept late, an event answered and the plan moving, *Ask Felix about it* reaching the chat with its chip; the drag tests unchanged. `writes.spec.ts`'s "a tap opens its sheet" names the piece's sheet.
- **Docs.** `spec/website.md` (Day; the chat's *about*), `spec/day.md` (which sheets the website has), `spec/felix.md` (a message about something, from the website).
