# Screens, the blocker, protection and the widget

Eleven findings, all standing; two narrower than written. Ids: max UI-, ultra UB-. The two probes, `AstraUiReviewTest` (U-01) and `ReviewUiBlockTest` (U-06), are in [evidence/](evidence/); the rest are source-traced, and none was run on the phone.

### U-01 · P1 · A subject named "No subject" crashes the Work list

Max UI-01. Reproduced: `AstraUiReviewTest` "a subject named No subject and an unclassified task must not duplicate Work list keys" fails: keys `[group-No subject, group-No subject]`.

- **Where:** `ui/Groups.kt:64-68` groups by the subject string and adds the tasks without one under `NO_SUBJECT = "No subject"` (`:71`); `ui/WorkScreen.kt:173-176` makes `"group-$title"` the lazy list's item key and the fold state's identity. `OwnTaskRules.problem` (`core/OwnTasks.kt:83-94`) never checks the subject; the form's field is free text.
- **What happens:** one task whose subject is "No subject" (typed, or a class mapped to it in Settings), one with none, By subject in Work: Compose refuses a key used twice when both headings are on screen. The two groups also fold as one.
- **Fix:** give groups an identity apart from their label (a reserved one for no subject) and use it for both the key and the fold.

### U-02 · P1 · Turning the phone during a task's save leaves the form open with Save disabled for good

Max UI-02, ultra UB-04. Source-traced.

- **Where:** `ui/OwnTaskForm.kt:82-83`: `saving` is `rememberSaveable`, the save runs in `rememberCoroutineScope`; `:114-116` the only reset of `saving` is the old coroutine's failure branch. `saveOwnTask` (`tasks/OwnTasks.kt:51`) is `NonCancellable`, so the write lands, but the cancelled scope never runs `onClose`. The hosts restore the form (`WorkScreen.kt:90` `making`, `TaskSheet.kt:88,96` `editing`, both saveable); the manifest has no `configChanges`.
- **What happens:** rotate in the milliseconds between Save and the write landing. The task is saved; the restored form stays open, Save disabled, till Close. On the photo path (`PhotoTasksSheet.kt:53-58`) the task is saved without its photo (`attachPhoto` is skipped), and re-adding uses the same id, so no duplicate.
- **Checked:** the chat's proposal form heals itself, which neither review saw: once applied it's drawn from the `added` branch (`AskActivity.kt:589-597`), a fresh composition. A tiny window, Close recovers; P1 as a visibly broken control.
- **Fix:** own the save and its state outside the composition, or let a restored form reconcile by the task's stable id.

### U-03 · P1 · The rating dialog's typed feedback and chosen text are lost when the phone turns

Max UI-03. Source-traced.

- **Where:** `ui/AskActivity.kt:1136` the dialog's visibility, `:974-975` its words and preferred text, all `remember`. The chat box (`:413`), the instruction box (`InstructionsUi.kt:54`) and the task-note words (`TaskSheet.kt:490`) are all `rememberSaveable`.
- **Fix:** keep the message id, thumb, words and chosen text in saveable state.

### U-04 · P1 · Protection goes on saying its notification channel is off after you've turned it on

Max UI-04. Source-traced.

- **Where:** `protect/ProtectionActivity.kt:223-224` cache `allowed()` and `shown(Channels.PROTECTION)` under `remember(refresh)`; the channel's settings page opens through a plain `startActivity` (`:231-235`) that never bumps `refresh`, and the panel has no resume effect, unlike `SetupScreen.kt:103` and `CalendarSection.kt:58`. The watchdog's `report` (`:118`) is keyed the same way, so the "no accessibility shortcut" row is stale too.
- **What happens:** Protection → Notifications, Allow → turn the channel on → Back: the row still says it's off and offers Allow. Inside Settings the panel refreshes if scrolled off and back (a LazyColumn item); the standalone page has no such escape.
- **Fix:** recompute on resume (`LifecycleResumeEffect`), as Setup does.

### U-05 · P1 · A chat message whose first write fails is gone from both the box and the chat

Max UI-05. Source-traced.

- **Where:** `ui/AskActivity.kt:550-562` clears the box and reply state, launches `ask`, and restores the text only when `ask` returns false. `Conversation.send` (`felix/Conversation.kt:496-510`) calls `chat.update` with no catch; `JsonStore` writes before it publishes (`data/JsonStore.kt:109-113`) and lets the write throw (`:202-207`); the graph's handler only logs (`AppGraph.kt:98`). The reply path (`:520` on) has the same shape.
- **Checked:** only when writing `chat.json` fails (a full disk, an I/O error), which breaks most of the app at the same time. P1 by the rubric (typed input lost); narrow.
- **Fix:** keep the text till the write is durable, and catch the failure into a retryable state.

### U-06 · P2 · Day's Undo refuses the swipe that finished a task of your own

Ultra UB-01 (P1). Reproduced: `ReviewUiBlockTest` "Day swipe Undo must reopen the own task its last piece just finished" fails.

- **Where:** `ui/DayScreen.kt:281` calls `unkeepSlot(chunk.key, since)` with the default `reopen = false`; `block/Focus.kt:487-488` reopens a closed task only with `reopen`. `keepSlot` finishes a task of your own whose last piece this was, before the snackbar shows (`:318`, `AppGraph.kt:246`).
- **What happens:** swipe Done on the only remaining piece of a task of your own that has no steps, tap Undo: "It can't be taken back now: its task is finished." The task can still be reopened through the chat.
- **Checked:** ultra's "the same after the last remaining step is kept" is wrong: `ownDone` (`Focus.kt:447`) needs `subSteps.isEmpty()`, so a task with steps isn't finished by a keep and its Undo works. The refusal is described in `slots-and-calendar.md:89` (the chat's Undo opens it again), not decided; `reopen` was added in 4.1.1 for the chat alone, and requirement UI-03 says each has Undo. P2: the Undo fails and says so, nothing lost.
- **Fix:** don't pass a bare `reopen = true`: mirror the chat's guard, `ChatActs.finishedBy(task, record.at)` (`core/Chat.kt:365`), so a task finished another way meanwhile stays finished; amend `slots-and-calendar.md:89`.

### U-07 · P2 · "Done: 5 min free" for a keep that earned nothing

Ultra UB-03 (P1). Source-traced.

- **Where:** `ui/PieceActions.kt:164-167` builds the line from `Credit.forSlot(piece.minutes, ratio)`; `keepSlot` returns only a Boolean (`Focus.kt:314`). Focus pays nothing for a piece not laid today (`:351-352`, BL-08; `FocusTest.kt:785`) and caps at today's share (`:368`), so a keep within today can over-claim too. The photo and source lines ("earns its N min free") promise the same way.
- **Reachable:** a task sheet's Next Done works on the task's next piece whatever its day (`TaskSheet.kt:227`); a tomorrow piece's sheet on Day (`PieceActions.kt:246`).
- **Fix:** return what was earned from the keep and build the line from it; the policy stays.

### U-08 · P2 · Re-entering a blocked app with under a minute of free time gets no last-minute warning

Max UI-07 (P1). Source-traced.

- **Where:** `block/FocusService.kt:316-317` removes the warning callback and re-posts it only for `left > WARN_MS`; `stopSpending` (`:320-327`) cancels both notices on leaving; later `countdown` calls return early while the end hasn't moved (`:313`). The spendable time is the lesser of credit and the gap to the next slot (`Focus.kt:139-142`), so a gap under a minute does it too.
- **What happens:** the warning fires, you switch away (cancelling it), come back: no new one; blocking lands at zero with the silent countdown the only sign.
- **Against:** `blocking-and-protection.md:60`, a heads-up comes in its last minute.
- **Fix:** warn at once when a spending period begins inside the threshold, once per period, with the real remaining time.

### U-09 · P2 · The instruction dialog promises you'll check Claude's reading before anything changes

Max UI-06, ultra UB-05 (both P1). Source-traced.

- **Where:** `ui/InstructionsUi.kt:63`: "Claude reads it, then you check what it understood before anything changes." Both Day callers (`DayScreen.kt:531-533,706-708`) send the text to the chat, auto-sent (`InstructionsUi.kt:143-145`), where every card is instant (D-60, `decisions.md:104`); the rewrite path (`:133-136`) applies as soon as it's read (`tasks/Instructions.kt:247`, D-67). The KDoc at `core/Instructions.kt:472-474` says the same stale thing.
- **Checked:** the behaviour is the decision; the copy is wrong. P2 for that (both reviews' P1 counts wrong copy as a UI bug). "Claude" rather than "Felix" in this dialog is the app's convention for the model, as in Settings and Reviews, not a defect.
- **Fix:** say changes are made at once and can be taken back; fix the KDoc with it.

### U-10 · P3 · Four sentences describe rules that were replaced

Max UI-08 (P1 for the first), ultra UB-06 (all four).

| Where | Says | Now |
|---|---|---|
| `res/values/strings.xml:12` (the accessibility service's description) | work due "today or tomorrow" blocks | tomorrow's work alone never blocks (`blocking-and-protection.md:40`, `PlannerTest.kt:1393-1398`). Safe to reword: `FocusService.kt:173` recognises the settings page by the first sentence alone (`substringBefore(".")`), and this is the second; keep a full stop out of the first. `ProtectTest.kt:340` hard-codes a copy |
| `ui/DayScreen.kt:140` | the card folds as the grid scrolls | the grid's own scrolls never fold it (`:305`, `day.md:9`; D-118's text keeps the old phrase, the spec records the replacement) |
| `ui/MoveSheet.kt:35-38` | the sheet is the long press, no drag between rows | a long press drags (`DayGrid.kt:363,416`); the sheet opens from the piece or task sheet (D-118) |
| `blocking-and-protection.md:108` | quiet hours and the time before blocking are "the only release" | its own line 34 has the parent code's hour, and earned time and gaps release too |

### U-11 · P3 · The widget's navigation row sends task taps to Day

Max UI-09. `felix.md:37` says the widget's slot, warning and exclamation all open Day. The warning, summary and exclamation do (`widget/NextWidget.kt:99,289`); the headline and list rows open the task (`:165-167,249` → `OpenTaskActivity` → `TaskOpener`, the chat when it's gone), as requirement UI-05 and D-121 say and as the table's own `:43` row has it. Split the row.
