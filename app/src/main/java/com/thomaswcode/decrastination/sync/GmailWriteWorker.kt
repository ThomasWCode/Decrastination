package com.thomaswcode.decrastination.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.GmailWrite
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Posts
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.sources.gmail.ImapGmailWriter
import com.thomaswcode.decrastination.ui.MainActivity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * The writes to Gmail a finished email makes ([GmailFinishes.write]), as WorkManager work (it outlives the
 * screen it's asked from, and Android 15 cuts an app's network seconds after it leaves the screen): with a
 * connection, after the grace, tried again after a wait when a try fails. The tries and their waits are kept in
 * the journal, not by WorkManager, so a write is given up after [com.thomaswcode.decrastination.core.GmailWrites.MAX_TRIES] whatever ran it,
 * and the worker set going for another write doesn't cut a wait short.
 */
class GmailWriteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        return try {
            // A try that fails waits in the journal, and the write sets the worker's next run itself.
            graph.gmailFinishes.write(ImapGmailWriter(graph.stores.secrets))
            Result.success()
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (e: Exception) {
            // Not a write's try (the journal not read or not written): WorkManager's own wait.
            Log.w(AppGraph.TAG, "Gmail's writes failed", e)
            Result.retry()
        }
    }

    companion object {
        /** One a phone: a new time replaces the one set before. */
        const val NAME = "gmail-write"

        /** WorkManager's first wait after a run that failed outside a write's tries; doubled each time, to its cap of five hours. */
        private const val BACKOFF_MIN = 30L

        /** Set to run at [at], measured from [now], once there's a connection: in place of the one set before, or, by [policy] KEEP, only where none is set or running. */
        fun schedule(context: Context, at: Long, now: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val request = OneTimeWorkRequestBuilder<GmailWriteWorker>()
                .setInitialDelay((at - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MIN, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
        }
    }
}

/** [GmailFinishes]' ports on the phone: the worker, a Gmail-only sync, and a write given up said by the app's own notification. */
class AndroidGmailPorts(
    private val context: Context,
    private val notices: Posts,
    private val clock: WallClock,
    private val syncNow: (Set<Source>) -> Unit,
) : GmailFinishes.Ports {
    override fun schedule(at: Long) = GmailWriteWorker.schedule(context, at, clock.now())

    override fun ensureScheduled(at: Long) = GmailWriteWorker.schedule(context, at, clock.now(), ExistingWorkPolicy.KEEP)

    override fun syncGmail() = syncNow(setOf(Source.Gmail))

    override fun gaveUp(write: GmailWrite, words: String) {
        // Opening the task's sheet; its own data, so one a conversation doesn't take another's tap.
        val open = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("decrastination://gmail-write/${write.threadId}"))
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TASKS)
            .putExtra(MainActivity.EXTRA_TASK, write.taskId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        notices.post(
            NoticeId.GmailWrite,
            Notify.builder(context, Channels.SOURCES)
                .setContentTitle("Gmail wasn't changed as asked")
                .setContentText(words)
                .setStyle(NotificationCompat.BigTextStyle().bigText(words))
                .let { Notify.opening(it, context, NoticeId.GmailWrite.code(), open) }
                .setAutoCancel(true)
                .build(),
            // One a conversation, told apart by its thread.
            write.threadId,
        )
    }

    override fun sorted(write: GmailWrite) = notices.cancel(NoticeId.GmailWrite, write.threadId)
}
