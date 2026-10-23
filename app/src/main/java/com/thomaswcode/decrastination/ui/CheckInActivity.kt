package com.thomaswcode.decrastination.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.learn.ReviewWorker
import com.thomaswcode.decrastination.notify.NoticeId
import kotlinx.coroutines.launch

/** The five questions, and below them the latest weekly review. */
class CheckInActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        setContent { AppTheme { Form(graph) } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Form(graph: AppGraph) {
        val log by graph.log.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        // Worked out once a screen, kept through it made again: one open across the week's turn stays the week it began.
        val week = rememberSaveable { Daily.checkInWeek(graph.clock.now(), graph.clock.zone(), graph.settings.value.checkInMin) }
        // What's typed kept through the screen made again (the phone turned), its week's: a new week's form starts empty.
        var feel by rememberSaveable(week) { mutableIntStateOf(0) }
        var saving by remember { mutableStateOf(false) }
        var avoided by rememberSaveable(week) { mutableStateOf("") }
        var inTheWay by rememberSaveable(week) { mutableStateOf("") }
        var change by rememberSaveable(week) { mutableStateOf("") }
        var energy by rememberSaveable(week) { mutableStateOf("") }
        val done = log.checkIns.any { it.weekOf == week }

        Scaffold(topBar = { TopAppBar(title = { Text("This week") }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (done) {
                    Text("This week's check-in is done.", style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text("How did the week feel?", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (n in 1..5) FilterChip(selected = feel == n, onClick = { feel = n }, label = { Text(n.toString()) })
                    }
                    Text("1 is badly, 5 is well.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(avoided, { avoided = it }, label = { Text("What did you avoid, and why?") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(inTheWay, { inTheWay = it }, label = { Text("What got in the way?") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(change, { change = it }, label = { Text("What would you change about the plan?") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(energy, { energy = it }, label = { Text("When in the day did you have most energy?") }, modifier = Modifier.fillMaxWidth())
                    Button(
                        enabled = feel > 0 && !saving,
                        onClick = {
                            saving = true
                            scope.launch {
                                val now = graph.clock.now()
                                // Once a week: a second tap (or another screen) finds it there already.
                                val added = graph.learning.recordCheckIn(CheckIn(week, now, feel, avoided.trim(), inTheWay.trim(), change.trim(), energy.trim()), now)
                                AppGraph.get(this@CheckInActivity).notifier.cancel(NoticeId.CheckIn)
                                // Felix's questions, should he have begun them: the form has the answers.
                                if (added) graph.conversation.endCheckInFlow()
                                if (!added) return@launch
                                // As a job: it can outlast this screen.
                                // Unless the week has had its review already (the evening's ran first).
                                ReviewWorker.enqueue(this@CheckInActivity, ifDue = true)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save, and review the week") }
                }
                log.reviews.maxByOrNull { it.at }?.let { review ->
                    HorizontalDivider()
                    Text("Review, ${Format.at(review.at, graph.clock.now(), graph.clock.zone())}", style = MaterialTheme.typography.titleSmall)
                    review.lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    // Why the rules wrote it, where that was kept (older reviews didn't keep it).
                    Text(if (review.by == "rules") "By the rules" + (review.why?.let { ": $it." } ?: ".") else "By Claude.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
