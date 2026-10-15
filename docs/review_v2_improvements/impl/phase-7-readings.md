# Phase 7: better readings

Items: AI-01, AI-03, AI-07, AI-08, AI-09, AI-13, AI-14, AI-15, AI-16, AI-17, AI-19, AI-20, AI-23, NF-01, DX-02, DX-08, DX-10, DX-15, NF-06. Depends on phase 5 (the profile, Felix, the chat as Felix). Independent of phase 6. Everything that touches a prompt or schema is a change the golden files (phase 8) will pin; note each in `PROGRESS.md` until then.

---

## AI-01 One repair round, then salvage

**Files.** `enrich/Prompts.kt` (`Answers`), `enrich/ClaudeEnricher.kt`, `core/Enrichment.kt`, `enrich/ModelAlerts.kt`.

**Steps.**

1. Prompt sentences (Assignment): "No step may start after the assignment's due time. Work that comes after the hand-in (revising for a test the work prepares for) gets no dates of its own; give the test's day in testDate instead." (Effort): "No block may start after the item's date."
2. `Answers.parse` returns `Parsed(enrichment: Enrichment?, complaint: String?)`: `complaint` is the exact check that failed, in words the model can act on ("Step 7, 'Revise using the marked sheet', starts on 2026-10-10, after the due time 2026-10-08T08:30"; "The steps add up to 180 minutes, not the 200 effortMin gives"; "Step 3 is 300 minutes, over the 240 allowed"). The existing `dropped` strings are the source; make them specific.
3. `ClaudeEnricher.enrich`: after the first answer, if `complaint != null`: a second call with the same system and profile, `addUserMessage(describe)`, `addAssistantMessage(firstAnswerText)`, `addUserMessage("Your answer had a problem: $complaint. Return the whole answer again with only that put right.")`; parse again. Cost is the sum; `Enricher.Result` gains `repaired: Boolean`.
4. Salvage, in `Answers.steps`: for a step whose `from` or `due` is after `dueBy` (or `from > due`), keep the step with both dates null and add a line to `adjusted` ("'Revise using the marked sheet': its start was after the hand-in, so it's planned by the task's date"); still reject (return `Checked(null, why)`) for: no title, minutes ≤ 0 or > MAX_STEP, a date that doesn't parse, more than MAX_STEPS, a sum that doesn't match within slack. `Enrichment.adjusted: List<String> = emptyList()`.
5. Order: parse → complaint? → repair call → parse (with salvage) → `dropped` only if still failing. `ModelAlerts` fires the dropped alert only on `dropped`; the sheet shows "Claude's dates for one step were adjusted" from `adjusted`.

**Tests.** `EnrichTest`: a bad `from` is salvaged with the line; an unparseable date still drops; `complaint` text for each check; `ClaudeEnricher` against `MockWebServer` (the existing fake server in `EnrichTest`): the second request carries the assistant turn and the complaint; two bad answers → dropped, `repaired = true`.

*(As built: the second time of asking is a call of its own (`Enricher.repair`, sent by `enrichNow` through `callModel`), so the cap reserves for it and a rest holds it; its item is cut to leave room for the first answer within `MAX_INPUT_CHARS`. `Answers.read` returns `Parsed`; `Answers.parse` stays, the salvaging read's enrichment, for its callers. A step due after its task is salvaged too.)*

---

## AI-03 and AI-20 Limits in the prompt and in the schema

**Files.** `enrich/Prompts.kt`.

**Steps.** Make `MAX_EFFORT`, `MAX_BLOCKS_EFFORT`, `MAX_STEP`, `MAX_STEPS` `internal` on `Answers`; `Prompts.limits(job)` builds: Assignment → "Limits the app enforces: at most 12 steps; no step longer than 240 minutes; a total of at most 10 hours; the steps must add up to effortMin exactly."; Email/Effort → "at most 30 blocks, 20 hours in all, adding up to effortMin exactly." appended to `system(job)`. Schema descriptions: `minutes` → "10 to 60 where the work allows; never over 240", `from` → "The date before which this step can't be done, only if the instructions give one; never after the task's due date", `ankiSections` → "Only for a step that is nothing but learning numbered vocabulary sections", `effortMin` → "The sum of the steps", `where` (phase 3) → its sentence; build them with a `described(schema, text)` helper.

**Tests.** `EnrichTest`: the limits text names the constants' values.

---

## AI-07 Worked examples

**Files.** `enrich/Prompts.kt` (new `Examples` object in the same file or `enrich/Examples.kt`).

**Steps.** Two or three examples per job as `val EMAIL: List<Pair<String, String>>` (input text, expected JSON), appended to `system(job)` under "Examples:" with the input indented and the JSON on one line. Synthetic: a form from a no-reply address with a date (Admin, deadline quoted, 15 min); a parent's email with three dated items (blocks); a self-note with a link (Admin, nextStep "Watch the recording", 10 min); a two-part homework with a test date (steps, testDate, ankiSections, `where`); an appointment in Power Planner (effort 5, no blocks). The JSON must validate against the schema: add a test that parses each example through `Answers.parse` and gets a non-null enrichment with no complaint.

---

## AI-08 Previous steps for stability

**Files.** `enrich/Prompts.kt`.

**Steps.** `Prompts.describe(task, job, now, zone, previous: List<SubStep>?)`: when the task has steps (current or from `task.enrichment`), append "Steps as planned before (keep the exact title of any step whose work is unchanged; mark none as done): 1. …". `ClaudeEnricher.enrich` passes `task.subSteps.ifEmpty { task.enrichment?.subSteps.orEmpty() }`.

**Tests.** `EnrichTest`: `describe` prints them.

---

## AI-09 A reason with each reading

**Files.** `enrich/Prompts.kt`, `core/Enrichment.kt`, `ui/TaskSheet.kt`.

**Steps.** `"reason" to STRING` on the Email and Assignment schemas ("the phrase in the text that gave the deadline, kind or test date, quoted, under 160 characters"); `Enrichment.reason: String? = null`; the sheet's About section shows it under the due date ("Claude: 'by Friday 17th' in the email").

---

## AI-13 Time-of-day in the chat

**Files.** `enrich/ChatReader.kt`.

**Steps.** Add to the chat's system prompt: "The context gives the time now. A deadline later today is still ahead ('due at 08:30 this morning'), not past. Say 'overdue' only where the plan marks it so." Fix the missing space after "a line a task)." in `ChatPrompts.SYSTEM`. `ChatPrompts.plan`: each slot line carries "past", "now" or "ahead".

---

## AI-14 and AI-19 Chat actions and the Steps change

**Files.** `enrich/ChatReader.kt`, `core/Instructions.kt`, `enrich/InstructionReader.kt`, `core/OwnTasks.kt`, `AppGraph.kt`, `ui/AskActivity.kt`.

**Steps.**

1. Chat schema: `"actions" to array of obj("type" to enum["tickStep", "open"], "taskId" to STRING, "stepIndex" to nullable(INTEGER), "key" to nullable(STRING))`; `ChatAnswers.Reading.actions`. System prompt bullet: "5. Actions: tickStep when the student says a step is done (taskId and stepIndex from the list); open to show them a task." `tickStep` applies at once (AI-24's instant set: it alters an existing task) through `focus.keepSlot` when the step has a slot today, else `tickBlock`; `open` is a card with an Open button.
2. `ChangeType.Steps` with `Change.steps: List<OwnStep>? = null`; `InstructionPrompts.CHANGE` schema gains `steps` (array of `{title, minutes, startDate, dueDate, where}`); `CHANGE_TYPES`: "Steps: replace a task of the student's own's steps with these, adding up to its minutes. Needs taskId and steps; only for tasks marked Yours." Parse into `OwnStep`s; `OwnTasks.MADE_ON_IT += Steps`; `OwnTasks.draftWith` applies them (ticks kept by title through `edited`). Refused for a non-own task by `problemWith`.
3. The sheet's "Split with Felix" opens the chat with `text = "Split this into steps"` and `about = About(taskId)`.

**Tests.** `ChatTest`: actions parsed; `InstructionsTest`: Steps parsed and described; `OwnTasksTest`: Steps replaces, keeps ticks.

---

## AI-15 Micro-plans, generated ahead

**Files.** New `enrich/MicroPlans.kt`, `data/RuntimeState.kt`, `enrich/Ahead.kt`, `ui/DayScreen.kt`, `widget/WidgetModel.kt`, `calendar/SlotCalendar.kt`, `enrich/FelixPrompts.kt`.

**Steps.**

1. `@Serializable data class MicroPlan(val key: String, val first: String, val next: List<String>, val doneWhen: String, val inputHash: String, val at: Long)`; `RuntimeState.microPlans: Map<String, MicroPlan>` (keyed by `chunk.key`, trimmed to today's and tomorrow's keys at each `Ahead.run`).
2. `MicroPlans.describe(chunk, task)`: the task's title, class, instructions (first 1,500 chars), the step's title and minutes, the attachments' names; `SYSTEM`: "Give the student the first action for this piece of work, under two minutes, concrete enough to do without thinking ('open the booklet at p.7 and read Q1 aloud'); then the next two actions; then what done looks like for this piece. Plain UK English, no preamble."; schema `{first, next: [string, string], doneWhen}`; effort `MicroPlan`.
3. `Ahead.run`: for each work slot today and tomorrow whose key has no micro-plan with a matching `inputHash` (`Enrichments.inputHash(task) + stepIndex`), one call (through `reserveCall(false)`), store.
4. Shown: the slot card and DO NOW card (first line, the rest on tap), the sheet's About section, the widget's `label`, the calendar event's description, Felix's `First action:` line.

**Tests.** `EnrichTest`: parse; `MicroPlans.describe`.

---

## AI-16 and AI-17 The photo check with context; several photos

**Files.** `enrich/PhotoChecker.kt`, `block/PhotoChecks.kt`, `ui/PieceActions.kt`.

**Steps.** `PhotoChecker.check(jpegs: List<ByteArray>, context: String)`: one image block per photo (up to 3), then the text: the task's title, class, instructions (first 1,500 chars), the step's title and minutes, the ticked steps; `SYSTEM` gains "Blank or crossed-out pages, a photo of a screen, printed answers, or work for a different task are not done. Name what you can see." and the schema gains `seen: string`. `PhotoChecks.check` collects up to three captures ("Add another page?" after each) before the call. The verdict's `reason` shown as "Shows worked answers to Q5–8; Q9 isn't visible".

---

## AI-23 and NF-01 A photo as context; a photo becomes a task

**Files.** `enrich/ClaudeEnricher.kt`, `enrich/Prompts.kt`, `core/Enrichment.kt`, new `enrich/PhotoTasks.kt`, `ui/WorkScreen.kt`, `ui/TaskSheet.kt`, `ui/OwnTaskForm.kt`, `AppGraph.kt`.

**Steps.**

1. Photo as context: `TaskItem.extra["photo"]` = a file path under `filesDir/photos/<taskId>.jpg` (copied from the capture); `Enrichments.inputHash` includes the file's SHA-256 (first 8 hex) when present. `ClaudeEnricher.enrich(task, job, now, image: ByteArray?)`: with an image, the user message is `addUserMessageOfBlockParams(listOf(image block, text block))` and the text starts "The student photographed the work itself; estimate and split from what the photo shows, and say in reason what you saw." (prepended to `describe`). The sheet's "Add a photo of the work" captures, stores, and calls `enrichNow(only = taskId)`; "from your photo" shown on the estimate line.
2. Photo to task: `PhotoTasks.SYSTEM` ("Read the homework in this photo (a worksheet, a page, a board) into tasks for the student's planner. For each: title in the student's words, subject if it says, due date and time if it says, the instructions as written, minutes for a typical Year 12 student, steps if it's worth splitting (each with minutes and where it can be done), where for the task (anywhere or home). If you can't read a date, leave it null and ask in question."), schema `{tasks: [{title, subject, dueDate, dueTime, instructions, minutes, where, steps: [...]}], question}`; effort `PhotoToTask`. The Work view's camera button → capture → the call → for each task, `OwnTaskForm(initial = task, photo)` in turn, Save creating it with the photo attached; "Attach to an existing task instead" lists open tasks and routes to step 1.

**Tests.** `EnrichTest`: `inputHash` changes with the photo hash; `PhotoTasks` parse.

---

## DX-08 Teams links and attachments

**Files.** `sources/teams/TeamsSource.kt`.

**Steps.**

1. In the Teams widget (see `README.md` § The Teams widget): its sync reads each assignment's detail page in Teams (that's where `description` and `detail_read_at` come from); capture from the same page the attachment names and the link URLs the page shows (reference material and attached files) into the assignment store, and expose them as two new columns on `/assignments`, `attachments` and `links`, each newline-separated, empty when none, in `provider/AssignmentsContract.kt` and `AssignmentsProvider`; a unit test for the parsing, the contract's README updated. One PR there.
2. Here: `TeamsRows.fetched` reads the two columns when present (`row["attachments"]`, `row["links"]`; absent → nothing) into `extra[EXTRA_ATTACHMENTS]`/`extra[EXTRA_LINKS]`; the sheet shows them as tappable lines (a link opens in the browser; an attachment name opens the assignment in Teams); `Prompts.describe` prints the names for the Assignment job.

---

## DX-10 Deck descriptions

**Files.** `sources/anki/AnkiSource.kt`, `sources/anki/AnkiRules.kt`.

**Steps.** `AnkiProvider.decks` adds `deck_desc` to the projection → `Deck.description: String = ""`. `AnkiRules.sectionsOf(task, decks)`: when the numbered match is empty, match the task's title + detail (lower-cased) against each deck's description as a whole phrase (description length ≥ 4, one candidate only). The Assignment prompt gets the deck list with descriptions ("Decks: 1.3 Holidays and travel; 1.4 School") so `ankiSections` can be named by topic.

---

## DX-02 Calendar invites in emails

**Files.** `sources/gmail/Mime.kt`, `sources/gmail/GmailSource.kt`, `core/Enrichment.kt`, `ui/TaskSheet.kt`.

**Steps.** In `Mime.attachments`/`collect`, note a `text/calendar` part (or an attachment named `*.ics`); `GmailSource.text()` fetches it (it's small; `BODY.PEEK[section]`) and `Mime.invite(text): Invite?` parses `SUMMARY`, `DTSTART`, `DTEND`, `LOCATION` (the common `YYYYMMDDTHHMMSSZ` and local `TZID=` forms; ignore recurrence). Stored as `extra["invite"]` (`summary|start|end|location`). `enriched()` for a Gmail task with an invite and no quoted deadline: `kind = Event`, `dueAt = start` (hard, as the sender's words). The sheet shows "Invite: Thu 16 Oct 18:00–20:00, Main Hall" with "Add to calendar" (`Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)` with the fields). P3: do it last in this phase.

**Tests.** `MimeTest`: both date forms; a missing `DTEND` gives one hour.

---

## DX-15 The same work from two sources

**Files.** New `core/Duplicates.kt`, `core/Instructions.kt`, `enrich/InstructionReader.kt`, `core/Merge.kt`, `ui/WorkScreen.kt`.

**Steps.** `Duplicates.candidates(tasks, classMap): List<Pair<TaskItem, TaskItem>>`: open tasks from different sources with the same subject (via `classMap` or equal `className`), due within a day (or both undated), and titles sharing ≥ 60 % of their words (lower-cased, ≥ 3 letters). `ChangeType.SameAs` (`taskId`, `afterTaskId` = the one kept); `TaskOverrides.sameAs: String?`; `TaskItem.userSameAs` → `hidden` is true for the duplicate; `Focus.keepSlot`/`addWorked` on the kept one; the duplicate's source finishing still finishes it. The Work view shows candidates as a line on each ("Looks like the same work as … from Teams. Same?") with a button making the change; the chat lists them in context.

**Tests.** `DuplicatesTest`: the matcher.

---

## NF-06 Returned work with feedback

**Files.** `sources/teams/TeamsSource.kt`, `core/Merge.kt`.

**Steps.**

1. In the Teams widget: the provider lists assignments not handed in; returned work appears in Teams' Completed tab with a "Returned" state and the teacher's feedback on its detail page. Have the sync read the Completed tab's returned assignments (the most recent 20 is enough) and expose them on a new path, `/returned`, with `key, title, class_name, returned_at, feedback` (feedback text as the page shows it, empty when none), under the same permission. One PR there.
2. Here: `TeamsSource.read` queries `/returned` when the provider answers it (an unknown path throws; catch and treat as none) and `TeamsRows.returned(row)` emits a `Fetched` per row: `sourceId = "$key:feedback"`, title "Read the feedback on <title>", `Kind.Admin`, `detail` = the feedback, `dueAt = returned_at + 3 days`, `sourceEffortMin = 10`, `extra["tickDone"] = "true"` so a tick finishes it (`Focus.keepSlot` → done); gone from `/returned` → finished.
