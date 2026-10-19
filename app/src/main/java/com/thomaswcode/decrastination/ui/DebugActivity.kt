package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.ChatHistory
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.debug.CommandActivity
import com.thomaswcode.decrastination.enrich.ReadingPrompts
import com.thomaswcode.decrastination.protect.Watchdog
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import java.time.Instant

/**
 * The debug page (AR-03, debug builds, the menu's): the model's recent calls, a task's prompt as it
 * would be sent, the chat's context, Felix's state and last messages, the stores' sizes and ages,
 * each source's last read and the watchdog's findings, each block with Copy; and the commands that
 * take nothing, as the PC sends them.
 */
class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        setContent { AppTheme { DebugPage(graph, this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugPage(graph: AppGraph, activity: Activity) {
    val runtime by graph.runtime.state.collectAsStateWithLifecycle()
    val model by graph.stores.model.state.collectAsStateWithLifecycle()
    val protection by graph.stores.protection.state.collectAsStateWithLifecycle()
    val state by graph.tasks.state.collectAsStateWithLifecycle()
    val log by graph.log.state.collectAsStateWithLifecycle()
    val chat by graph.chat.state.collectAsStateWithLifecycle()
    val zone = graph.clock.zone()
    val now = graph.clock.now()
    // Made again on a tap: the context and the plan are the costly ones.
    var round by remember { mutableIntStateOf(0) }
    var taskId by rememberSaveable { mutableStateOf<String?>(null) }
    fun at(time: Long?) = time?.let { Format.ago(it, now) } ?: "never"
    val context = remember(round, state, runtime.notSame) { graph.conversation.chatContext().let { it.known + "\n\n" + it.plan } }
    Scaffold(topBar = { TopAppBar(title = { Text("Debug") }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "commands") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CommandActivity.PLAIN.forEach { cmd -> OutlinedButton(onClick = { activity.startActivity(Intent(activity, CommandActivity::class.java).putExtra("cmd", cmd)) }) { Text(cmd) } }
                        TextButton(onClick = { round++ }) { Text("Read again") }
                    }
                    Text("Each logs what it did: adb logcat -s Decrastination", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "calls") { Block(activity, "Recent calls (${model.calls.size})", model.calls.asReversed().joinToString("\n\n") { it.details(zone) }.ifEmpty { "None yet." }) }
            item(key = "prompt") {
                val open = state.tasks.filter { Enrichments.jobFor(it, GmailThreads::textRead) != null }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("A task's prompt", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        open.forEach { t -> FilterChip(selected = taskId == t.id, onClick = { taskId = t.id }, label = { Text(t.title.take(28)) }) }
                    }
                    open.firstOrNull { it.id == taskId }?.let { task ->
                        val job = Enrichments.jobFor(task, GmailThreads::textRead)!!
                        val profile = graph.conversation.profile(now)
                        val previous = task.subSteps.ifEmpty { task.enrichment?.subSteps.orEmpty() }
                        val item = (if (task.photo != null) ReadingPrompts.photo(job) + "\n\n" else "") + ReadingPrompts.describe(task, job, now, zone, previous, state.deckTopics)
                        Block(activity, "${job.name}, version ${ReadingPrompts.VERSION}", ReadingPrompts.system(job, profile.year) + "\n\n=== PROFILE ===\n" + profile.text + "\n\n=== ITEM ===\n" + item)
                    }
                }
            }
            item(key = "chat") { Block(activity, "The chat's context", context) }
            item(key = "felix") {
                Block(
                    activity,
                    "Felix",
                    "State: ${chat.felix}\nRead to: ${chat.readAt}\n\nLast messages:\n" + ChatHistory.his(chat.messages).takeLast(10).joinToString("\n") { m ->
                        "${Instant.ofEpochMilli(m.at).atZone(zone).toLocalDateTime().withNano(0)} ${m.kind}: ${m.text}" + (ChatHistory.replyOf(m, chat.messages)?.let { " → ${it.text}" } ?: "")
                    }.ifEmpty { "none" },
                )
            }
            item(key = "stores") {
                val files = activity.filesDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
                Block(activity, "Stores", files.joinToString("\n") { "${it.name}: ${it.length()} bytes, written ${at(it.lastModified())}" }.ifEmpty { "none" })
            }
            item(key = "sources") {
                Block(
                    activity,
                    "Sources",
                    state.sources.entries.sortedBy { it.key.name }.joinToString("\n") { (source, s) ->
                        "${source.label}: read ${at(s.lastSuccessAt)}, tried ${at(s.lastAttemptAt)}" + (s.dataAsOf?.let { ", its data ${at(it)}" } ?: "") + (s.error?.let { "; failed: $it" } ?: "") + (s.note?.let { "; $it" } ?: "")
                    }.ifEmpty { "none read yet" },
                )
            }
            item(key = "watchdog") {
                val report = remember(round) { runCatching { Watchdog.report(activity).toString() }.getOrElse { "couldn't be made: ${it.message}" } }
                Block(activity, "The watchdog", "Problems: ${protection.protection.problems.ifEmpty { listOf("none") }.joinToString("; ")}\nChecked ${at(protection.protection.checkedAt)}\n\n$report")
            }
        }
    }
}

/** A titled block of [text], to read and copy whole. */
@Composable
private fun Block(activity: Activity, title: String, text: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, text)) }) { Text("Copy") }
            }
            Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
