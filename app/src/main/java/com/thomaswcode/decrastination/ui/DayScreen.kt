package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.enrich.MicroPlan
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Day (UI-17): the week ahead in a strip, each day with its load (UI-02) and a mark where work
 * is behind; today's DO NOW card; and the chosen day as it runs (a [DayModel]): its windows, its
 * calendar's events, its slots (on record, under way, to come) with a tick each, the free time
 * between them, the moment it is, the pieces it has no time for, and what's been done (UI-08).
 * What the plan leaves out, and the sources, are on Work.
 */
/** How soon a second Done of the same row is the same swipe seen again (UI-03). */
private const val SWIPE_AGAIN_MS = 3_000L

@Composable
fun DayScreen(graph: AppGraph, activity: Activity) {
    val tasks by graph.tasks.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val asked by graph.stores.calendar.state.collectAsStateWithLifecycle()
    val written by graph.stores.model.state.collectAsStateWithLifecycle()
    val session by graph.stores.session.state.collectAsStateWithLifecycle()
    val log by graph.log.state.collectAsStateWithLifecycle()
    val school by graph.school.state.collectAsStateWithLifecycle()
    val instructions by graph.instructions.state.collectAsStateWithLifecycle()
    val calendar by graph.sync.calendarState.collectAsStateWithLifecycle()
    // The day moves with the clock as well as the data: slots begin and end, the evening runs out.
    val now by produceState(graph.clock.now()) {
        while (true) {
            delay(60_000)
            value = graph.clock.now()
        }
    }
    // Today's starts kept and missed (PL-13) are the plan's too: an Undo putting a slot back redraws it.
    val plan = remember(tasks, settings, now, instructions, calendar, session.slots) { graph.planning.plan(tasks, settings, now) }
    val zone = graph.clock.zone()
    val scope = rememberCoroutineScope()
    // How the last action went, and the task it's about, as the old Plan had it.
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var messageFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(MESSAGE_MS)
            message = null
        }
    }
    val say = { taskId: String?, text: String ->
        messageFor = taskId
        message = text
    }
    val photo = rememberPhotoCheck(graph, activity, plan, say)
    fun messageOf(taskId: String, general: Boolean = false): String? = message?.takeIf { messageFor == taskId || (general && messageFor == null) }

    // The day chosen in the strip (today's by default), a piece's sheet, a task's, an event's.
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val day = chosen?.let(LocalDate::parse)?.takeIf { it >= plan.today && it <= plan.today.plusDays(WEEK - 1) } ?: plan.today
    val today = day == plan.today
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    // The last Done by swipe, so the row's dismissal seen again as it's put back keeps nothing and says nothing.
    var lastDone by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var changing by rememberSaveable { mutableStateOf<String?>(null) }
    var eventFor by rememberSaveable { mutableStateOf<String?>(null) }
    // A piece being moved by hand (UI-04), by its key; an instruction about the day being written.
    var moving by rememberSaveable { mutableStateOf<String?>(null) }
    var writingDay by rememberSaveable { mutableStateOf(false) }
    val answers = asked.eventAnswers + InstructionRules.eventAnswers(instructions.applied)
    val model = remember(plan, day, calendar, log, school, settings, tasks) {
        DayModel.from(plan, day, calendar.events, log.slots, SchoolDays.windows(day, settings, tasks.timetable, school), now, zone)
    }
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    // Before the day's rows: the strip, today's DO NOW card, the day's heading and what you've said
    // about it, its deadlines that won't fit.
    val head = 4 + (if (today) 1 + plan.wontFit.size else 0)
    // Today's moment in view, as today's first shown.
    LaunchedEffect(day) {
        if (today) model.rows.indexOfFirst { it is DayModel.Row.Now }.takeIf { it > 0 }?.let { list.scrollToItem(head + it - 1) }
    }

    Box(Modifier.fillMaxWidth()) {
    LazyColumn(state = list, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "hint") { Hint(graph, "day", "Day is when: today's slots as they come, and the week's days above. Work is what: every task, on the next tab.") }
        item(key = "week") { WeekStrip(plan, day) { chosen = it.toString() } }
        if (today) {
            item(key = "next") {
                val unread = Source.SYNCED.filter { tasks.status(it).lastSuccessAt == null }
                NextCard(
                    graph, activity, plan, zone, photo, ::messageOf, say,
                    empty = if (unread.isNotEmpty()) "Nothing planned yet: ${unread.joinToString { it.label }} not read yet." else "No work is planned. New tasks appear as the sources are read.",
                )
            }
        }
        item(key = "heading-$day") {
            SectionHeading(
                dayName(day, plan.today),
                trailing = listOfNotNull(
                    Format.load(model.plannedMin, model.freeMin),
                    model.doneMin.takeIf { it > 0 }?.let { "${Format.minutes(it)} done" },
                ).joinToString(" · "),
            )
        }
        // Your instructions about the day, its limit and busy times, and one more (the Calendar page's, UI-11).
        item(key = "said-$day") {
            val rules = instructions.applied.flatMap { it.changes }.filter { c ->
                (c.type == ChangeType.DayLimit || c.type == ChangeType.BusyTime) &&
                    (c.date == day.toString() || (c.date == null && c.weekday == day.dayOfWeek.value))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                rules.forEach { rule ->
                    Text(
                        "Your instruction: " + InstructionRules.describe(rule, emptyMap(), zone),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
                TextButton(onClick = { writingDay = true }) { Text("An instruction about this day…") }
            }
        }
        if (today) {
            itemsIndexed(plan.wontFit, key = { _, it -> "wontfit-${it.taskId}" }) { index, short ->
                WontFitTile(short, index, plan.wontFit.size, plan.now, zone) {
                    val text = "There isn't time for “${short.title}” by ${Format.at(short.deadline, plan.now, zone)}: ${Format.minutes(short.leftMin)} left, " +
                        "${Format.minutes(short.freeMin)} free. What should I shorten, move or ask for more time on?"
                    activity.startActivity(AskActivity.intent(activity, About(taskId = short.taskId, taskTitle = short.title), text, send = false))
                }
            }
        }
        if (model.rows.none { it is DayModel.Row.Planned || it is DayModel.Row.Recorded || it is DayModel.Row.Unslotted }) {
            item(key = "empty-$day") {
                Text("Nothing planned.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
            }
        }
        items(model.rows, key = { rowKey(it) }) { row ->
            when (row) {
                is DayModel.Row.Window -> WindowRow(row, zone)
                is DayModel.Row.Event -> EventRow(row.event, EventJudge.judge(row.event, answers), asked.eventsAsked, now, zone) { eventFor = eventKey(row.event) }
                is DayModel.Row.Planned -> PlannedRow(
                    row, plan, zone, messageOf(row.slot.chunk?.taskId.orEmpty()), today, row.slot.chunk?.key?.let { written.microPlans[it] },
                    onOpen = { row.slot.chunk?.let { sheetFor = it.key } },
                    onMove = { row.slot.chunk?.let { moving = it.key } },
                    // Done, with an Undo while it's said (UI-03).
                    onDone = {
                        val chunk = row.slot.chunk ?: return@PlannedRow
                        // One swipe, one Done.
                        val at = SystemClock.elapsedRealtime()
                        if (lastDone?.let { (key, t) -> key == chunk.key && at - t < SWIPE_AGAIN_MS } == true) return@PlannedRow
                        lastDone = chunk.key to at
                        scope.launch {
                            val since = graph.clock.now()
                            if (!graph.focus.keepSlot(chunk.key, seenAt = now)) {
                                snackbar.showSnackbar("That piece has changed since (done, or planned again).")
                            } else if (snackbar.showSnackbar(listOfNotNull("Done: ${chunk.label}", stillPlanned(graph, chunk.taskId)).joinToString(". "), actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                                if (!graph.focus.unkeepSlot(chunk.key, since)) snackbar.showSnackbar("It can't be taken back now: its task is finished.")
                            }
                        }
                    },
                    onLater = {
                        val chunk = row.slot.chunk?.takeIf { today } ?: return@PlannedRow
                        scope.launch {
                            if (plan.chunksOf(chunk.taskId).firstOrNull()?.key != chunk.key) {
                                // A time pins its task's next piece: this one goes by its day.
                                snackbar.showSnackbar("Only its next piece takes a time: hold it to move all of it to a day")
                            } else {
                                val at = graph.instructions.laterToday(chunk.key)
                                snackbar.showSnackbar(at?.let { "Later today, at ${clock(it, zone)}: undone in the chat's Your changes" } ?: "No time left today it can be done in")
                            }
                        }
                    },
                )
                is DayModel.Row.Recorded -> RecordedRow(row.record, zone)
                is DayModel.Row.Gap -> GapRow(row)
                is DayModel.Row.Now -> NowRow(row.at, zone)
                is DayModel.Row.Unslotted -> Column {
                    SectionHeading("No time today")
                    row.chunks.forEachIndexed { index, chunk -> ChunkTile(chunk, null, index, row.chunks.size, plan.now, zone) { sheetFor = chunk.key } }
                }
                is DayModel.Row.Done -> Column {
                    SectionHeading("Done today", trailing = Format.minutes(row.records.sumOf { it.workedMin }))
                    row.records.forEachIndexed { index, record ->
                        ListTile(index, row.records.size) {
                            Text(record.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${outcome(record)} · ${Format.minutes(record.workedMin)}" + if (record.earned) " · earned" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }

    // A piece's actions, as the block screen has them.
    sheetFor?.let { key ->
        val chunk = plan.ordered.firstOrNull { it.key == key }
        if (chunk == null) {
            LaunchedEffect(key) { sheetFor = null }
        } else {
            PieceSheet(graph, activity, chunk, plan.now, zone, photo, messageOf(chunk.taskId), { say(chunk.taskId, it) }, onChange = {
                sheetFor = null
                changing = chunk.taskId
            }) { sheetFor = null }
        }
    }
    changing?.let { id -> TaskSheetHost(graph, activity, id, plan, photo, messageOf(id)) { changing = null } }
    if (writingDay) {
        InstructionDialog("About ${dayName(day, plan.today)}", onDismiss = { writingDay = false }) { text ->
            writingDay = false
            sendInstruction(activity, text, About(day = day.toString()))
        }
    }
    moving?.let { key ->
        val chunk = plan.ordered.firstOrNull { it.key == key }
        if (chunk == null) {
            LaunchedEffect(key) { moving = null }
        } else {
            MoveSheet(graph, chunk, plan, zone, onDone = { said ->
                moving = null
                scope.launch { snackbar.showSnackbar("$said: undone in the chat's Your changes") }
            }) { moving = null }
        }
    }
    eventFor?.let { key ->
        val event = calendar.events.firstOrNull { eventKey(it) == key }
        if (event == null) LaunchedEffect(key) { eventFor = null } else EventSheet(graph, activity, event, answers, InstructionRules.eventAnswers(instructions.applied).keys, now, zone) { eventFor = null }
    }
}

/** How many days the strip shows. */
private const val WEEK = 7L

private val SHORT_DAY = DateTimeFormatter.ofPattern("EEE d", Locale.UK)
private val LONG_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

private fun dayName(day: LocalDate, today: LocalDate) = when (day) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> day.format(LONG_DAY)
}

private fun clock(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).format(CLOCK)

private fun eventKey(event: CalendarEvent) = "${event.id}@${event.start}"

/** Each row's key in the list, its own however alike two rows are: a slot kept twice is two records, told apart by when. */
internal fun rowKey(row: DayModel.Row): String = when (row) {
    is DayModel.Row.Window -> "window-${row.start}"
    is DayModel.Row.Event -> "event-${eventKey(row.event)}"
    is DayModel.Row.Planned -> "slot-${row.slot.key}-${row.slot.start}"
    is DayModel.Row.Recorded -> "record-${row.record.key}-${row.record.start}-${row.record.outcome}-${row.record.at}"
    is DayModel.Row.Gap -> "gap-${row.start}"
    is DayModel.Row.Now -> "now"
    is DayModel.Row.Unslotted -> "unslotted"
    is DayModel.Row.Done -> "done"
}

private fun outcome(record: SlotRecord): String = when (record.outcome) {
    SlotOutcome.Kept -> "Kept"
    SlotOutcome.KeptLate -> "Kept late"
    SlotOutcome.Missed -> "Missed"
    SlotOutcome.Moved -> "Moved"
}

/** The week ahead, a day a chip (UI-02): its name, its load as a bar, and a mark where work is behind. */
@Composable
private fun WeekStrip(plan: Plan, chosen: LocalDate, onPick: (LocalDate) -> Unit) {
    LazyRow(Modifier.padding(top = 12.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items((0L until WEEK).map { plan.today.plusDays(it) }, key = { it.toString() }) { date ->
            val bucket = plan.bucket(date)
            val planned = bucket?.plannedMin ?: 0
            val free = bucket?.capacityMin ?: 0
            val over = planned > free
            Card(
                onClick = { onPick(date) },
                modifier = Modifier.width(76.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = if (date == chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (date == plan.today) "Today" else date.format(SHORT_DAY), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (bucket?.chunks.orEmpty().any { it.behind }) Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    }
                    LinearProgressIndicator(
                        progress = { if (free > 0) (planned.toFloat() / free).coerceIn(0f, 1f) else if (planned > 0) 1f else 0f },
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(Format.minutes(planned), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A window of the day, as a heading: school's tinted, the evening plain. */
@Composable
private fun WindowRow(row: DayModel.Row.Window, zone: ZoneId) {
    val name = when (row.window.kind) {
        WindowKind.Evening -> "Your time"
        WindowKind.Study -> "Study period"
        WindowKind.Break -> "Break"
        WindowKind.Lunch -> "Lunch"
    }
    val school = row.window.kind.atSchool
    Text(
        "$name ${clock(row.start, zone)}–${clock(row.end, zone)}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (school) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** An event of the day's: its name, its time, its calendar, and how the plan counts it. */
@Composable
private fun EventRow(event: CalendarEvent, judgement: EventJudge.Judgement, asked: Collection<String>, now: Long, zone: ZoneId, onClick: () -> Unit) {
    ListTile(0, 1, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(event.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(if (event.allDay) "All day" else "${clock(event.start, zone)}–${clock(event.end, zone)}", event.calendar).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Pill(verdict(judgement, EventJudge.key(event) in asked))
        }
    }
}

/** How the plan counts an event, in a word or two. */
private fun verdict(judgement: EventJudge.Judgement, asked: Boolean): String = when (judgement) {
    EventJudge.Judgement.Free -> "Free"
    EventJudge.Judgement.Busy -> "Busy"
    is EventJudge.Judgement.Load -> "${Format.minutes(judgement.minutes)} of the day"
    EventJudge.Judgement.Ask -> if (asked) "Asked" else "Not known"
}

/**
 * A slot of the plan's: its time, its piece and why it's there, and Done; swiped right to left it's
 * done, left to right it's later today (UI-03); held, it's moved (UI-04). Outside, a small card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannedRow(row: DayModel.Row.Planned, plan: Plan, zone: ZoneId, message: String?, today: Boolean, micro: MicroPlan?, onOpen: () -> Unit, onMove: () -> Unit, onDone: () -> Unit, onLater: () -> Unit) {
    val slot: Slot = row.slot
    val chunk = slot.chunk
    if (slot.kind == SlotKind.Outside || chunk == null) {
        Text(
            "${clock(slot.start, zone)}–${clock(slot.end, zone)} · Outside, ${Format.minutes(((slot.end - slot.start) / 60_000L).toInt())}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
        )
        return
    }
    val now = row.state == DayModel.State.Now
    val swipe = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.4f })
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = swipe,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
        // Later today is today's: another day's slot is held to move it.
        enableDismissFromStartToEnd = today,
        backgroundContent = {
            val done = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = if (done) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) { Text(if (done) "Done" else "Later today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        },
        onDismiss = { value ->
            // Back in place: the row goes when the plan's made again.
            scope.launch { swipe.reset() }
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> onDone()
                SwipeToDismissBoxValue.StartToEnd -> onLater()
                else -> Unit
            }
        },
    ) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onOpen, onLongClick = onMove, onLongClickLabel = "Move it"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (now) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${clock(slot.start, zone)}–${clock(slot.end, zone)}" + when (row.state) {
                        DayModel.State.Now -> " · now"
                        DayModel.State.Gone -> " · gone by"
                        DayModel.State.Ahead -> ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (chunk.urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Text(chunk.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(chunk.whyLine(plan.today, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Its first step (AI-15); the rest on its sheet.
                micro?.first?.takeIf { it.isNotEmpty() }?.let { Text("First: $it", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
            }
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
    }
}

/** A slot on record: kept struck through, missed said so. */
@Composable
private fun RecordedRow(record: SlotRecord, zone: ZoneId) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${clock(record.start, zone)}–${clock(record.end, zone)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(
            record.label,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (record.outcome.kept) TextDecoration.LineThrough else null,
            color = if (record.outcome == SlotOutcome.Missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(outcome(record), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Free time between two slots, muted. */
@Composable
private fun GapRow(row: DayModel.Row.Gap) {
    Text("Free, ${Format.minutes(row.minutes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
}

/** The moment it is: a thin line in the logo's pink. */
@Composable
private fun NowRow(at: Long, zone: ZoneId) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Now ${clock(at, zone)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.weight(1f), thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * An event's sheet (UI-17, UI-11, moved from the Calendar page): when and where, what it says, how
 * the plan counts it, its answers (for every event of that name), and an instruction about it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventSheet(graph: AppGraph, activity: Activity, event: CalendarEvent, answers: Map<String, String>, instructed: Set<String>, now: Long, zone: ZoneId, onClose: () -> Unit) {
    val key = EventJudge.key(event)
    var writing by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(event.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (event.allDay) "All day" else "${Format.at(event.start, now, zone)} to ${clock(event.end, zone)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (event.location.isNotBlank()) Text(event.location, style = MaterialTheme.typography.bodyMedium)
            if (event.description.isNotBlank()) Text(event.description.take(600), style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis)
            val judgement = EventJudge.judge(event, answers)
            Text("The plan counts it: ${verdict(judgement, false).lowercase()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            if (key in instructed) {
                // Your instruction says how it counts, over any answer here: it's taken back where it was made.
                Text("Your instruction says how it counts: take it back in the chat's Your changes to answer here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CalendarTime.ANSWERS.forEach { (label, answer) ->
                        OutlinedButton(onClick = { graph.scope.launch { graph.calendarWriter.answer(key, answer) } }) { Text(label) }
                    }
                }
                Text("For every event of that name.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { writing = true }) { Text("An instruction about it") }
        }
    }
    if (writing) {
        InstructionDialog("About “${event.title}” (every event of that name)", onDismiss = { writing = false }) { text ->
            writing = false
            sendInstruction(activity, text, About(eventKey = key, eventTitle = event.title, eventStart = event.start))
        }
    }
}
