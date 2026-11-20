package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.views.StorageView

/**
 * Settings' Storage (6.10.0, D-161): what the box, R2 and B2 hold, each beside its free allowance, when it'd be passed at the last
 * week's rate, and when it was read, as the server read it (it alone can); fetched as the page opens.
 */
@Composable
internal fun StorageSection(graph: AppGraph) {
    val view by produceState<Result<StorageView?>?>(null) { value = runCatching { graph.replica.storage() } }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        val shown = view?.getOrNull()
        when {
            view == null -> Quiet("Reading it from the server…")
            shown == null -> Quiet(if (graph.replica.paired) "The server hasn't read it yet, or can't be reached: it's read once a day." else "Read by the server: pair this phone with the website to see it.")
            else -> {
                shown.lines.forEach { line ->
                    Text(line.label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (line.near) FontWeight.SemiBold else null, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        "${line.used}: ${line.percent}%" + (line.passes?.let { ", $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (line.near) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                shown.box.forEach { Quiet(it) }
                shown.problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
                Quiet(shown.read)
            }
        }
    }
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}
