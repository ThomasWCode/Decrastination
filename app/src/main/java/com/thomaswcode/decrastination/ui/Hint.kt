package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import kotlinx.coroutines.launch

/** A screen's first-run hint (UI-17), [text], till you put it away: then never again ([com.thomaswcode.decrastination.data.Settings.hintsSeen]). */
@Composable
fun Hint(graph: AppGraph, name: String, text: String) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    if (name in settings.hintsSeen) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(onClick = { graph.scope.launch { graph.changeSettings { it.copy(hintsSeen = (it.hintsSeen + name).distinct().takeLast(com.thomaswcode.decrastination.data.SettingsLimits.MAX_HINTS)) } } }) { Text("Got it") }
        }
    }
}
