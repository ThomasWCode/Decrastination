package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How soon a second Done of the same block is the same swipe seen again (UI-03). */
private const val SWIPE_AGAIN_MS = 3_000L

/** The sheets the header's chips open, each holding what the Day's list held under its rows. */
private enum class DaySheet { WontFit, NoTime, Done, Said }

/**
 * Today's DO NOW card's fold as a drag on the card reaches it: once it's folding, taken before what the card holds moves, so
 * it folds from its top down; before that, only what's left once what it holds has scrolled to its end. [at] says how far
 * it's folded; [by] folds it by what it can of a scroll and says what it took; [settle], given the fling's speed as it folds,
 * settles a fold the drag moved and says whether there was one to settle.
 */
internal class Folding(private val at: () -> Float, private val by: (Float) -> Float, private val settle: (Float) -> Boolean = { false }) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (at() > 0f) Offset(0f, by(available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = Offset(0f, by(available.y))

    // Let go, a drag that moved the fold settles it and takes the fling; one that only scrolled what the card holds flings that.
    override suspend fun onPreFling(available: Velocity): Velocity = if (settle(-available.y)) available else Velocity.Zero

    // A fling that folded the card once what it holds reached its end settles it as it stops.
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = if (settle(-available.y)) available else Velocity.Zero
}

/**
 * The Day (UI-17, 4.6.0): the week ahead in a strip, each day with its load (UI-02) and a mark where work is behind; today's
 * DO NOW card, folding to a line as the grid scrolls on to later hours; the chosen day's header (its date, load, all-day events,
 * and chips for what won't fit, the pieces with no time, what's done and its instructions); and the day as a calendar
 * ([DayGrid]), a piece held to be dragged to a time or onto a day above ([DayDrag]). What the plan leaves out, and the
 * sources, are on Work.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    // How the last action went, and the task it's about.
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

    // The day chosen in the strip (today's by default), a piece's sheet, a task's, an event's, a chip's.
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val day = chosen?.let(LocalDate::parse)?.takeIf { it >= plan.today && it <= plan.today.plusDays(WEEK - 1) } ?: plan.today
    val today = day == plan.today
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    // The last Done by swipe, so the block's dismissal seen again as it's put back keeps nothing and says nothing.
    var lastDone by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var changing by rememberSaveable { mutableStateOf<String?>(null) }
    var eventFor by rememberSaveable { mutableStateOf<String?>(null) }
    // A piece being moved by the sheet (UI-04, TalkBack's Move it), by its key; an instruction about the day being written.
    var moving by rememberSaveable { mutableStateOf<String?>(null) }
    var writingDay by rememberSaveable { mutableStateOf(false) }
    var daySheet by rememberSaveable { mutableStateOf<DaySheet?>(null) }
    val answers = asked.eventAnswers + InstructionRules.eventAnswers(instructions.applied)
    val model = remember(plan, day, calendar, log, school, settings, tasks, instructions) {
        DayModel.from(
            plan, day, calendar.events, log.slots, SchoolDays.windows(day, settings, tasks.timetable, school), now, zone,
            busy = InstructionRules.busyBlocks(instructions.applied, day, day),
        )
    }
    val snackbar = remember { SnackbarHostState() }
    val grid = rememberScrollState()
    // On its side the Day scrolls as one, [page] its scroll (Q94).
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val page = rememberScrollState()
    val drag = remember { DayDrag() }
    val density = LocalDensity.current

    // While a piece is dragged near the grid's top or bottom, the grid scrolls, up to 12 dp a frame. On its side, held at or
    // above the grid's top once that's at midnight, the Day's page comes back, the strip with it, so a day can be dropped on.
    LaunchedEffect(drag.active) {
        val edge = with(density) { 48.dp.toPx() }
        val step = with(density) { 12.dp.toPx() }
        while (drag.active) {
            val y = drag.finger.y
            when {
                drag.dayUnder() != null -> Unit
                landscape && grid.value == 0 && y < drag.gridTop + edge && page.value > 0 -> page.dispatchRawDelta(-step)
                y in drag.gridTop..(drag.gridTop + edge) -> grid.dispatchRawDelta(-step * (1f - (y - drag.gridTop) / edge))
                y in (drag.gridBottom - edge)..drag.gridBottom -> grid.dispatchRawDelta(step * (1f - (drag.gridBottom - y) / edge))
            }
            withFrameNanos { }
        }
    }

    // A drop, judged on the plan as it then is (UI-04): onto a day above, all of its task there; on the grid, its task's next
    // piece at that time, or a break outside at that time on its own day (D-116); anywhere else, nothing.
    fun drop() {
        val slot = drag.slot ?: return
        val onto = drag.dayUnder()
        val minute = drag.minuteOnGrid(grid.value.toFloat(), drag.hourPx.takeIf { it > 0f } ?: with(density) { settings.dayHourDp.dp.toPx() })
        val moved: Moves.Moved? = if (slot.kind == SlotKind.Outside || slot.chunk == null) {
            when {
                onto != null -> Moves.Moved.Refused("A break outside stays on its day")
                minute != null -> Moves.outsideTo(graph, slot, day, DayLayout.dropStart(minute, drag.minutes), zone)
                else -> null
            }
        } else {
            val chunk = plan.ordered.firstOrNull { it.key == slot.chunk?.key }
            val task = tasks.tasks.firstOrNull { it.id == chunk?.taskId }
            when {
                chunk == null -> Moves.Moved.Refused("That piece has changed since (done, or planned again).")
                onto != null && onto == day -> null
                onto != null && !Moves.dayAllowed(task, onto, graph.clock.now(), zone) -> Moves.Moved.Refused("Not that day: it's due before then, or can't be started till later")
                onto != null -> Moves.toDay(graph, chunk, onto, zone)
                minute == null -> null
                plan.chunksOf(chunk.taskId).firstOrNull()?.key != chunk.key -> Moves.Moved.Refused("Only its next piece takes a time: drag it onto a day above")
                else -> Moves.toTime(graph, chunk, day, DayLayout.dropStart(minute, chunk.minutes), zone)
            }
        }
        when (moved) {
            is Moves.Moved.Done -> {
                drag.cancel()
                scope.launch { snackbar.showSnackbar("${moved.said}: undone in the chat's Your changes") }
            }
            is Moves.Moved.Refused -> {
                scope.launch { drag.springBack() }
                scope.launch { snackbar.showSnackbar(moved.why) }
            }
            null -> scope.launch { drag.springBack() }
        }
    }

    val actions = DayActions(
        onOpen = { sheetFor = it.key },
        onEvent = { eventFor = eventKey(it) },
        // Done, with an Undo while it's said (UI-03).
        onDone = { chunk ->
            // One swipe, one Done.
            val at = SystemClock.elapsedRealtime()
            if (lastDone?.let { (key, t) -> key == chunk.key && at - t < SWIPE_AGAIN_MS } != true) {
                lastDone = chunk.key to at
                scope.launch {
                    val since = graph.clock.now()
                    if (!graph.focus.keepSlot(chunk.key, seenAt = now)) {
                        snackbar.showSnackbar("That piece has changed since (done, or planned again).")
                    } else if (snackbar.showSnackbar(listOfNotNull("Done: ${chunk.label}", stillPlanned(graph, chunk.taskId)).joinToString(". "), actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                        if (!graph.focus.unkeepSlot(chunk.key, since)) snackbar.showSnackbar("It can't be taken back now: its task is finished.")
                    }
                }
            }
        },
        onLater = { chunk ->
            if (today) {
                scope.launch {
                    if (plan.chunksOf(chunk.taskId).firstOrNull()?.key != chunk.key) {
                        // A time pins its task's next piece: this one goes by its day.
                        snackbar.showSnackbar("Only its next piece takes a time: hold it to move all of it to a day")
                    } else {
                        val at = graph.instructions.laterToday(chunk.key)
                        snackbar.showSnackbar(at?.let { "Later today, at ${clock(it, zone)}: undone in the chat's Your changes" } ?: "No time left today it can be done in")
                    }
                }
            }
        },
        onMove = { moving = it.key },
        onDrop = ::drop,
    )

    // Today's DO NOW card shows in full, up to the whole Day under the strip (Q96). It folds into a line as it's dragged up, or
    // as the day's header is, and unfolds as the header's dragged down, wherever the grid's scrolled, as it's dragged down
    // while part-folded, or at a tap on the folded line; the grid's own scrolls only move its hours (your word, replacing
    // Q97). Let go part-folded, it settles folded or unfolded, as two screens; the Day opens on its calendar, the card folded
    // (your word, 4.9.1).
    var cardFull by remember { mutableIntStateOf(0) }
    // The card's cap (below) in pixels, as last laid out: the card folds from what it shows unfolded, its height or that cap.
    var cardCap by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val line = with(density) { 40.dp.toPx() }
    var fold by remember(landscape) { mutableFloatStateOf(Float.MAX_VALUE) }
    val foldMax = DayLayout.foldMax(cardFull.toFloat(), cardCap, line)
    // A flick fast enough to settle the fold its way, and how far a slower drag moves it to settle it on that way.
    val flick = with(density) { 125.dp.toPx() }
    val nudge = with(density) { 24.dp.toPx() }
    // Where the fold was as the drag moving it began, while it's between unfolded and its line; and its settling, if it is.
    var foldStart by remember { mutableStateOf<Float?>(null) }
    var settling by remember { mutableStateOf<Job?>(null) }
    // The fold takes what it can of a scroll [dy], and says what it took: a drag takes it from a settling under way.
    fun foldBy(dy: Float): Float {
        if (cardFull == 0 || dy == 0f) return 0f
        settling?.cancel()
        val most = DayLayout.foldMax(cardFull.toFloat(), cardCap, line)
        val was = fold.coerceAtMost(most)
        fold = DayLayout.folded(was, dy, cardFull.toFloat(), cardCap, line)
        // Unfolded, or at its line (where the card under the finger goes), there's nothing to settle.
        foldStart = if (fold <= 0f || fold >= most) null else foldStart ?: was
        return was - fold
    }
    // Let go at [velocity] (folding, positive), a fold the drag left between settles at its line or unfolded; says if it did.
    fun settle(velocity: Float): Boolean {
        val start = foldStart ?: return false
        foldStart = null
        val most = DayLayout.foldMax(cardFull.toFloat(), cardCap, line)
        val to = DayLayout.settled(fold, start, velocity, most, flick, nudge)
        settling = scope.launch { animate(fold.coerceAtMost(most), to, velocity) { value, _ -> fold = value.coerceIn(0f, most) } }
        return true
    }
    // The fold set outright (the line's tap, TalkBack's Fold it), taken from a settling under way, which would set it back.
    fun foldTo(to: Float) {
        settling?.cancel()
        fold = to
    }
    val cardFolding = remember { Folding({ fold }, ::foldBy, ::settle) }
    // The day's header dragged, or flicked, moves the fold itself: up folds the card, down pushes the hours down.
    val pushing = rememberDraggableState { delta -> foldBy(delta) }

    var rootAt by remember { mutableStateOf(Offset.Zero) }
    val card = rememberScrollState()
    // On its side there's no room for the grid under what's above it, so the Day scrolls as one (Q94): what's above goes first
    // as the grid scrolls on to later hours, and slides back in first as it scrolls back (Q95).
    val collapsing = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                Offset(0f, -page.dispatchRawDelta(-available.y))
        }
    }
    // What's above the card, measured: the hints and the strip.
    var aboveCard by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { rootAt = it.positionInRoot() }) {
        // The card shows in full up to the whole Day under the strip (Q96), scrolling within that, the day's header and the
        // grid going on under the screen's edge till it folds; only its line where there's no room for it.
        val cap = maxHeight - with(density) { aboveCard.toDp() }
        SideEffect { cardCap = with(density) { cap.toPx() } }
        // The Day's height: on its side, the grid's.
        val tall = maxHeight
        Column(if (landscape) Modifier.fillMaxSize().verticalScroll(page) else Modifier.fillMaxSize().clipToBounds()) {
            Column(Modifier.onSizeChanged { aboveCard = it.height }) {
                Hint(graph, "day", "Day is when: today's slots as they come, and the week's days above. Work is what: every task, on the next tab.")
                Hint(graph, "day-drag", "Hold a piece to move it: drag it to a time, or onto a day above.")
                WeekStrip(plan, day, drag, tasks.tasks.associateBy { it.id }, graph.clock.now(), zone) { chosen = it.toString() }
            }
            if (today) {
                val unread = Source.SYNCED.filter { tasks.status(it).lastSuccessAt == null }
                // With no room for the card, or on its side (Q92), it stays its line, and a tap on the line opens the piece's
                // sheet (the card's actions) instead of unfolding it.
                val roomless = landscape || cap < 40.dp
                // Unmeasured, it's folded only as the Day opens folded, till then shown to be measured.
                val folded = (fold >= foldMax - 1f && (cardFull > 0 || fold == Float.MAX_VALUE)) || roomless
                val nextCard: @Composable () -> Unit = {
                    NextCard(
                        graph, activity, plan, zone, photo, ::messageOf, say,
                        empty = if (unread.isNotEmpty()) "Nothing planned yet: ${unread.joinToString { it.label }} not read yet." else "No work is planned. New tasks appear as the sources are read.",
                        // For TalkBack, folded by an action: while the card fills the Day, the grid has no room to be reached.
                        actions = listOf(CustomAccessibilityAction("Fold it") { foldTo(Float.MAX_VALUE); true }),
                    )
                }
                if (folded) {
                    val doing = plan.slotAt(plan.now)?.chunk ?: plan.next
                    val label = doing?.label ?: "nothing planned"
                    Text(
                        "DO NOW · $label",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().height(40.dp).clickable(enabled = !roomless || doing != null) { if (roomless) sheetFor = doing?.key else foldTo(0f) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    // Folded as the Day opens, the card's measured once out of sight, so the header dragged down unfolds it under
                    // the finger.
                    if (cardFull == 0 && !roomless) {
                        Box(Modifier.fillMaxWidth().height(0.dp).clipToBounds().clearAndSetSemantics { }) {
                            Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { cardFull = it.height }) { nextCard() }
                        }
                    }
                } else {
                    val shown = if (cardFull == 0) cap else minOf(with(density) { (minOf(cardFull.toFloat(), cardCap) - fold.coerceAtMost(foldMax)).toDp() }, cap)
                    Box(Modifier.fillMaxWidth().heightIn(max = shown).nestedScroll(cardFolding).verticalScroll(card)) {
                        Box(Modifier.fillMaxWidth().onSizeChanged { cardFull = it.height }) { nextCard() }
                    }
                }
            }
            // Laid at its own height, running on under the screen's edge rather than squeezed, while the card fills the Day; on
            // today, upright, dragged to fold the card or push the hours down, wherever they're scrolled.
            Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).then(if (today && !landscape) Modifier.draggable(pushing, Orientation.Vertical, onDragStopped = { velocity -> settle(-velocity) }) else Modifier)) {
                DayHeader(model, plan, day, today, zone, instructions.applied.flatMap { it.changes }.count { c -> saysOf(c.type) && (c.date == day.toString() || (c.date == null && c.weekday == day.dayOfWeek.value)) }) { daySheet = it }
                model.allDay.takeIf { it.isNotEmpty() }?.let { events ->
                    LazyRow(contentPadding = PaddingValues(start = GUTTER, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(events, key = { eventKey(it) }) { event ->
                            val colour = event.colour?.let { Color(it) } ?: MaterialTheme.colorScheme.secondaryContainer
                            AssistChip(
                                onClick = { eventFor = eventKey(event) },
                                label = { Text(event.title.ifBlank { "(no title)" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = colour, labelColor = event.colour?.let { Colours.onEvent(it) } ?: MaterialTheme.colorScheme.onSecondaryContainer),
                                border = null,
                            )
                        }
                    }
                }
            }
            DayGrid(
                model, plan, zone, settings.dayHourDp, grid, { EventJudge.judge(it, answers) }, asked.eventsAsked, written.microPlans,
                { messageOf(it) }, drag, actions,
                onZoom = { hourDp -> scope.launch { graph.protection.changeSettings { it.copy(dayHourDp = hourDp) } } },
                // Upright, the grid's scrolls move only its hours, never the card. On its side the grid is the Day's height, under
                // what's above it, and its scrolls take that away first.
                modifier = if (landscape) Modifier.height(tall).nestedScroll(collapsing) else Modifier.weight(1f),
            )
        }

        // The piece or break lifted, over the whole Day, following the finger.
        drag.slot?.let { slot ->
            val at = drag.finger - drag.grab - rootAt
            Card(
                modifier = Modifier.zIndex(1f).offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                    .size(with(density) { drag.size.width.toDp() }, with(density) { drag.size.height.toDp() })
                    .shadow(8.dp, RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = if (slot.kind == SlotKind.Outside) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer),
            ) {
                Text(slot.chunk?.label ?: "Outside", style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }

    // What the list held under its rows, now each a chip's sheet; its headings set in as far as its lines, each line padding itself.
    daySheet?.let { which ->
        ModalBottomSheet(onDismissRequest = { daySheet = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                when (which) {
                    DaySheet.WontFit -> plan.wontFit.forEachIndexed { index, short ->
                        WontFitTile(short, index, plan.wontFit.size, plan.now, zone) {
                            val text = "There isn't time for “${short.title}” by ${Format.at(short.deadline, plan.now, zone)}: ${Format.minutes(short.leftMin)} left, " +
                                "${Format.minutes(short.freeMin)} free. What should I shorten, move or ask for more time on?"
                            activity.startActivity(AskActivity.intent(activity, About(taskId = short.taskId, taskTitle = short.title), text, send = false))
                        }
                    }
                    DaySheet.NoTime -> {
                        Box(Modifier.padding(horizontal = 24.dp)) { SectionHeading("No time ${if (today) "today" else "that day"}", sheet = true) }
                        model.unslotted.forEachIndexed { index, chunk ->
                            ChunkTile(chunk, null, index, model.unslotted.size, plan.now, zone) {
                                daySheet = null
                                sheetFor = chunk.key
                            }
                        }
                    }
                    DaySheet.Done -> {
                        Box(Modifier.padding(horizontal = 24.dp)) { SectionHeading("Done ${if (today) "today" else "that day"}", trailing = Format.minutes(model.doneMin), sheet = true) }
                        model.done.forEachIndexed { index, record ->
                            ListTile(index, model.done.size) {
                                Text(record.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${outcomeWords(record.outcome)} · ${Format.minutes(record.workedMin)}" + if (record.earned) " · earned" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // Your instructions about the day, its limit, busy times and the tasks it's only for (D-115), and one more (UI-11).
                    DaySheet.Said -> {
                        Box(Modifier.padding(horizontal = 24.dp)) { SectionHeading("Instructions", sheet = true) }
                        val rules = instructions.applied.flatMap { it.changes }.filter { c ->
                            saysOf(c.type) && (c.date == day.toString() || (c.date == null && c.weekday == day.dayOfWeek.value))
                        }
                        val byId = tasks.tasks.associateBy { it.id }
                        rules.forEach { rule ->
                            Text(
                                "Your instruction: " + InstructionRules.describe(rule, byId, zone),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                            )
                        }
                        TextButton(onClick = {
                            daySheet = null
                            writingDay = true
                        }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("An instruction about this day…") }
                    }
                }
            }
        }
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

/** The instructions said of a day (UI-11): its limit, busy times, and the tasks it's only for (D-115). */
private fun saysOf(type: ChangeType) = type == ChangeType.DayLimit || type == ChangeType.BusyTime || type == ChangeType.DayOnly

/**
 * The chosen day's header, as Google's: its weekday over its date in the gutter's width (today's in a circle of the primary
 * colour); its name and load; and a row of chips for what the list showed under its rows, each opening its sheet: what won't
 * fit (today's, PL-02), the pieces with no time, what's done (UI-08), its instructions (UI-11); "Nothing planned." where it has
 * no slot, record or piece.
 */
@Composable
private fun DayHeader(model: DayModel, plan: Plan, day: LocalDate, today: Boolean, zone: ZoneId, said: Int, onSheet: (DaySheet) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(GUTTER), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(day.format(WEEKDAY), style = MaterialTheme.typography.labelMedium, color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                Modifier.size(36.dp).clip(CircleShape).then(if (today) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge, color = if (today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(dayName(day, plan.today), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(Format.load(model.plannedMin, model.freeMin), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (today && plan.wontFit.isNotEmpty()) {
                    AssistChip(
                        onClick = { onSheet(DaySheet.WontFit) },
                        label = { Text("${plan.wontFit.size} won't fit") },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.errorContainer, labelColor = MaterialTheme.colorScheme.onErrorContainer),
                        border = null,
                    )
                }
                if (model.unslotted.isNotEmpty()) AssistChip(onClick = { onSheet(DaySheet.NoTime) }, label = { Text("${model.unslotted.size} with no time") })
                if (model.done.isNotEmpty()) AssistChip(onClick = { onSheet(DaySheet.Done) }, label = { Text("${Format.minutes(model.doneMin)} done") })
                AssistChip(onClick = { onSheet(DaySheet.Said) }, label = { Text(if (said > 0) "Instructions · $said" else "Instruction…") })
            }
            if (model.blocks.none { it.item is DayModel.Row.Planned || it.item is DayModel.Row.Recorded } && model.unslotted.isEmpty()) {
                Text("Nothing planned.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** How many days the strip shows. */
private const val WEEK = 7L

private val SHORT_DAY = DateTimeFormatter.ofPattern("EEE d", Locale.UK)
private val LONG_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.UK)
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

private fun dayName(day: LocalDate, today: LocalDate) = when (day) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> day.format(LONG_DAY)
}

private fun clock(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).format(CLOCK)

private fun eventKey(event: CalendarEvent) = "${event.id}@${event.start}"

/**
 * The week ahead, a day a chip (UI-02): its name, its load as a bar, and a mark where work is behind. While a piece is dragged,
 * its days are drop targets ([drag]): the one under the finger outlined, those it can't go to dimmed, as the move sheet's chips
 * are (a break outside's all: it stays on its day).
 */
@Composable
private fun WeekStrip(plan: Plan, chosen: LocalDate, drag: DayDrag, tasks: Map<String, TaskItem>, now: Long, zone: ZoneId, onPick: (LocalDate) -> Unit) {
    val dragged: Chunk? = drag.slot?.chunk
    val under = if (drag.active) drag.dayUnder() else null
    LazyRow(Modifier.padding(top = 12.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items((0L until WEEK).map { plan.today.plusDays(it) }, key = { it.toString() }) { date ->
            val bucket = plan.bucket(date)
            val planned = bucket?.plannedMin ?: 0
            val free = bucket?.capacityMin ?: 0
            val over = planned > free
            val dimmed = drag.active && (dragged == null || !Moves.dayAllowed(tasks[dragged.taskId], date, now, zone))
            // Its bounds go with it: a day the strip no longer shows (midnight passed) is no drop target.
            DisposableEffect(date) { onDispose { drag.chips.remove(date) } }
            Card(
                onClick = { onPick(date) },
                modifier = Modifier.width(76.dp)
                    .onGloballyPositioned { coordinates ->
                        val b = coordinates.boundsInRoot()
                        drag.chips[date] = DayLayout.Box(b.left, b.top, b.right, b.bottom)
                    }
                    .alpha(if (dimmed) 0.4f else 1f)
                    .then(if (under == date) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier),
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

/** How the plan counts an event, in a word or two. */
private fun verdict(judgement: EventJudge.Judgement, asked: Boolean): String = when (judgement) {
    EventJudge.Judgement.Free -> "Free"
    EventJudge.Judgement.Busy -> "Busy"
    is EventJudge.Judgement.Load -> "${Format.minutes(judgement.minutes)} of the day"
    EventJudge.Judgement.Ask -> if (asked) "Asked" else "Not known"
}

/**
 * An event's sheet (UI-17, UI-11): when and where, what it says, how
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
