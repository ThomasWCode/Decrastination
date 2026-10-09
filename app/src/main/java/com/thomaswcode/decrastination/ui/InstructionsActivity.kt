package com.thomaswcode.decrastination.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import kotlinx.coroutines.launch

/**
 * Your instructions (core/Instructions.kt): a new one, about nothing in particular; then those
 * whose reading waits for you to check it, those Claude is reading, and those in use. One about a
 * task is written from the task (Tasks), one about an event or a day from the Calendar.
 */
class InstructionsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        setContent { AppTheme { Screen(graph) } }
    }

    @Composable
    private fun Screen(graph: AppGraph) {
        val state by graph.instructions.state.collectAsStateWithLifecycle()
        val tasks by graph.tasks.state.collectAsStateWithLifecycle()
        val byId = tasks.tasks.associateBy { it.id }
        val now = graph.clock.now()
        val zone = graph.clock.zone()
        val scope = rememberCoroutineScope()
        var text by rememberSaveable { mutableStateOf("") }
        val newest = state.instructions.sortedByDescending { it.appliedAt ?: it.at }
        val toCheck = newest.filter { it.state == InstructionStatus.Understood || it.state == InstructionStatus.Unclear }
        val reading = newest.filter { it.state == InstructionStatus.Reading }
        val inUse = newest.filter { it.state == InstructionStatus.Applied }

        Scaffold(topBar = { BackBar("Instructions", this) }) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "new") {
                    Group("New instruction") {
                        GroupTile {
                            OutlinedTextField(text, { text = it }, label = { Text("In your words") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                            Text(
                                "$INSTRUCTION_EXAMPLES One about a task is best written from it, in Tasks; one about an event or a day, from the Calendar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Button(
                                enabled = text.isNotBlank(),
                                onClick = {
                                    val words = text
                                    text = ""
                                    scope.launch { graph.addInstruction(words, About()) }
                                },
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text("Send to Claude") }
                        }
                    }
                }
                section("To check", toCheck, graph, byId, now, zone)
                section("Being read", reading, graph, byId, now, zone)
                section("In use", inUse, graph, byId, now, zone)
                if (state.instructions.isEmpty()) {
                    item(key = "none") {
                        Text(
                            "No instructions yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.section(
        title: String,
        list: List<Instruction>,
        graph: AppGraph,
        tasks: Map<String, com.thomaswcode.decrastination.core.TaskItem>,
        now: Long,
        zone: java.time.ZoneId,
    ) {
        if (list.isEmpty()) return
        item(key = "title-$title") { SectionHeading(title, trailing = "${list.size}") }
        itemsIndexed(list, key = { _, it -> it.id }) { index, instruction ->
            ListTile(index, list.size) { InstructionContent(graph, instruction, tasks, now, zone) }
        }
    }
}
