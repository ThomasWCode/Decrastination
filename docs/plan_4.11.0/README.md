# Decrastination 4.11.0: the chat keeps your words, and Day's menus do more

The plan for one release, written before it's built (31 Oct 2026). Nothing here is built yet; the specs say how the app works now.

- [chat-draft.md](chat-draft.md): what's typed in the chat's box is kept when you go to the plan and come back.
- [day-menus.md](day-menus.md): a missed slot on Day opens a sheet with *Done*, *Open*, *Check it's done* and *Ask Felix about it*; *Ask Felix about it* is on every sheet Day opens.

## What you've answered

- The kept draft doesn't outlive the app's process: it's held in memory, not written to a file (no store, no migration).
- A missed slot's sheet holds *Done*, *Open*, *Check it's done* and *Ask Felix about it*.
- *Ask Felix about it*, as the Work tab's task sheet has it, goes on all of Day's sheets.

These go into `docs/plan_v4/answers.md` as Q127 and Q128 when the release is built.

## Still to answer

1. **Words handed in to the box** ([chat-draft.md §Words handed in](chat-draft.md#words-handed-in)): when the chat opens with words put in its box for you (*Split with Felix*, *What gives?*) while it still holds words you typed earlier, which goes where? Recommended: both kept, the handed words first.
2. **The block screen's chat**: it shares the kept draft with the app's chat, as one holder for the app does. Recommended: yes.
3. **Open** on a missed slot's sheet: the task in its source (Teams, Gmail…), as *Open* on a piece's sheet does, or the piece's own sheet? Recommended: in its source, as everywhere else that says *Open*.
4. **One of your own tasks**: a piece's sheet has *All of it done* in place of *Check it's done*, as there's no source to ask. Recommended: the missed slot's sheet does the same.
5. **Where on Day *Ask Felix about it* goes** ([day-menus.md §Ask Felix about it](day-menus.md#ask-felix-about-it-on-every-day-sheet)): the piece's, the missed slot's, the event's and the day's *Instructions* sheets. Not the lists (*Won't fit* has *What gives?*; *No time*'s rows open the piece's sheet; *Done* is a record), nor the DO NOW card or the block screen, which share the piece's buttons but aren't Day's sheets. Recommended: as listed.

## The release

- Branch `v4.11.0` from `main`; `versionCode` 97, `versionName` "4.11.0". A minor version, as it adds to what Day's sheets do.
- Commits, each building and passing its tests:
  1. *Ask Felix about it* moved out of `TaskSheet.kt` into a shared `AskFelixButton` (a move, nothing changed).
  2. *Check it's done* moved out of `PieceActions` into a function of its own (a move, nothing changed).
  3. The chat's kept draft, its spec and its tests.
  4. The missed slot's sheet, its spec and its tests.
  5. *Ask Felix about it* on Day's sheets, its spec.
  6. The version, and the answers in `answers.md`.
- No new library, store, migration, prompt or golden, and no new call to Claude: *Ask Felix about it* opens the chat and sends nothing.
- Checks while building: `./gradlew :app:testDebugUnitTest --tests '*ChatScreenStateTest' --tests '*DayModelTest' --tests '*MissedActionsTest'`; before the push, CI's command once: `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- `CLAUDE.md`'s *Where things are said* names this folder while it's in use; it's deleted once what it describes is built and in the specs, as `plan_v4`'s design documents were.

## At the phone

What the tests can't see, in order (the scripts' install checks, `scripts/phone-check.sh`, first):

1. Type in the chat, tap *Plan*, then Back: the words are in the box.
2. From Day opened by a notification, open the chat with Felix's button, type, tap *Plan*, then Back: the words are in the box.
3. Type in the chat, tap *Plan*, then Felix's button on Day: the words are in the box.
4. Send a message: the box is empty, and stays empty after *Plan* and Back.
5. Tap a missed slot on today's Day: its sheet opens with *Done*, *Open*, *Check it's done* (or *All of it done* for one of yours) and *Ask Felix about it*.
6. *Done* there: the slot shows *Kept late*, with an Undo; Undo makes it *Missed* again.
7. A missed slot whose task is done since: its sheet says so and has no *Done*.
8. *Ask Felix about it* on a piece's, an event's and the day's *Instructions* sheet: the chat opens about it, nothing sent, his unread count on the button as on the Work tab's.
9. TalkBack on a missed slot: it's read with its time and *Missed*, and offers *Done*.
