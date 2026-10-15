# Phase 6: the screens

Items: UI-17, UI-18, UI-12, UI-01, UI-02, UI-03, UI-04, UI-05, UI-08, UI-09, UI-10, UI-11, UI-13, UI-15, UI-07. Depends on phase 5 (Felix's button and name; slots from phase 3). Compose code has no unit tests in this repository; keep every piece of logic a screen needs in a pure function beside it (as `WidgetModel.from` and `Groups` are) and test that.

---

## UI-12 The logo's colours

**Files.** `ui/Theme.kt`, new `ui/Colours.kt`, `widget/NextWidget.kt`, `block/BlockedActivity.kt`, every `NotificationCompat.Builder` (`setColor`).

**Steps.**

1. `ui/Colours.kt`:
   ```kotlin
   object Colours {
       val Pink = Color(0xFFFFAAAA); val PinkPale = Color(0xFFFFD6D6); val PinkDeep = Color(0xFF5A2E2E)
       val Ink = Color(0xFF1A1A1A); val Paper = Color(0xFFFCFCFC); val Night = Color(0xFF121212); val NightRaised = Color(0xFF1E1E1E)
       val Rose = Color(0xFFB86B6B); val OffWhite = Color(0xFFEDEDED)
       val Light = lightColorScheme(primary = Pink, onPrimary = Ink, primaryContainer = PinkPale, onPrimaryContainer = Ink, secondary = Rose, onSecondary = Paper, secondaryContainer = PinkPale, onSecondaryContainer = Ink, surface = Paper, onSurface = Ink, surfaceVariant = Color(0xFFF2EAEA), onSurfaceVariant = Color(0xFF4A4040), background = Paper, onBackground = Ink, outline = Color(0xFF8A7A7A))
       val Dark = darkColorScheme(primary = Pink, onPrimary = Ink, primaryContainer = PinkDeep, onPrimaryContainer = PinkPale, secondary = Rose, onSecondary = Ink, secondaryContainer = PinkDeep, onSecondaryContainer = PinkPale, surface = Night, onSurface = OffWhite, surfaceVariant = NightRaised, onSurfaceVariant = Color(0xFFCFC5C5), background = Night, onBackground = OffWhite, outline = Color(0xFF6A5E5E))
   }
   ```
   `error`/`onError` stay Material's defaults (the standard red for overdue).
2. `AppTheme`: `MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Colours.Dark else Colours.Light, …)`; delete the dynamic-colour branch and the Android 12 check.
3. Widget: Glance's `GlanceTheme` with `ColorProviders(light = Colours.Light, dark = Colours.Dark)` (glance-material3 supports it); the pills and the header use `primary`.
4. Notifications: `.setColor(0xFFFFAAAA.toInt())` on every builder (centralise: a `Notify.builder(context, channel)` that sets the icon and colour; replace the builders).
5. Block screen: the headline on `primaryContainer`.
6. Check once with the system font at its largest that the DO NOW card and the block screen's buttons fit.

**Done when.** No `dynamicDarkColorScheme` reference remains; screenshots in light and dark match the icon's pink.


*(As built, after Codex's third review: by day the primary is `PinkDeep`, as `Pink` on `Paper` reads at under 2:1; the pink and its pale shade fill the cards and mark the notifications.)*
---

## UI-17 Two screens: Day and Work

**Files.** New `ui/DayScreen.kt` (replaces `TodayScreen.kt`), rename `ui/TasksScreen.kt` → `ui/WorkScreen.kt`, delete `ui/CalendarActivity.kt` after UI-11, `ui/MainActivity.kt`, new `ui/DayModel.kt` (pure).

**Steps.**

1. `ui/DayModel.kt` (pure, tested): `data class DayModel(val date: LocalDate, val rows: List<Row>, val plannedMin: Int, val freeMin: Int, val doneMin: Int, val wontFit: String?)` with `sealed interface Row { Window(kind, start, end); Event(calendarEvent); Slot(slot, state: Kept|Missed|Now|Ahead, micro: String?); Gap(start, end, minutes); Unslotted(chunk) ; Done(records) }` and `fun DayModel.from(plan, date, events, slotRecords, windows, now, zone): DayModel` ordering rows by time with the current slot marked `Now`.
2. `DayScreen(graph, activity)`: a `Scaffold` body with (a) a week strip (`LazyRow` of seven day chips from today, each with the date, a load bar `plannedMin/freeMin` (UI-02) and a dot for "won't fit"; tapping selects the day; the selected day is `rememberSaveable`); (b) the DO NOW card (`NextCard` from `TodayScreen`, minus Start, plus the reward line BL-04 and the micro-plan's first line when present); (c) the timeline: `LazyColumn` of the model's rows: a window row as a thin heading ("Break 10:45–11:15", colour by kind: Evening plain, Study/Break/Lunch tinted `secondaryContainer`); an event row as a tile with the calendar's name and the verdict pill (Free/Busy/A few hours/Asked; tap opens the event sheet from `CalendarActivity`, moved here as `EventSheet`); a slot row as a card with time, label, `why` line, micro-plan first line, a tick (`keepSlot`), a "Later" button (UI-03's action), and for a kept one struck through with its outcome; outside rows as small cards "Outside, 20 min"; a gap row "free 40 min" in a muted style; the now line (a thin primary rule between the row before and after `now`); unslotted chunks under a heading "No time today"; "Done today" at the foot (UI-08). Scroll to the now line on first show of today (`LazyListState.scrollToItem`).
3. The trouble cards, key-problem card and "Not planned" fold from `TodayScreen` move to the top of the Work view (they're about sources, not the day).
4. `MainActivity.TABS = listOf("Day", "Work", "Stats")`; tab 0 → `DayScreen`, 1 → `WorkScreen`, 2 → `StatsScreen`. First-run hint (a dismissible card on each of Day and Work, `rememberSaveable` + a `Settings.hintsSeen: List<String>`): "Day is when. Work is what."
5. `WorkScreen` (UI-10): the open tasks grouped by due day (`Groups.byDue`, default) or by subject (a `SegmentedButton` row; `Groups.bySubject(tasks, classMap)` new, pure); a chip row filtering by source; the camera button (phase 7, NF-01) and the New task FAB; at the foot, folded: "Finished or missed lately", "Not tasks, as you said", "Same as another" (phase 7, DX-15); the Sources fold stays.

**Tests.** `DayModelTest` (new): rows ordered; `Now` marked; gaps computed; done minutes; won't-fit text.

**Done when.** Day and Work replace Plan, Tasks and the Calendar page; nothing the old screens did is lost (walk the old screens' features: the sheet, instructions, photo check, Not planned, events, sources, new task).

---

## UI-18 The menu

**Files.** `ui/MainActivity.kt`, `ui/SettingsActivity.kt`, `ui/SetupActivity.kt`, `protect/ProtectionActivity.kt`.

**Steps.** `MENU = listOf("Reviews" to ReviewsActivity (phase 8; until then omit), "This week" to CheckInActivity, "Settings" to SettingsActivity)`. `SettingsActivity` becomes a sectioned page whose first screen is a list of sections, each opening its own page: About you (AI-05), Hours and windows, School (PL-16), Planning, Blocking and protection (the current `ProtectionActivity` content as a section page), Felix (name, line, standing instructions, memories; AI-21), Claude (key, cap, effort table read-only, cost this month: the current Setup's Claude section), Sources (the current Setup's sources), Calendar (UI-11), Notifications, Reset (phase 8, LR-08). `SetupActivity` stays reachable from the attention badge and the first run only.

**Done when.** The menu has three entries; every former Setup/Settings/Protection control is reachable under Settings.

*(As built: two entries till phase 8 brings Reviews; Backup is a page of its own, as Setup's backup had no other home among them; the first run reaches Setup by the attention mark, which shows while no source has been read; the Protection page stays for its alerts, its panel shared with Settings.)*

---

## UI-01 Time pickers

**Files.** `ui/SettingsActivity.kt`, `ui/Pickers.kt`.

**Steps.** `Pickers.TimeDialog(initialMinute: Int, onPick: (Int) -> Unit, onClose)` wrapping Material 3 `TimePicker` (`rememberTimePickerState(initialHour, initialMinute, is24Hour = true)`). `TimeField` becomes a read-only `OutlinedTextField` showing `HH:MM` with a trailing icon that opens the dialog; `WindowField` two of them with the length shown ("5 h 15"). Keep `parseTime` for the debug commands only.

---

## UI-02 Load lines

Done inside UI-17's week strip and `DayModel`; also the day header text "2 h 10 planned · 3 h 40 free" via `Format.load` (exists).

---

## UI-03 Swipe a piece: done, or later today

**Files.** `ui/DayScreen.kt`, `AppGraph.kt` (`laterToday`).

**Steps.** `SwipeToDismissBox` on slot rows: end-to-start → `graph.focus.keepSlot(key)` (for a source task's one-piece chunk with no steps: `addWorked`; the row shows a snackbar "Done. Undo" that calls `graph.focus.unkeepSlot(key)`: remove the record, untick the step, subtract the minutes, give back the credit); start-to-end → `graph.laterToday(key)`: `Slotter.firstFreeAfter(today's slots, windows, busy, from = now, minutes, where)` → an `AtTime` quick instruction (`addOwnChange(..., Change(AtTime, taskId, date, startMin), quick = true)`), or a snackbar "No time left today" when null. Thresholds: `positionalThreshold = { it * 0.4f }`.

**Tests.** `SlotterTest` covers `firstFreeAfter`.

*(As built, found on the phone on 15 Oct: Undo puts the slot's kept start back, so the piece keeps its time rather than being laid anew where the day may have no room; one swipe is one Done, the row's dismissal seen again as it's put back keeping nothing; and Day draws the plan again when a start comes back.)*

---

## UI-04 Move a piece by hand

**Files.** `ui/DayScreen.kt`, new `ui/MoveSheet.kt`.

**Steps.** Long-press a slot row → `MoveSheet`: a day picker (the next 14 days as chips, each with its load) and, for the chosen day, a time picker (`Pickers.TimeDialog`) or "Any time that day"; Apply → `OnDay` (day only) or `AtTime` (day and time) as a quick instruction; a day after the task's hard deadline is disabled in the picker with the reason "due before then". A true drag between rows is not required (Compose drag-and-drop between `LazyColumn` items costs more than it gives); record that in `PROGRESS.md`.

---

## UI-05 The widget

**Files.** `widget/WidgetModel.kt`, `widget/NextWidget.kt`, `widget/WidgetUpdater.kt`.

**Steps.** `WidgetModel.from(plan, …, now)`: `headline` = the current or next slot's time and label ("17:00–17:30 · Statics Prep"); `label` = the micro-plan's first line when present; `reward` (BL-04); `summary` = "Today: 1 of 4 kept, 1 h 20 left". A `TickAction : ActionCallback` with the slot key as a parameter calling `AppGraph.get(context).focus.keepSlot(key)` then `WidgetUpdater.update`. `nextRedrawAt` adds the next slot start and end.

**Tests.** `WidgetModelTest`: headline and summary from a plan with slots.

*(As built: the headline is the first row at every size, so the "DO NOW" caption over the old `label` went; the ✓ shows from a 2×1's width, beside ↻ where there's room for both.)*

---

## UI-08 Done today

Part of `DayModel` (`Done` rows at the foot with the day's kept slots and their outcome) and the week strip's done minutes.

---

## UI-09 The task sheet in three parts

**Files.** `ui/TaskSheet.kt`.

**Steps.** Three `SectionHeading`s: **Do** (tick the next piece / the step list with ticks, Open in source, Check with a photo, Add a photo of the work (phase 7)); **About** (due with Claude's reason (phase 7), the steps, attachments (phase 1), when it's planned and why (`why` lines of its chunks with their slot times), the micro-plan (phase 7), the instructions' text folded under "Instructions"); **Change** (Later today, Tomorrow, Move…, Not a task, Ask Felix, Read again (phase 8), Split with Felix (phase 7), Edit/Delete for own tasks, and for own tasks the `where` toggle). Remove the Done button for source tasks (the source says).

---

## UI-10 The Work view

Done in UI-17 step 5. `Groups.bySubject(tasks, classMap)`: tested.

---

## UI-11 Calendar settings

**Files.** `ui/SettingsActivity.kt` (Calendar section), `ui/DayScreen.kt` (`EventSheet`), delete `ui/CalendarActivity.kt`.

**Steps.** The Calendar section: permission state; the write calendar picker (phase 3); the read calendars with checkboxes; the range note. `EventSheet(event)`: the verdict chips (All of it / A few hours / None) calling `CalendarTime.answer`, the instruction button, location and description (phase 3). Remove `CalendarActivity` from the manifest and the menu.

---

## UI-13 Settings that say what they do

**Files.** `ui/SettingsActivity.kt`, new `ui/SettingsEffects.kt` (pure).

**Steps.** `SettingsEffects.line(field: String, settings: Settings, timetable: Timetable?, school: SchoolCalendar, today: LocalDate): String?` returning one computed sentence per field: hours → "5 h 15 of evening on a school day; with study periods about 6 h 40 on a Tuesday"; margin → "Work due Thursday 08:30 is planned by Tuesday"; ratio → "A 30-minute piece earns 10 minutes"; boxMin → "Work is cut into 30-minute pieces"; photoFromMin → "Pieces of 20 minutes or more need a photo to earn free time"; outside → "Three 20-minute outside breaks on a free Saturday". Shown as the field's supporting text. Tested.

---

## UI-15 Edit your own task's steps

**Files.** `ui/OwnTaskForm.kt`, `core/OwnTasks.kt`.

**Steps.** A "Steps" section: a list of rows (title field, minutes field, `where` toggle, drag handle via `Modifier.draggable` reorder or up/down buttons, delete), "Add a step"; the total shown and the task's minutes field disabled when steps exist (the sum rules). `OwnStep.where: String = "anywhere"`. `OwnTasks.edited` keeps ticks by title (it does) and carries `done` through a rename when the row's original index is passed (`OwnStep.wasIndex: Int?` transient field on the form's draft, applied in `apply`).

**Tests.** `OwnTasksTest`: a renamed step keeps its tick when `wasIndex` says so.

*(As built: `OwnStep.where` is nullable, null meaning as its task (so a step nobody set keeps its task's where); up and down buttons, not a drag; from the form, steps match by their place alone, so a new step with an old step's name has no tick of its own.)*

---

## UI-07 Say it instead of typing it

**Files.** `ui/AskActivity.kt`.

**Steps.** A mic `IconButton` in `InputBar`: `rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult())` with `Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(EXTRA_LANGUAGE_MODEL, LANGUAGE_MODEL_FREE_FORM).putExtra(EXTRA_LANGUAGE, "en-GB")`; the first result goes into `draft` (not sent). Hide the button when `intent.resolveActivity(packageManager) == null`.
