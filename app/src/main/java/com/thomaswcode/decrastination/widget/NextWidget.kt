package com.thomaswcode.decrastination.widget

import android.content.Context
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
import com.thomaswcode.decrastination.ui.OpenTaskActivity

/**
 * The next thing to do, on the home screen (PLAN.md Phase 2): a "Do:" line, its badge and
 * minutes, a "Then:" line, and ↻. A tap opens the task where it lives. It grows from one line
 * at 2×1 to the rest of today's list at 4×3 and up.
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
            val model = WidgetModel.from(graph.plan(tasks, settings), tasks, graph.clock.zone())
            GlanceTheme { Content(model) }
        }
    }

    @Composable
    private fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val size = LocalSize.current
        val layout = WidgetLayout.of(size.width.value, size.height.value, model.warning != null)
        val open = actionStartActivity(OpenTaskActivity.intent(context, model.taskId))
        Column(
            GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp)
                .background(GlanceTheme.colors.widgetBackground).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = if (layout.centred) Alignment.CenterVertically else Alignment.Top,
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight().clickable(open)) {
                    Text(
                        model.headline,
                        maxLines = layout.headlineLines,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    )
                    val details = listOfNotNull(model.badge, model.minutes).joinToString(" · ")
                    if (details.isNotEmpty()) {
                        Text(
                            details,
                            maxLines = 1,
                            style = TextStyle(
                                color = if (model.urgent) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 13.sp,
                                fontWeight = if (model.urgent) FontWeight.Medium else FontWeight.Normal,
                            ),
                        )
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
            // A Glance column draws at most ten children: spacing is padding, and the list is
            // one lazy column, however long.
            if (layout.then) {
                model.then?.let {
                    Text(it, maxLines = 1, modifier = GlanceModifier.padding(top = 4.dp), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp))
                }
            }
            if (layout.summary) {
                Text(model.summary, maxLines = 1, modifier = GlanceModifier.padding(top = 2.dp), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
            if (layout.listLines > 0 && model.list.isNotEmpty()) {
                LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight().padding(top = 6.dp)) {
                    items(model.list) { line ->
                        Row(GlanceModifier.fillMaxWidth().padding(vertical = 1.dp).clickable(actionStartActivity(OpenTaskActivity.intent(context, line.taskId)))) {
                            Text(
                                line.text,
                                maxLines = 1,
                                modifier = GlanceModifier.defaultWeight().padding(end = 8.dp),
                                style = TextStyle(color = if (line.urgent) GlanceTheme.colors.error else GlanceTheme.colors.onSurface, fontSize = 13.sp),
                            )
                            Text(line.minutes, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                        }
                    }
                }
            }
            if (layout.warning) {
                model.warning?.let {
                    Text(it, maxLines = 1, modifier = GlanceModifier.padding(top = 4.dp), style = TextStyle(color = GlanceTheme.colors.error, fontSize = 12.sp))
                }
            }
        }
    }
}

/**
 * What fits at a widget's size, in dp (Glance gives the exact size): pure, so each size is tested.
 * From 2×1 up: the "Do:" line and its badge; with width, ↻; with height, the "Then:" line, the
 * day's count and any warning; then as much of today's list as fits.
 */
data class WidgetLayout(
    val headlineLines: Int,
    val refresh: Boolean,
    val then: Boolean,
    val summary: Boolean,
    val warning: Boolean,
    val listLines: Int,
    /** A short widget sits in the middle of its row; a tall one reads from the top. */
    val centred: Boolean,
) {
    companion object {
        private const val PADDING = 20f
        private const val HEADLINE = 22f
        private const val DETAILS = 18f
        private const val LINE = 20f

        fun of(width: Float, height: Float, hasWarning: Boolean): WidgetLayout {
            val headlineLines = if (height >= 150) 2 else 1
            val then = height >= 76
            val summary = height >= 100
            val warning = hasWarning && height >= 120
            val used = PADDING + HEADLINE * headlineLines + DETAILS + (if (then) LINE else 0f) +
                (if (summary) LINE else 0f) + (if (warning) LINE else 0f) + 6f
            val listLines = if (height >= 150) ((height - used) / LINE).toInt().coerceIn(0, 12) else 0
            return WidgetLayout(headlineLines, width >= 180, then, summary, warning, listLines, centred = height < 150)
        }
    }
}

/** ↻: reads every source now, and asks the Teams widget to sync Teams (Q21: a sync on a tap). */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        AppGraph.get(context).refreshAll(teams = true)
    }
}
