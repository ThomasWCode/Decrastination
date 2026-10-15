package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Memories
import com.thomaswcode.decrastination.enrich.MemoryTidy
import kotlinx.coroutines.launch

/**
 * Settings' list of what Felix remembers (AI-21; phase 6 makes it Settings → Felix): the standing
 * instructions on how he behaves and the facts about you, each with a forget, and a way to add an
 * instruction in your own words. Saved at once, as the school calendar is: they're his, not settings.
 */
@Composable
fun MemorySection(graph: AppGraph) {
    val memory by graph.memory.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var rule by rememberSaveable { mutableStateOf("") }
    // What adding one did ("Felix will: …", or why it wasn't added).
    var said by rememberSaveable { mutableStateOf<String?>(null) }
    val small = MaterialTheme.typography.bodySmall
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (memory.facts.isEmpty() && memory.rules.isEmpty()) {
            Text("Nothing yet. What you tell Felix that's worth keeping (\"football is on Tuesdays\", \"don't message me before 8\") is kept here, and sent with every call.", style = small, color = quiet)
        }
        if (memory.rules.isNotEmpty()) Text("How Felix should behave", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        memory.rules.forEach { r -> Entry(r.text, "Forget") { scope.launch { graph.memory.update { it.copy(rules = it.rules - r) } } } }
        if (memory.facts.isNotEmpty()) Text("Remembered, newest first", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        memory.facts.asReversed().forEach { f -> Entry("${f.topic}: ${f.text}", "Forget") { scope.launch { graph.memory.update { it.copy(facts = it.facts - f) } } } }
        if (memory.facts.isNotEmpty()) {
            val used = Memories.chars(memory.facts) * 100 / Memories.FACT_BUDGET_CHARS
            Text("$used% of the room for facts used. Past ${(MemoryTidy.DUE_SHARE * 100).toInt()}%, Sunday's review tidies them into fewer lines; past it all, the oldest go.", style = small, color = quiet)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = rule,
                onValueChange = { rule = it.take(300) },
                label = { Text("A standing instruction for Felix") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = rule.isNotBlank(), onClick = {
                val text = rule.trim()
                rule = ""
                scope.launch { said = graph.remember(listOf(Given(text = text, kind = Given.RULE))).joinToString("\n").ifEmpty { null } }
            }) { Text("Add") }
        }
        said?.let { Text(it, style = small, color = quiet) }
    }
}
