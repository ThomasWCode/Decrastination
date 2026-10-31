# Day's menus: a missed slot's sheet, and Ask Felix about it on each

## A missed slot's sheet

Today a missed slot on Day (outlined and worded in the error colour, "Missed") does nothing when tapped (`RecordBlock` in `ui/DayGrid.kt`; *none* in `docs/spec/day.md`'s table). Day only shows today and the six days after, so the missed slots it shows are today's.

### What it opens

A tap on a missed slot opens its sheet (a `ModalBottomSheet`, as every other tap on Day does), `MissedSheet` in `ui/DayScreen.kt` beside `EventSheet`:

- its label, and "Missed, 16:00–16:45";
- **Done**: keeps it, late. `Focus.keepSlot` already does this for a piece missed today and still in the plan under the same key: the record is *Kept late*, in the missed slot's place and times, and the miss gives way (`Focus.keep`, LR-09); its free time is earned as a Done on its piece planned again would (BL-08's limits). It's Day's own Done (`DayActions.onDone`), so the sheet closes and Day's bar says it with an Undo, which makes it *Missed* again (`Focus.unkeep`). Shown only while its piece is still in the plan;
- **Open**: the task in its source (`TaskOpener.open`), as *Open* on a piece's sheet;
- **Check it's done**: asks its source (a sync of that source alone), and says whether it agrees, as on a piece's sheet; for one of your own, *All of it done* in its place, as on a piece's sheet. Shown while its task is open;
- **Ask Felix about it** (below). Shown while its task is open.

Where its task is done since, or its piece is no longer in the plan (ticked in the chat, changed), the sheet says "Done since, or planned again" and has only *Open*.

Each says how it went in Day's bar (the snackbar), as a record has no line of its own for a message.

### How it's built

- `DayModel.Row.Recorded` gains `piece: Chunk?`: the plan's piece with the record's key (`plan.ordered`), where the record is *Missed*; null for every other record. `DayModel.from` sets it, so it's tested with the rest of the model.
- `DayActions` gains `onMissed: (SlotRecord) -> Unit`; `RecordBlock` takes `actions` and is clickable where the record is *Missed*. For TalkBack it's read as now and offers *Done* as a custom action where its piece is in the plan, as a piece's block offers its own.
- `DayScreen` keeps the record tapped (`var missedFor by rememberSaveable`, by the record's key and start), and shows `MissedSheet` while that record is still on the day.
- Which buttons show is a pure rule, `MissedActions.of(record, piece, task)`, in `ui/` beside `DayModel`, so it's tested without a screen.
- *Check it's done* is moved first out of `PieceActions` into a function both use (`checkDone(graph, task, say)` in `ui/PieceActions.kt`), in a commit of its own that changes nothing.
- Nothing changes in `Focus`, the stores or the plan's rules.

A task done in boxes of time has its next box under the same key, so *Done* on its missed slot keeps the box now due, as a Done on that box planned again does.

## Ask Felix about it on every Day sheet

The Work tab's task sheet has *Ask Felix about it* (`TaskSheet.kt`, `onInstruct`): it opens the chat about the task (`AskActivity.intent(activity, About(taskId, taskTitle))`), sends nothing, and wears his unread count, as his button does elsewhere (UI-16). It's shown while the task's open.

- First it's moved, unchanged, into `AskFelixButton(activity, about)` in `ui/FelixButton.kt`, beside `FelixFab`: the label "Ask <his name> about it", the badge, the chat opened about `about`. `TaskSheet` uses it, with its own rule for when it shows.
- Then it goes on Day's sheets:

| Sheet | About | Shown |
|---|---|---|
| A piece's (`PieceSheet`) | its task (`About(taskId, taskTitle)`) | always (a piece's task is open) |
| A missed slot's (`MissedSheet`, above) | its task | while the task's open |
| An event's (`EventSheet`), all-day ones in the header too | the event (`About(eventKey, eventTitle, eventStart)`, as *An instruction about it* has) | always |
| The day's *Instructions* (`DaySheet.Said`) | the day (`About(day)`, as *An instruction about this day…* has) | always |

- Not on the list sheets (*Won't fit* has *What gives?*, which asks him already; *No time*'s rows open a piece's sheet, which has it; *Done* lists records), nor on the DO NOW card or the block screen, which use `PieceActions` but aren't Day's sheets, as you answered. It goes on `PieceSheet` itself, not into `PieceActions`, so those two are left as they are.
- `PieceSheet` is opened from Day alone, so the chat it opens is the app's, never the block screen's (`back` stays false).

## Tests

- `DayModelTest`: a missed record whose piece is still in the plan carries it; a kept one, and a missed one whose piece has left the plan, carry none.
- `MissedActionsTest` (new, `app/src/test/java/com/thomaswcode/decrastination/ui/`): Done only with a piece; *Check it's done* for a synced source's open task, *All of it done* for one of your own, neither for one done; *Ask Felix about it* only for an open task; *Open* where the task is still there.
- Done's keeping late and its Undo are `Focus`'s, held already by `block/FocusTest.kt`; a test is added there only if building shows a case it doesn't hold (a slot missed and its piece moved to another day, kept from its missed slot).

## Spec

`docs/spec/day.md`: the table's *A slot missed* row (Tap: its sheet, with what it holds; Swipe and Hold: none), a paragraph on the missed slot's sheet, and *Ask Felix about it* on the piece's, event's and *Instructions* sheets; UI-16's line in `docs/spec/requirement-ids.md` if it's taken to cover the sheets' buttons too.
