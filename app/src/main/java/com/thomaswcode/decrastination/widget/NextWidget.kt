package com.thomaswcode.decrastination.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.ui.MainActivity
import com.thomaswcode.decrastination.ui.OpenTaskActivity

/**
 * The next thing to do, on the home screen (PLAN.md Phase 2): a "Do:" line, its badge and
 * minutes, a "Then:" line, and ↻. A tap opens the task where it lives. It grows from a card
 * holding the next thing at 2×1 to that card over the rest of today's list at 4×3 and up.
 */
class NextWidget : GlanceAppWidget() {

    /** Exact, so the list can fill whatever height the widget is given. */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = AppGraph.get(context)
        WidgetUpdater.scheduleRedraw(context, graph)
        provideContent {
            // Recomposes as the tasks or settings change while the widget's session lasts;
            // WidgetUpdater redraws it after that, and at midnight and deadlines.
            val tasks by graph.tasks.state.collectAsState()
            val settings by graph.settings.state.collectAsState()
            val runtime by graph.runtime.state.collectAsState()
            val model = WidgetModel.from(graph.plan(tasks, settings), tasks, graph.clock.zone(), runtime, settings.armed)
            GlanceTheme { Content(model) }
        }
    }

    /*
     * As the app is laid out: the next thing in a card of the accent colour, the rest of the day
     * as tiles a shade off the background with a sliver between each, urgent ones marked down
     * their edge, and trouble in the error colours, first. Small, the widget is the card itself.
     * A Glance column draws at most ten children: spacing is padding, and the list is one lazy
     * column, however long.
     */
    @Composable
    private fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val size = LocalSize.current
        val layout = WidgetLayout.of(size.width.value, size.height.value, WidgetLayout.Parts.of(model), context.resources.configuration.fontScale)
        val app = actionStartActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (!layout.roomy) {
            Column(
                GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp)
                    .background(GlanceTheme.colors.primaryContainer).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (layout.warning) model.warning?.let { Warning(it, app) }
                Next(model, layout)
                if (layout.summary) {
                    Text(
                        model.summary,
                        maxLines = 1,
                        modifier = GlanceModifier.padding(top = 4.dp).clickable(app),
                        style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp),
                    )
                }
            }
            return
        }
        Column(
            GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp)
                .background(GlanceTheme.colors.widgetBackground).padding(8.dp),
        ) {
            if (layout.warning) model.warning?.let { Warning(it, app) }
            Column(GlanceModifier.fillMaxWidth().cornerRadius(16.dp).background(GlanceTheme.colors.primaryContainer).padding(horizontal = 14.dp, vertical = 12.dp)) {
                Next(model, layout)
            }
            // The day's count heads the list, and opens the app, which has all of it.
            Text(
                model.summary,
                maxLines = 1,
                modifier = GlanceModifier.fillMaxWidth().padding(start = 8.dp, top = 10.dp, bottom = 6.dp).clickable(app),
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
            if (layout.listLines > 0 && model.list.isNotEmpty()) {
                LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight().cornerRadius(16.dp)) {
                    items(model.list) { line -> ListRow(line) }
                    if (model.more > 0) {
                        item {
                            Text(
                                "${model.more} more in the app",
                                maxLines = 1,
                                modifier = GlanceModifier.fillMaxWidth().background(GlanceTheme.colors.surfaceVariant)
                                    .padding(horizontal = 14.dp, vertical = 7.dp).clickable(app),
                                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            )
                        }
                    }
                }
            }
        }
    }

    /** The next thing: "DO NOW" over it where there's room, its badge and minutes as pills, then what comes after; ↻ beside. */
    @Composable
    private fun Next(model: WidgetModel, layout: WidgetLayout) {
        val context = LocalContext.current
        val open = actionStartActivity(OpenTaskActivity.intent(context, model.taskId))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight().clickable(open)) {
                val labelled = layout.label && model.label != null
                if (labelled) {
                    Text("DO NOW", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                }
                Text(
                    if (labelled) model.label!! else model.headline,
                    maxLines = layout.headlineLines,
                    style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 17.sp, fontWeight = FontWeight.Bold),
                )
                if (model.badge != null || model.minutes != null) {
                    when (layout.pills) {
                        WidgetLayout.Pills.Row -> Row(GlanceModifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            model.badge?.let { Pill(it, urgent = model.urgent) }
                            if (model.badge != null && model.minutes != null) Spacer(GlanceModifier.width(6.dp))
                            model.minutes?.let { Pill(it) }
                        }
                        WidgetLayout.Pills.Stacked -> {
                            model.badge?.let { Box(GlanceModifier.padding(top = 4.dp)) { Pill(it, urgent = model.urgent) } }
                            model.minutes?.let { Box(GlanceModifier.padding(top = 4.dp)) { Pill(it) } }
                        }
                        WidgetLayout.Pills.Plain -> Text(
                            listOfNotNull(model.badge, model.minutes).joinToString(" · "),
                            maxLines = 1,
                            modifier = GlanceModifier.padding(top = 2.dp),
                            style = TextStyle(
                                color = if (model.urgent) GlanceTheme.colors.error else GlanceTheme.colors.onPrimaryContainer,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
                if (layout.then) {
                    model.then?.let {
                        Text(it, maxLines = 1, modifier = GlanceModifier.padding(top = 4.dp), style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 13.sp))
                    }
                }
            }
            if (layout.refresh) {
                Spacer(GlanceModifier.width(8.dp))
                Image(
                    ImageProvider(R.drawable.ic_refresh),
                    contentDescription = "Sync now",
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    modifier = GlanceModifier.size(28.dp).clickable(actionRunCallback<RefreshAction>()),
                )
            }
        }
    }

    /** A row of the list as a tile: urgent work marked in red down its edge, its minutes in a pill. */
    @Composable
    private fun ListRow(line: WidgetModel.Line) {
        val context = LocalContext.current
        Box(GlanceModifier.fillMaxWidth().padding(bottom = 3.dp)) {
            Row(
                GlanceModifier.fillMaxWidth().background(GlanceTheme.colors.surfaceVariant).padding(horizontal = 10.dp, vertical = 6.dp)
                    .clickable(actionStartActivity(OpenTaskActivity.intent(context, line.taskId))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(GlanceModifier.width(4.dp).height(18.dp).cornerRadius(2.dp).background(if (line.urgent) GlanceTheme.colors.error else GlanceTheme.colors.outline)) {}
                Text(
                    line.text,
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight().padding(start = 10.dp, end = 8.dp),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = if (line.urgent) FontWeight.Medium else FontWeight.Normal),
                )
                Pill(line.minutes)
            }
        }
    }

    /**
     * A short value set apart, as the app's pills are; [urgent] in the error colours. The others
     * are in the surface colour, which stands apart from both the card and the tiles in light
     * mode as in dark (the widget's secondary container is barely a shade off its pale card).
     */
    @Composable
    private fun Pill(text: String, urgent: Boolean = false) {
        Text(
            text,
            maxLines = 1,
            modifier = GlanceModifier.cornerRadius(10.dp)
                .background(if (urgent) GlanceTheme.colors.errorContainer else GlanceTheme.colors.surface)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = TextStyle(
                color = if (urgent) GlanceTheme.colors.onErrorContainer else GlanceTheme.colors.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }

    /** What's wrong, set apart in the error colours, first; a tap opens the app, whose ⋮ menu leads to the fix. */
    @Composable
    private fun Warning(text: String, app: Action) {
        Box(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text(
                text,
                maxLines = 1,
                modifier = GlanceModifier.fillMaxWidth().cornerRadius(12.dp).background(GlanceTheme.colors.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 5.dp).clickable(app),
                style = TextStyle(color = GlanceTheme.colors.onErrorContainer, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * What fits at a widget's size, in dp (Glance gives the exact size), of what there is to show:
 * pure, so each size is tested. Small, the widget is a card holding the next thing and its
 * pills, then as there's height the "Then:" line, the "DO NOW" label, any warning, the day's
 * count, and a second line for the name; a row with nothing to show takes no room. Once two
 * rows of the list fit under the whole card, it's [roomy]: the card, the day's count as the
 * list's heading, and as much of today's list as fits.
 */
data class WidgetLayout(
    /** The card over the list, rather than the widget as the card. */
    val roomy: Boolean,
    val headlineLines: Int,
    /** "DO NOW" over the next thing's name, rather than "Do:" before it. */
    val label: Boolean,
    val refresh: Boolean,
    /** How the badge and minutes are set out, as the width allows. */
    val pills: Pills,
    val then: Boolean,
    val summary: Boolean,
    val warning: Boolean,
    val listLines: Int,
    /** A short widget sits in the middle of its row; a tall one reads from the top. */
    val centred: Boolean,
) {
    /**
     * Side by side; one over the other where they won't fit side by side; or, where there's no
     * height for that either, as plain words without the pills' padding, as narrow as they go.
     */
    enum class Pills { Row, Stacked, Plain }

    /** What there is to show, as far as the layout's concerned. */
    data class Parts(
        val warning: Boolean = false,
        val badge: String? = null,
        val minutes: String? = null,
        val then: Boolean = false,
        val label: Boolean = false,
    ) {
        companion object {
            fun of(model: WidgetModel) = Parts(model.warning != null, model.badge, model.minutes, model.then != null, model.label != null)
        }
    }

    companion object {
        // Heights: the card's padding (and, roomy, the widget's), then each line's at the default
        // text size.
        private const val PADDING = 12f
        private const val ROOMY_PADDING = 40f
        private const val HEADLINE = 22f
        private const val PILLS = 24f
        private const val THEN = 20f
        private const val LABEL = 16f
        private const val WARNING = 32f
        private const val SUMMARY = 18f
        private const val HEADING = 30f
        private const val LINE = 30f

        // Widths: a pill's 12 sp character (generously, at the default text size), its padding,
        // the gap between two, the card's padding either side (and the widget's, around the card
        // when roomy), and ↻.
        private const val CHAR = 7f
        private const val PILL = 16f
        private const val GAP = 6f
        private const val SIDES = 28f
        private const val ROOMY_SIDES = 44f
        private const val REFRESH = 36f

        /** [fontScale]: the phone's text size, which the text's sizes (at the default, below) grow with. */
        fun of(width: Float, height: Float, parts: Parts, fontScale: Float = 1f): WidgetLayout {
            fun t(atDefault: Float) = atDefault * fontScale
            val refresh = width >= 180
            val values = listOfNotNull(parts.badge, parts.minutes)
            val pillsHigh = if (values.isEmpty()) 0f else t(PILLS)
            fun sideBySide(sides: Float) =
                values.sumOf { it.length * t(CHAR) + PILL.toDouble() } + GAP * (values.size - 1) <= width - sides - (if (refresh) REFRESH else 0f)

            val roomyPills = if (sideBySide(ROOMY_SIDES)) Pills.Row else Pills.Stacked
            val card = ROOMY_PADDING + (if (parts.label) t(LABEL) else 0f) + t(HEADLINE) + pillsHigh * (if (roomyPills == Pills.Stacked) 2 else 1) +
                (if (parts.then) t(THEN) else 0f) + (if (parts.warning) t(WARNING) else 0f) + t(HEADING)
            if (height >= card + 2 * t(LINE)) {
                val headlineLines = if (height >= 320) 2 else 1
                val listLines = ((height - card - t(HEADLINE) * (headlineLines - 1)) / t(LINE)).toInt().coerceIn(0, 12)
                return WidgetLayout(
                    true, headlineLines, label = parts.label, refresh, roomyPills, then = parts.then,
                    summary = true, warning = parts.warning, listLines, centred = false,
                )
            }
            // Each in turn, as long as it fits; what isn't there takes no room.
            var used = PADDING + t(HEADLINE) + pillsHigh
            fun fits(extra: Float) = (height >= used + extra).also { if (it) used += extra }
            val pills = when {
                sideBySide(SIDES) -> Pills.Row
                fits(t(PILLS)) -> Pills.Stacked
                else -> Pills.Plain
            }
            val then = parts.then && fits(t(THEN))
            val label = parts.label && fits(t(LABEL))
            val warning = parts.warning && fits(t(WARNING))
            val summary = fits(t(SUMMARY))
            val headlineLines = if (fits(t(HEADLINE))) 2 else 1
            return WidgetLayout(false, headlineLines, label, refresh, pills, then, summary, warning, listLines = 0, centred = true)
        }
    }
}

/** ↻: reads every source now, and asks the Teams widget to sync Teams (Q21: a sync on a tap). */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        AppGraph.get(context).refreshAll(teams = true)
    }
}
