# The photo check: more pages, a memory, and more doors

Three releases. 6.4.0 changes the check itself; 6.5.0 opens it from more places on the phone, from the gallery too, with the tick box; 6.7.0 opens it on the website. One judge throughout (`Photos.checkJpegs`, `enrich/PhotoChecker.kt`): every door hands it pages and a piece, and it alone says done.

## 6.4.0: ten pages, and what earlier checks saw (built: D-155, Q152 to Q154)

**Ten pages.** `PhotoChecker.MAX_PAGES` goes from 3 to 10, and with it the prompt's "up to 3 photos", *Add another page?*'s line, the pages the screen keeps through a turn of the phone, and the server's count of what a check may send.

- The cap's dearest call isn't raised. Today a check's text is cut to the 40 000 characters every call is costed for, though it's a piece's line and 1 500 characters of instructions. Cut to a bound of its own, the room that leaves is the pictures': a test shows ten pages, the text at its bound and the prompt cost no more than `Pricing.WORST_CALL_USD`, as `AiUsageTest` shows three do now. Each page is costed at its dearest shape, a square of 1 568 pixels a side (the 3 300 tokens `Pricing` already allows a picture), not a page's shape, as a picture from the gallery or the website can be any shape; `PROMPT_TOKENS`, which holds three pictures' worth, is set again with it.
- The server's bound on a check's size (`MAX_CHECK_BYTES`) is a photo's 4 MB for each page and a third again: at ten pages, 53 MB taken into memory. A shrunk page is far smaller; the bound is set from real pages measured, with room.
- `POST /commands` checks no size at all. It gets a bound, as the photo routes have.
- Its three minutes (`ModelEffort`'s `PhotoCheck`) stay unless ten real pages show otherwise.

**Each page named.** A line "Page 1:", "Page 2:" before each picture, as the API's guide to several images says, so the answer can say which page shows what and which is blank.

**What a check saw is kept.** The answer gains `covered`: exactly which parts of the work these photos show done, by question, page or section, written to be relied on later. A plain string, required, as the schema's others are.

- After every check that says done or not yet, its note is kept: the task, the piece's key and label, `covered`, whether it passed, and when. Not after one Claude couldn't answer, or a call that failed.
- The next check of any piece of that task is told them: "Earlier checks of this task", each with its piece, its day and time, passed or not, and what it showed. The prompt says what they showed counts as shown, the photos themselves not being sent again, and that the piece is done when those and these together show all of it.
- No part credit, as now (AI-17): a piece shown in part earns nothing till the rest is shown. What changes is that the rest is enough.
- **Nothing earns twice.** A note of a check that passed has earned already, for its own piece. It still counts as shown, so a later piece's check needn't show it again, but that check must show work no passed note covers: a second sitting whose photos show nothing new is refused, though the first's note says every page was shown. The answer says so in a field of its own (`fresh`: these photos show work no check that passed has shown), which the app holds a pass to wherever the task has a passed note, so the rule is the app's and not the prompt's alone. A note of a check that didn't pass carries over whole, to its own piece and the task's others. What follows: work for two sittings all shown at the first earns the first's share; the second is kept by its tick, earning nothing, as there's nothing left to show.
- What you're told: "Not yet: pages 11 and 12 aren't shown. What these showed is remembered: photograph the rest."
- The notes are in `session.json` (version 3 to 4, nothing in a file from before to change, a fixture with the field filled), written by `Focus`, its owner, at `Photos`' asking. Eight a task at most, the newest; a task's go when it's done or gone. Once the phone sends commands the check is the server's (D-148), so its notes are written there and pulled.
- **In the backups.** A backup takes `session.json`'s slots alone today (`Backup`'s parts). The notes join them: with the pages deleted they're the only record of what was shown, and a restore without them would refuse pages 11 and 12 for want of 8 to 10. A part of their own, read from a backup of before as none, and tested so. A reset of everything learned lets go of them.
- *Shown so far* on a task's sheet, the phone's and the website's: its notes, newest first, so what's still wanted can be seen before the camera's out.

**A page taken again.** *Add another page?* says how many are taken ("Page 3 taken") and offers *Retake* beside *Add a page* and *Check it now*: with ten pages, one blurred page shouldn't cost a check.

**Prompts and goldens.** `photo-check.txt` and `photo-check.schema.json` change with the prompt and the schema, the schema compiled by the API once (`SchemaCompileTest`, about a cent). No reading's prompt changes, so `ReadingPrompts.VERSION` stays.

**Judged on real pages first.** A small set of real pages in `private/`, each case sent as the app sends it and judged through the app's own parser: a piece whole in one check; split across two; split with a page never shown; another task's work; a blank page among good ones; a second sitting sent a page the first's passed check showed already, which must be refused. Each run two or three times, the prompt before beside the one after, every answer kept. A few cents a run.

**Tests.** `PhotoCheckerTest` (the pages named, the notes in the text, `covered` read and cut), `PhotosTest` (a note kept after a pass and a not yet, none after a failure; the second check told the first's; a second sitting showing nothing new refused, and one showing the rest passed), `FocusTest` (the notes' cap, and their going with the task), the migration's test from every earlier shape, the backup's (the notes kept, and none in one from before), `AiUsageTest` (ten square pages within the dearest call), `ApiTest` (the bounds).

**Docs.** `spec/blocking-and-protection.md` §Photo checks; `spec/privacy.md` (ten photos, and the notes kept); `spec/requirement-ids.md` AI-17; `spec/storage-and-backups.md` and `plan_v4/data-and-migrations.md` (the store's new shape); `spec/server.md` (the bounds).

**At the phone.** A piece of five pages checked in one go. A piece checked with its first pages, refused with the rest named, then passed with the rest alone, earning once. *Shown so far* on its sheet between the two. A page retaken. A task in two sittings: the first passed with its pages, the second refused with one of those pages again, then passed with the rest.

## 6.5.0: more doors on the phone (built: D-156, Q155 to Q158)

**A missed slot's sheet** gains *Check with a photo* where it offers *Done*, by the same rule (`MissedActions`, which gains it, so the website's sheet has it from the same place): the last miss of its key that day, its piece still in the plan, written homework, Claude on.

**A slot ticked *Done*.** Its grey block on Day, and its row under *Done*, open a sheet: its label and times, how it was kept, whether it's earned, and *Check with a photo* while it can still earn (kept today without earning, written homework, Claude on).

- Today a photo earns for a tick only when it's taken within half a minute of it, as a slot's key alone can't tell one kept slot of a task from another. The check from this sheet names its record (its key, start and when it was kept), so the earning lands on that slot and no other: `PhotoDone` gains the record it's for, and `Focus` earns it by `earnKept`, as a photo after a tick does now. `session.json` version 4 to 5.
- The piece has left the plan by then, so the check is told the record's label and minutes in the piece's place, with its task. The ask the phone sends the server names the record, and the server finds it in its own log.
- That day only: a kept slot earns within the day's plan, as now.

**Work's camera button** opens a sheet of three: *New tasks from a photo* (as now), *Check work with a photo* (now's piece first, today's other written pieces, then the slots ticked *Done* that can still earn), *Add a photo to a task* (the tasks whose own sheet offers it).

**The gallery.** Wherever a photo's taken, for a check, for a task, for homework read into tasks, there's *Camera* or *Gallery*: Android's photo picker, which asks no permission, several at once for a check up to the pages left. A picture chosen is copied, turned upright and shrunk as a camera's is. The phone's check no longer takes only what's photographed there and then; the judge's own rules are the guard (not a screen, not printed answers, the right task), as they are on the website (6.7.0).

**The tick box.** *Delete after use*, ticked each time it's shown, beside the pages before a check and wherever a photo's sent. Ticked: as now, the pages deleted whatever the check says. Unticked: kept with their task (README, *Answered on 19 Nov* 3), and deleted when the task's done or gone (deleted, or dropped by its source), at a reset, or 14 days after they were taken, whichever is first; a sweep lets go of any photo file nothing names, as the task photos' does now (`Photos.sweepPhotos`), the chat's kept photos with them. The gallery's own copy is never touched.

**Docs.** `spec/day.md` (the blocks' table: a kept slot's tap; a missed slot's sheet), `spec/blocking-and-protection.md` (the doors, the gallery, what a kept slot's photo earns), `spec/privacy.md` (a check's pages kept when unticked), `spec/sync.md` (the ask's new field).

**At the phone.** A missed slot kept by its photo. A longer piece ticked *Done*, then checked an hour later from its block, earning then, once. Each row of Work's menu. A check from three gallery pictures. A check unticked, its pages on the task's sheet, gone when the task's done.

## 6.7.0: the check on the website

- `POST /photos/check` is the phone's alone today ("A photo check is the phone's"). It opens to the website, once the phone sends commands, as it is for the phone.
- The piece's menu, the missed slot's and the kept slot's sheets on Day, and the task's sheet, gain *Check with a photo* from the server's actions ([website-day.md](website-day.md)): up to ten pictures chosen or taken, shrunk in the browser as *From a photo* on Work shrinks one, with the tick box.
- A browser can't insist on the camera, so any saved picture can be chosen. The owner's answer: any picture, the same judge, the same earning. It's written into the decision, so it isn't later mended as a fault.
- When the photo was taken is when the server received it.
- Tested with the stand-in Messages API in the website's end-to-end tests: a check that passes, one refused with its note shown, one too big.
- **Docs.** `spec/website.md` (Day and a task's sheet: no longer "not on the website"), `spec/server.md` (the route's clients).
