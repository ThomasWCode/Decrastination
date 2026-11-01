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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.learn.SummaryEmail
import com.thomaswcode.decrastination.sync.protocol.UndoReviewChange
import com.thomaswcode.decrastination.sync.protocol.UndoTidy
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
            // The week's latest summary: Sunday's, where a midweek review wrote one before it (D-123).
            graph.log.value.reviews.filter { it.week == week && it.summary != null }.maxByOrNull { it.at }?.summary?.let { text ->
                runCatching { startActivity(SummaryEmail.email(graph.settings.value.summaryEmail, week, text)) }
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
    val cards = ReviewPage.cards(log, settings, memory, zone)
    Scaffold(topBar = { TopAppBar(title = { Text("Reviews") }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            said?.let { item(key = "said") { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) } }
            if (cards.isEmpty()) item(key = "none") { Text(ReviewPage.NONE_YET, style = MaterialTheme.typography.bodyLarge) }
            items(cards, key = { it.review.at }) { card ->
                ReviewCard(
                    card,
                    settings,
                    onUndo = { setting -> scope.launch { said = graph.act(UndoReviewChange(card.review.at, setting)) } },
                    onUndoTidy = { scope.launch { said = graph.act(UndoTidy(card.review.at)) } },
                )
            }
        }
    }
}

@Composable
private fun ReviewCard(card: ReviewPage.Card, settings: Settings, onUndo: (String) -> Unit, onUndoTidy: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(card.week, style = MaterialTheme.typography.titleMedium)
            Text(card.by, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            card.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            card.changes.forEach { change ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(change.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    // Only while it's what the review left, and what it was is known.
                    if (change.undoable) TextButton(onClick = { onUndo(change.setting) }) { Text("Undo") }
                }
            }
            card.tidy?.let { tidy ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tidy, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (card.tidyUndoable) TextButton(onClick = onUndoTidy) { Text("Undo") }
                }
            }
            // The week's summary for home (NF-02), and its email.
            card.summary?.let { text ->
                Text("The week's summary", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text, style = MaterialTheme.typography.bodyMedium)
                val context = LocalContext.current
                card.review.week?.let { week -> TextButton(onClick = { runCatching { context.startActivity(SummaryEmail.email(settings.summaryEmail, week, text)) } }) { Text("Send it") } }
            }
            card.checkIn?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
