package com.thomaswcode.decrastination.learn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.AssessLater
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.ui.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The quick self-assessment after each verified completion (docs/scheduler.md §5, item 5): one tap
 * in the notification, harder, as expected or easier; or a tap on it to add a line. It feeds the
 * effort multipliers and the weekly review.
 */
object Assessment {
    private const val BASE_ID = 4100
    const val ACTION = "com.thomaswcode.decrastination.action.ASSESS"
    const val EXTRA_TASK = "task"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ANSWER = "answer"

    private val ANSWERS = listOf("Harder" to Calibrator.HARDER, "As expected" to Calibrator.AS_EXPECTED, "Easier" to Calibrator.EASIER)

    const val EXTRA_DONE_AT = "doneAt"

    /** One notification per completion, told apart by its tag: two rounds of one task don't share one. */
    private fun tag(taskId: String, doneAt: Long?) = "assess:$taskId@${doneAt ?: 0}"

    /**
     * Asked about homework and revision only: an archived email or a passed event isn't work to
     * judge. While notifications can't be seen, the questions are kept and asked once they can.
     */
    suspend fun ask(context: Context, completed: List<TaskItem>) {
        // Asked again after a stop: one answered meanwhile isn't asked again.
        val answered = AppGraph.get(context).log.value.completions.filter { it.assessment != null }
        val asks = completed.filter { it.kind == Kind.Homework || it.kind == Kind.Revision }
            .filterNot { t -> answered.any { it.taskId == t.id && it.doneAt == t.doneAt } }
            .map { AssessLater(it.id, it.title, it.doneAt) }
        if (asks.isEmpty()) return
        if (!Notify.shown(context, Channels.DAILY)) {
            AppGraph.get(context).runtime.update { it.copy(assessLater = (it.assessLater + asks).distinctBy { a -> a.taskId to a.doneAt }.takeLast(MAX_LATER)) }
            return
        }
        asks.forEach { post(context, it) }
    }

    /** The questions kept while notifications couldn't be seen, asked now they can. */
    suspend fun askLater(context: Context) {
        val graph = AppGraph.get(context)
        val waiting = graph.runtime.value.assessLater
        if (waiting.isEmpty() || !Notify.shown(context, Channels.DAILY)) return
        waiting.forEach { post(context, it) }
        graph.runtime.update { it.copy(assessLater = it.assessLater - waiting.toSet()) }
    }

    private const val MAX_LATER = 20

    private fun post(context: Context, ask: AssessLater) {
        val builder = NotificationCompat.Builder(context, Channels.DAILY)
            .setSmallIcon(R.drawable.ic_focus)
            .setContentTitle("Done: ${ask.title}")
            .setContentText("How was it?")
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, AssessActivity::class.java)
                        .setData(Uri.fromParts("task", "${ask.taskId}@${ask.doneAt ?: 0}", null))
                        .putExtra(EXTRA_TASK, ask.taskId)
                        .putExtra(EXTRA_DONE_AT, ask.doneAt ?: 0L)
                        .putExtra(EXTRA_TITLE, ask.title)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setAutoCancel(true)
        ANSWERS.forEachIndexed { i, (label, answer) ->
            val tap = PendingIntent.getBroadcast(
                context,
                i,
                Intent(context, AssessmentReceiver::class.java)
                    .setAction(ACTION)
                    .setData(Uri.fromParts("task", "${ask.taskId}@${ask.doneAt ?: 0}#$i", null))
                    .putExtra(EXTRA_TASK, ask.taskId)
                    .putExtra(EXTRA_DONE_AT, ask.doneAt ?: 0L)
                    .putExtra(EXTRA_ANSWER, answer),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, label, tap)
        }
        Notify.post(context, tag(ask.taskId, ask.doneAt), BASE_ID, builder.build())
    }

    /** Records [answer] (and [note]) on the completion of [taskId] done at [doneAt] (0: its latest, for older questions). */
    suspend fun record(context: Context, taskId: String, doneAt: Long, answer: String, note: String? = null) {
        AppGraph.get(context).log.update { answered(it, taskId, doneAt, answer, note) }
        Notify.cancel(context, tag(taskId, doneAt.takeIf { it != 0L }), BASE_ID)
    }

    /** [log] with [answer] on the completion it's about: the one done at [doneAt], or (0) the latest of [taskId]'s. */
    fun answered(log: ActivityLog, taskId: String, doneAt: Long, answer: String, note: String?): ActivityLog {
        val i = if (doneAt != 0L) log.completions.indexOfFirst { it.taskId == taskId && it.doneAt == doneAt } else log.completions.indexOfLast { it.taskId == taskId }
        if (i < 0) return log
        return log.copy(completions = log.completions.toMutableList().also { it[i] = it[i].copy(assessment = answer, note = note?.trim()?.takeIf(String::isNotEmpty) ?: it[i].note) })
    }
}

/** A tap on one of the notification's answers. */
class AssessmentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val task = intent.getStringExtra(Assessment.EXTRA_TASK) ?: return
        val answer = intent.getStringExtra(Assessment.EXTRA_ANSWER) ?: return
        val doneAt = intent.getLongExtra(Assessment.EXTRA_DONE_AT, 0L)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Assessment.record(context, task, doneAt, answer)
            } finally {
                pending.finish()
            }
        }
    }
}

/** The three answers and a line, for when one tap isn't enough. */
class AssessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val task = intent.getStringExtra(Assessment.EXTRA_TASK) ?: return finish()
        val title = intent.getStringExtra(Assessment.EXTRA_TITLE).orEmpty()
        val doneAt = intent.getLongExtra(Assessment.EXTRA_DONE_AT, 0L)
        setContent { AppTheme { Screen(task, doneAt, title) } }
    }

    @Composable
    private fun Screen(task: String, doneAt: Long, title: String) {
        var note by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        fun save(answer: String) {
            scope.launch {
                Assessment.record(this@AssessActivity, task, doneAt, answer, note)
                finish()
            }
        }
        Scaffold { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text("How was it?", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(note, { note = it }, label = { Text("A line about it (optional)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { save(Calibrator.HARDER) }) { Text("Harder") }
                    Button(onClick = { save(Calibrator.AS_EXPECTED) }) { Text("As expected") }
                    OutlinedButton(onClick = { save(Calibrator.EASIER) }) { Text("Easier") }
                }
            }
        }
    }
}
