package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.SettingsLimits
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.enrich.MicroPlan
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The gutter down the grid's left, the hours in it; the header's date sits in its width. */
internal val GUTTER: Dp = 56.dp

/** The least a block is drawn, so a short one can still be read, tapped and swiped. */
private val MIN_BLOCK: Dp = 24.dp

private val GRID_CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

private fun gridClock(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).format(GRID_CLOCK)

/** What the grid's blocks do, handed in by the Day: as its list's rows did. */
internal class DayActions(
    val onOpen: (Chunk) -> Unit,
    val onEvent: (CalendarEvent) -> Unit,
    val onDone: (Chunk) -> Unit,
    val onLater: (Chunk) -> Unit,
    val onMove: (Chunk) -> Unit,
    val onDrop: () -> Unit,
    /** A missed slot tapped: its sheet. */
    val onMissed: (SlotRecord) -> Unit,
    /** Whether a missed slot's sheet has a Done just now ([MissedActions]), which TalkBack offers on its block too. */
    val canKeep: (DayModel.Row.Recorded) -> Boolean,
)

/**
 * The day as a calendar (UI-17, 4.6.0): 00:00 to 24:00 down a gutter of hours, its windows as bands behind (school's tinted,
 * the hours outside every window shaded), its blocks by time side by side ([DayLayout.columns]), and today's moment as a line.
 * It scrolls on its own; two fingers pinch it between 40 and 120 dp an hour about the point between them, saved ([onZoom]) as
 * the pinch ends. A piece or a break outside held is dragged ([drag]).
 */
@Composable
internal fun DayGrid(
    model: DayModel,
    plan: Plan,
    zone: ZoneId,
    hourDp: Int,
    scroll: ScrollState,
    judge: (CalendarEvent) -> EventJudge.Judgement,
    asked: Collection<String>,
    micro: Map<String, MicroPlan>,
    messageOf: (String) -> String?,
    drag: DayDrag,
    actions: DayActions,
    onZoom: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // The hour's height, live during a pinch; the setting's as it's saved.
    var hour by remember { mutableFloatStateOf(hourDp.toFloat()) }
    LaunchedEffect(hourDp) { hour = hourDp.toFloat() }
    val hourPx = with(density) { hour.dp.toPx() }
    // A drop is judged at the hour the grid's drawn at.
    SideEffect { drag.hourPx = hourPx }
    // Where a pinch would have the grid scrolled, kept till the grid's laid out at its new height: scrolled now, it would be
    // held to the old height's end.
    var anchored by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(hour) {
        anchored?.let { target ->
            scroll.scrollTo(target.roundToInt())
            anchored = null
        }
    }
    val latestZoom by rememberUpdatedState(onZoom)
    // Where it opens: today an hour before now; another day half an hour before its first window or block.
    LaunchedEffect(model.date) {
        val minute = model.now?.let { DayLayout.minuteOf(it, model.date, zone) - 60 } ?: model.opensAt
        scroll.scrollTo(DayLayout.yOf(minute.coerceAtLeast(0), hourPx).roundToInt())
    }
    val shade = MaterialTheme.colorScheme.surfaceContainer
    val surface = MaterialTheme.colorScheme.surface
    val school = MaterialTheme.colorScheme.secondaryContainer
    val line = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier.clipToBounds()
            .onGloballyPositioned { coordinates ->
                val top = coordinates.positionInRoot().y
                drag.gridTop = top
                // Its bottom as far as it's seen: on its side the grid runs on below the screen till what's above it has gone.
                drag.gridBottom = minOf(top + coordinates.size.height, coordinates.boundsInRoot().bottom)
            }
            // Two fingers pinch the hour's height, about the point between them; one only scrolls.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pinched = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val before = hour.dp.toPx()
                                val minute = ((anchored ?: scroll.value.toFloat()) + centroid.y) * 60f / before
                                hour = (hour * zoom).coerceIn(SettingsLimits.DAY_HOUR_DP.first.toFloat(), SettingsLimits.DAY_HOUR_DP.last.toFloat())
                                anchored = (minute * hour.dp.toPx() / 60f - centroid.y).coerceAtLeast(0f)
                                pinched = true
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    // Settled on a whole height as it's saved, so the grid draws what the setting holds.
                    if (pinched) {
                        hour = hour.roundToInt().toFloat()
                        latestZoom(hour.roundToInt())
                    }
                }
            },
    ) {
        Box(
            Modifier.fillMaxWidth().verticalScroll(scroll).height((hour * 24).dp + 96.dp)
                .drawBehind {
                    val left = GUTTER.toPx()
                    // The hours outside every window shaded; the windows' bands over it, school's tinted.
                    drawRect(shade, Offset(left, 0f), Size(size.width - left, hourPx * 24))
                    for (w in model.windows) {
                        val top = DayLayout.yOf(w.window.startMin, hourPx)
                        val bottom = DayLayout.yOf(w.window.endMin, hourPx)
                        drawRect(if (w.window.kind.atSchool) school else surface, Offset(left, top), Size(size.width - left, bottom - top))
                    }
                    for (h in 1 until 24) drawLine(line, Offset(left, h * hourPx), Offset(size.width, h * hourPx), strokeWidth = 1f)
                },
        ) {
            // The hours down the gutter, every hour but midnight's, as the app writes times.
            for (h in 1 until 24) {
                Text(
                    "%02d:00".format(Locale.UK, h),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(x = 8.dp, y = (h * hour).dp - 8.dp),
                )
            }
            // Each window's name at its top.
            for (w in model.windows) {
                Text(
                    windowName(w.window.kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(x = GUTTER + 4.dp, y = (w.window.startMin * hour / 60f).dp + 2.dp),
                )
            }
            BoxWithConstraints(Modifier.padding(start = GUTTER, end = 4.dp).fillMaxWidth().height((hour * 24).dp)) {
                val width = maxWidth
                val minMinutes = (MIN_BLOCK.value * 60f / hour).roundToInt()
                val minutes = model.blocks.map { DayLayout.minuteOf(it.start, model.date, zone) to DayLayout.minuteOf(it.end, model.date, zone) }
                val placed = DayLayout.columns(minutes, minMinutes)
                model.blocks.forEachIndexed { i, block ->
                    // Each block's state (its swipe, where it is) its own, by its key, not its place in the day.
                    key(blockKey(block)) {
                        val (start, end) = minutes[i]
                        val at = placed[i]
                        val column = width / at.columns
                        val height = maxOf(((end - start) * hour / 60f).dp, MIN_BLOCK)
                        Box(Modifier.offset(x = column * at.column, y = (start * hour / 60f).dp).width(column * at.span).height(height).padding(1.dp)) {
                            GridBlock(block, plan, zone, judge, asked, micro, messageOf, drag, actions, column * at.span)
                        }
                    }
                }
                // Where a dragged piece or break would go: a ghost at its snapped time, with its times.
                if (drag.active) {
                    val minute = drag.minuteOnGrid(scroll.value.toFloat(), hourPx)
                    val length = drag.minutes
                    if (minute != null) {
                        val top = DayLayout.dropStart(minute, length)
                        val start = Moves.timeOf(model.date, top, zone)
                        Box(
                            Modifier.offset(y = (top * hour / 60f).dp).fillMaxWidth().height(maxOf((length * hour / 60f).dp, MIN_BLOCK))
                                .border(2.dp, primary, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("${gridClock(start, zone)}–${gridClock(start + length * 60_000L, zone)}", style = MaterialTheme.typography.labelMedium, color = primary)
                        }
                    }
                }
            }
            // Today's moment: a line across in the primary colour, a dot at the gutter's edge.
            model.now?.let { now ->
                val y = (DayLayout.minuteOf(now, model.date, zone) * hour / 60f).dp
                Box(Modifier.offset(x = GUTTER - 5.dp, y = y - 5.dp).size(10.dp).clip(CircleShape).background(primary))
                Box(Modifier.offset(x = GUTTER, y = y - 1.dp).fillMaxWidth().height(2.dp).background(primary))
            }
        }
    }
}

/** A window's name, as its band says it. */
internal fun windowName(kind: WindowKind): String = when (kind) {
    WindowKind.Evening -> "Your time"
    WindowKind.Study -> "Study period"
    WindowKind.Break -> "Break"
    WindowKind.Lunch -> "Lunch"
}

/** A block of the grid, as its kind draws it (`docs/spec/day.md` §The blocks). */
@Composable
private fun GridBlock(block: DayModel.Block, plan: Plan, zone: ZoneId, judge: (CalendarEvent) -> EventJudge.Judgement, asked: Collection<String>, micro: Map<String, MicroPlan>, messageOf: (String) -> String?, drag: DayDrag, actions: DayActions, width: Dp) {
    // A block under 40 dp wide shows its first word only.
    fun fit(text: String) = if (width < 40.dp) text.substringBefore(' ') else text
    when (val item = block.item) {
        is DayModel.Row.Planned -> {
            val chunk = item.slot.chunk
            if (item.slot.kind == SlotKind.Outside || chunk == null) {
                OutsideBlock(item, zone, drag, actions, ::fit)
            } else {
                PieceBlock(item, chunk, plan, zone, micro[chunk.key], messageOf(chunk.taskId), drag, actions, width, ::fit)
            }
        }
        is DayModel.Row.Event -> EventBlock(item.event, judge(item.event), EventJudge.key(item.event) in asked, zone, actions, ::fit)
        is DayModel.Row.Recorded -> RecordBlock(item, zone, actions, ::fit)
        is DayModel.Row.Busy -> BusyBlockView(item, ::fit)
        is DayModel.Row.Window -> Unit
    }
}

/**
 * A piece of the plan's (`docs/spec/day.md` §The blocks): its label and times, then as its height allows its reason, its
 * first step and the last thing said of its task; urgent, a red edge; a ✓ where it's 32 dp tall and 120 wide. Tapped, its sheet;
 * swiped right to left, done; left to right, later today (UI-03); held, dragged (UI-04). For TalkBack, *Done*, *Later today* and
 * *Move it*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PieceBlock(item: DayModel.Row.Planned, chunk: Chunk, plan: Plan, zone: ZoneId, micro: MicroPlan?, message: String?, drag: DayDrag, actions: DayActions, width: Dp, fit: (String) -> String) {
    val slot = item.slot
    val now = item.state == DayModel.State.Now
    val today = slot.start >= plan.today.atStartOfDay(zone).toInstant().toEpochMilli() && slot.start < plan.today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val swipe = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.4f })
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // The block's slot and the Day's actions as they now are: the drag's handler outlives a recomposition.
    val held by rememberUpdatedState(slot)
    val heldChunk by rememberUpdatedState(chunk)
    val current by rememberUpdatedState(actions)
    val container = if (now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val content = if (now) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
    val times = "${gridClock(slot.start, zone)}–${gridClock(slot.end, zone)}" + when (item.state) {
        DayModel.State.Now -> " · now"
        DayModel.State.Gone -> " · gone by"
        DayModel.State.Ahead -> ""
    }
    SwipeToDismissBox(
        state = swipe,
        modifier = Modifier.fillMaxSize(),
        // Later today is today's: another day's piece is held to move it.
        enableDismissFromStartToEnd = today,
        backgroundContent = {
            val done = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = if (done) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                Text(if (done) "Done" else "Later today", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        onDismiss = { value ->
            // Back in place: the block goes when the plan's made again.
            scope.launch { swipe.reset() }
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> actions.onDone(chunk)
                SwipeToDismissBoxValue.StartToEnd -> actions.onLater(chunk)
                else -> Unit
            }
        },
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(container)
                .alpha(if (item.state == DayModel.State.Gone || drag.key == slot.key) 0.5f else 1f)
                .onGloballyPositioned { coordinates ->
                    origin = coordinates.positionInRoot()
                    size = coordinates.size
                }
                .semantics {
                    contentDescription = "${gridClock(slot.start, zone)} to ${gridClock(slot.end, zone)}, ${chunk.label}"
                    customActions = listOfNotNull(
                        CustomAccessibilityAction("Done") { actions.onDone(chunk); true },
                        CustomAccessibilityAction("Later today") { actions.onLater(chunk); true }.takeIf { today },
                        CustomAccessibilityAction("Move it") { actions.onMove(chunk); true },
                    )
                }
                .clickable { actions.onOpen(chunk) }
                .pointerInput(slot.key, slot.start) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            drag.start(held, size, origin + offset, offset, heldChunk.minutes)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            drag.finger += amount
                        },
                        onDragEnd = { current.onDrop() },
                        onDragCancel = { drag.cancel() },
                    )
                },
        ) {
            val tall = maxHeight >= 32.dp && maxWidth >= 120.dp
            Row(Modifier.fillMaxSize()) {
                // Urgent: a red edge on its left.
                if (chunk.urgent) Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.error))
                Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 2.dp).clipToBounds()) {
                    Text(fit(chunk.label), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(times, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1)
                    Text(chunk.whyLine(plan.today, zone), style = MaterialTheme.typography.bodySmall, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    // Its first step (AI-15); the rest on its sheet.
                    micro?.first?.takeIf { it.isNotEmpty() }?.let { Text("First: $it", style = MaterialTheme.typography.bodySmall, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                if (tall) Text("✓", style = MaterialTheme.typography.titleMedium, color = content, modifier = Modifier.clickable { actions.onDone(chunk) }.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

/** A break outside: a pale strip, "Outside"; held, dragged up and down its own day, but not under way (D-113, D-116). */
@Composable
private fun OutsideBlock(item: DayModel.Row.Planned, zone: ZoneId, drag: DayDrag, actions: DayActions, fit: (String) -> String) {
    val slot = item.slot
    val haptic = LocalHapticFeedback.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // As a piece's: a break's key names its own time, the same on any day, so its handler goes by its start too.
    val held by rememberUpdatedState(slot)
    val current by rememberUpdatedState(actions)
    val movable = item.state == DayModel.State.Ahead
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f))
            .alpha(if (drag.key == slot.key) 0.5f else 1f)
            .onGloballyPositioned { coordinates ->
                origin = coordinates.positionInRoot()
                size = coordinates.size
            }
            .semantics { contentDescription = "${gridClock(slot.start, zone)} to ${gridClock(slot.end, zone)}, outside" }
            .then(
                if (!movable) Modifier else Modifier.pointerInput(slot.key, slot.start) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            drag.start(held, size, origin + offset, offset, ((held.end - held.start) / 60_000L).toInt())
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            drag.finger += amount
                        },
                        onDragEnd = { current.onDrop() },
                        onDragCancel = { drag.cancel() },
                    )
                },
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(fit("Outside"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer, maxLines = 1)
    }
}

/**
 * An event: in its colour (its own, else its calendar's), its words black or white, whichever reads better on it; outlined only
 * where the plan counts it free; a small "?" where the plan needs your answer about it. Tapped, its sheet.
 */
@Composable
private fun EventBlock(event: CalendarEvent, judgement: EventJudge.Judgement, asked: Boolean, zone: ZoneId, actions: DayActions, fit: (String) -> String) {
    val colour = event.colour?.let { Color(it) } ?: MaterialTheme.colorScheme.secondaryContainer
    val words = event.colour?.let { Colours.onEvent(it) } ?: MaterialTheme.colorScheme.onSecondaryContainer
    val free = judgement == EventJudge.Judgement.Free
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
            .then(if (free) Modifier.border(1.dp, colour, RoundedCornerShape(8.dp)) else Modifier.background(colour))
            .clickable { actions.onEvent(event) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fit(event.title.ifBlank { "(no title)" }), style = MaterialTheme.typography.labelLarge, color = if (free) MaterialTheme.colorScheme.onSurface else words, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (judgement == EventJudge.Judgement.Ask && !asked) Text(" ?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (free) MaterialTheme.colorScheme.onSurface else words)
            }
            Text("${gridClock(event.start, zone)}–${gridClock(event.end, zone)}", style = MaterialTheme.typography.labelSmall, color = if (free) MaterialTheme.colorScheme.onSurfaceVariant else words, maxLines = 1)
        }
    }
}

/** A slot on record: kept, grey and struck through; missed, outlined and worded in the error colour, its sheet a tap away. */
@Composable
private fun RecordBlock(item: DayModel.Row.Recorded, zone: ZoneId, actions: DayActions, fit: (String) -> String) {
    val record = item.record
    val missed = record.outcome == SlotOutcome.Missed
    // For TalkBack, its Done offered on the block, as a piece's is.
    val keeps = item.piece?.takeIf { actions.canKeep(item) }
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
            .then(if (missed) Modifier.border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp)) else Modifier.background(MaterialTheme.colorScheme.surfaceVariant))
            .then(
                if (missed) {
                    Modifier.semantics { if (keeps != null) customActions = listOf(CustomAccessibilityAction("Done") { actions.onDone(keeps); true }) }
                        .clickable { actions.onMissed(record) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Column {
            Text(
                fit(record.label),
                style = MaterialTheme.typography.labelMedium,
                textDecoration = if (record.outcome.kept) TextDecoration.LineThrough else null,
                color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${gridClock(record.start, zone)}–${gridClock(record.end, zone)} · ${outcomeWords(record.outcome)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** A busy time of yours (D-117), as your calendar has it: "Busy: Football", or "Busy". */
@Composable
private fun BusyBlockView(item: DayModel.Row.Busy, fit: (String) -> String) {
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(fit(item.block.title?.let { "Busy: $it" } ?: "Busy"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A slot's outcome, in a word or two. */
internal fun outcomeWords(outcome: SlotOutcome): String = when (outcome) {
    SlotOutcome.Kept -> "Kept"
    SlotOutcome.KeptLate -> "Kept late"
    SlotOutcome.Missed -> "Missed"
    SlotOutcome.Moved -> "Moved"
}
