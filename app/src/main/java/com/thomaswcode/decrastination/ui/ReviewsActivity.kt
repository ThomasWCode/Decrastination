package com.thomaswcode.decrastination.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.learn.ReviewInput
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The weekly reviews (LR-01), newest first: each one's note, the settings it changed, each with an
 * Undo while it's what the review left, the tidy of what's remembered with its Undo, and that week's
 * check-in answers.
 */
class ReviewsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        setContent { AppTheme { Reviews(graph) } }
        // From the summary's notification (NF-02): its email opened over the page, once.
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_SEND)?.let { week ->
            graph.log.value.reviews.firstOrNull { it.week == week }?.summary?.let { text ->
                runCatching { startActivity(com.thomaswcode.decrastination.learn.Summary.email(graph.settings.value.summaryEmail, week, text)) }
            }
        }
    }

    companion object {
        /** The week whose summary to open in an email. */
        const val EXTRA_SEND = "send"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Reviews(graph: AppGraph) {
    val log by graph.log.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val memory by graph.memory.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val zone = graph.clock.zone()
    var said by remember { mutableStateOf<String?>(null) }
    val reviews = log.reviews.sortedByDescending { it.at }
    Scaffold(topBar = { TopAppBar(title = { Text("Reviews") }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            said?.let { item(key = "said") { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) } }
            if (reviews.isEmpty()) item(key = "none") { Text("No review yet: the first comes on Sunday evening, after the week's check-in.", style = MaterialTheme.typography.bodyLarge) }
            items(reviews, key = { it.at }) { review ->
                ReviewCard(
                    review,
                    log.checkIns.filter { it.weekOf == review.week }.maxByOrNull { it.at },
                    settings,
                    tidyUndoable = review.memoryTidy?.let { memory.facts == it.after } == true,
                    zone = zone,
                    onUndo = { change -> scope.launch { said = graph.undoReviewChange(review, change) } },
                    onUndoTidy = { scope.launch { said = graph.undoTidy(review) } },
                )
            }
        }
    }
}

@Composable
private fun ReviewCard(review: WeeklyReview, checkIn: CheckIn?, settings: Settings, tidyUndoable: Boolean, zone: ZoneId, onUndo: (ReviewInput.Change) -> Unit, onUndoTidy: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val week = review.week?.let { LocalDate.parse(it) } ?: Instant.ofEpochMilli(review.at).atZone(zone).toLocalDate()
            Text("Week of ${week.format(DAY)}", style = MaterialTheme.typography.titleMedium)
            Text(if (review.by == "rules") "By the rules" + (review.why?.let { ": $it" } ?: "") else "By Claude", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            review.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            review.changes.forEach { change ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ReviewInput.changeText(change, review.settingsBefore[change.setting]), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    // Only while it's what the review left, and what it was is known.
                    if (review.settingsBefore[change.setting] != null && ReviewInput.valueOf(settings, change.setting) == change.value) TextButton(onClick = { onUndo(change) }) { Text("Undo") }
                }
            }
            review.memoryTidy?.let { tidy ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("What's remembered tidied: ${tidy.before.size} facts into ${tidy.after.size}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (tidyUndoable) TextButton(onClick = onUndoTidy) { Text("Undo") }
                }
            }
            // The week's summary for home (NF-02), and its email.
            review.summary?.let { text ->
                Text("The week's summary", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text, style = MaterialTheme.typography.bodyMedium)
                val context = androidx.compose.ui.platform.LocalContext.current
                review.week?.let { week -> TextButton(onClick = { runCatching { context.startActivity(com.thomaswcode.decrastination.learn.Summary.email(settings.summaryEmail, week, text)) } }) { Text("Send it") } }
            }
            checkIn?.let { c ->
                fun said(text: String) = text.ifBlank { "-" }
                Text(
                    "The week felt ${c.feel}/5. Avoided: ${said(c.avoided)}. In the way: ${said(c.inTheWay)}. Would change: ${said(c.change)}. Most energy: ${said(c.energy)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
