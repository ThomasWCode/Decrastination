package com.thomaswcode.decrastination.block

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.ui.AppTheme
import com.thomaswcode.decrastination.ui.Format
import com.thomaswcode.decrastination.ui.TaskOpener
import com.thomaswcode.decrastination.widget.WidgetModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a blocked app shows instead (PLAN.md Phase 3): why, the next thing to do, and the ways
 * to get on with it. **Open** goes to the task; **Start** runs a focus session on it; **Check
 * it's done** asks its source; **Refresh Teams** asks the Teams widget to sync. There's no way
 * through (docs/scheduler.md §6, layer 1). Back, and **Go home**, go to the home screen.
 *
 * Its own task (manifest), so neither Back nor Recents can reveal the app behind.
 */
class BlockedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        val graph = AppGraph.get(this)
        setContent { AppTheme { Screen(graph) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private val targetName: String get() = intent.getStringExtra(EXTRA_TARGET).orEmpty()

    private fun targetLabel(): String = when (intent.getStringExtra(EXTRA_KIND)) {
        KIND_SITE -> targetName
        else -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetName, 0)).toString() }.getOrDefault(targetName)
    }

    @Composable
    private fun Screen(graph: AppGraph) {
        val tasks by graph.tasks.state.collectAsStateWithLifecycle()
        val runtime by graph.runtime.state.collectAsStateWithLifecycle()
        val settings by graph.settings.state.collectAsStateWithLifecycle()
        val now by produceState(graph.clock.now()) {
            while (true) {
                delay(1_000)
                value = graph.clock.now()
            }
        }
        val plan = remember(tasks, settings, now / 60_000) { graph.plan(tasks, settings, now) }
        val verdict = remember(tasks, settings, runtime, now / 10_000) { graph.focus.verdict(now) }
        val session = runtime.session
        val zone = graph.clock.zone()
        val scope = rememberCoroutineScope()
        var message by remember { mutableStateOf<String?>(null) }
        var why by remember { mutableStateOf(false) }
        // Its own target taken off the blocklist (a removal that fell due), whatever else is due.
        val targetBlocked = remember(settings) {
            when {
                targetName.isEmpty() -> true
                // A site: still listed, and its browser still one whose address bar is read.
                intent.getStringExtra(EXTRA_KIND) == KIND_SITE ->
                    targetName in settings.blockedSites && intent.getStringExtra(EXTRA_BROWSER)?.let { it in settings.checkedBrowsers } != false
                else -> graph.focus.target(targetName) != null
            }
        }
        // Nothing is blocked any more (free time earned, quiet hours begun), or not this: let it through.
        LaunchedEffect(verdict, session, targetBlocked) {
            // Its target no longer blocked goes at once, session or not; otherwise once nothing's
            // blocked and no session holds it.
            if (!targetBlocked || (verdict !is BlockPolicy.Verdict.Block && session == null)) finish()
        }
        val next = if (session != null) plan.chunksOf(session.taskId).firstOrNull() ?: plan.next else plan.next

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Not now", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Text("${targetLabel()} is blocked. ${verdict.reason.headline}.", style = MaterialTheme.typography.bodyLarge)
                if (session != null) {
                    val left = ((session.endsAt - now) / 1000).coerceAtLeast(0)
                    Text(
                        "Focus: ${session.label}, %d:%02d left".format(left / 60, left % 60),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (next != null) {
                    NextCard(next, plan.now, zone)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            tasks.tasks.firstOrNull { it.id == next.taskId }?.let { task ->
                                scope.launch { message = TaskOpener.open(this@BlockedActivity, task) }
                            }
                        }) { Text("Open") }
                        if (session == null) {
                            // Not before it's available (an Anki deck's next cards at 04:00).
                            FilledTonalButton(enabled = next.startable(plan.now), onClick = {
                                scope.launch { Sessions.start(this@BlockedActivity, next.taskId, next.label, next.step, next.minutes) }
                            }) { Text("Start ${Format.minutes(next.minutes)}") }
                        } else {
                            OutlinedButton(onClick = { scope.launch { Sessions.end(this@BlockedActivity, early = true) } }) { Text("Stop session") }
                        }
                        OutlinedButton(onClick = {
                            val task = tasks.tasks.firstOrNull { it.id == next.taskId } ?: return@OutlinedButton
                            message = "Asking ${task.source.label}…"
                            scope.launch {
                                graph.syncer.sync(setOf(task.source))
                                val still = graph.tasks.value.tasks.firstOrNull { it.id == task.id }?.isOpen == true
                                message = when {
                                    !still -> "Done: ${task.source.label} agrees."
                                    task.source == Source.Teams -> "Teams still lists it as not handed in. If you've handed it in, tap Refresh Teams."
                                    else -> "${task.source.label} still lists it as open."
                                }
                            }
                        }) { Text("Check it's done") }
                        if (next.source == Source.Teams) {
                            OutlinedButton(onClick = {
                                scope.launch { message = graph.requestTeamsSync() ?: "The Teams widget is syncing Teams" }
                            }) { Text("Refresh Teams") }
                        }
                    }
                } else {
                    Text("Nothing is planned. Free time comes from finishing work.", style = MaterialTheme.typography.bodyLarge)
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }
                val credit = graph.focus.creditLeftMs(now)
                if (credit > 0 || verdict.reason == BlockPolicy.Reason.NoFreeTime) {
                    Text("Free time left today: ${Format.minutes((credit / 60_000).toInt())}", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { why = !why }) { Text(if (why) "Hide why" else "Why am I blocked?") }
                AnimatedVisibility(why) { Why(verdict.reason, plan.dueSoon, settings.workMinPerFreeMin) }
                OutlinedButton(onClick = ::goHome, modifier = Modifier.fillMaxWidth()) { Text("Go to the home screen") }
            }
        }
    }

    @Composable
    private fun NextCard(chunk: Chunk, now: Long, zone: java.time.ZoneId) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Do", style = MaterialTheme.typography.labelLarge)
                Text(chunk.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    listOf(WidgetModel.badge(chunk, now, zone), Format.minutes(chunk.minutes), chunk.source.label).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (chunk.overdue || chunk.behind) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }

    @Composable
    private fun Why(reason: BlockPolicy.Reason, dueSoon: List<Chunk>, ratio: Int) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (reason) {
                BlockPolicy.Reason.DueSoon -> {
                    Text("While anything is due today or tomorrow, blocking is strict. Due soon:", style = MaterialTheme.typography.bodyMedium)
                    dueSoon.take(12).forEach { Text("• ${it.label} (${Format.minutes(it.minutes)})", style = MaterialTheme.typography.bodySmall) }
                    if (dueSoon.size > 12) Text("and ${dueSoon.size - 12} more", style = MaterialTheme.typography.bodySmall)
                }
                BlockPolicy.Reason.NoFreeTime -> Text(
                    "Nothing is due soon, but there's no free time left today. Every $ratio minutes of work earns a minute; it's gone at midnight.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                BlockPolicy.Reason.Session -> Text("You started a focus session: blocked apps stay blocked until it ends.", style = MaterialTheme.typography.bodyMedium)
                else -> Text(reason.headline, style = MaterialTheme.typography.bodyMedium)
            }
            Text("Nothing is blocked 22:30–07:00, or before 16:45 on school days.", style = MaterialTheme.typography.bodySmall)
        }
    }

    companion object {
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_BROWSER = "browser"
        private const val KIND_SITE = "site"

        fun intent(context: Context, target: Focus.Target, reason: BlockPolicy.Reason): Intent =
            Intent(context, BlockedActivity::class.java)
                .putExtra(EXTRA_TARGET, target.name)
                .putExtra(EXTRA_KIND, if (target is Focus.Target.Site) KIND_SITE else "app")
                .putExtra(EXTRA_BROWSER, (target as? Focus.Target.Site)?.browser)
                .putExtra(EXTRA_REASON, reason.name)
    }
}
