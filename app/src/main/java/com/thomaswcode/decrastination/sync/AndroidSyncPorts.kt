package com.thomaswcode.decrastination.sync

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.CalendarReader
import com.thomaswcode.decrastination.sources.teams.TeamsProvider
import com.thomaswcode.decrastination.sources.teams.TeamsRows
import com.thomaswcode.decrastination.widget.WidgetUpdater

/** [SyncPorts] on the phone. */
class AndroidSyncPorts(private val context: Context) : SyncPorts {
    override fun syncNow(sources: Set<Source>?) = SyncWorker.syncNow(context, sources)

    override fun requestTeamsSync(automatic: Boolean): TeamsAnswer? =
        TeamsProvider.call(context.contentResolver, TeamsProvider.METHOD_REQUEST_SYNC, TeamsProvider.SYNC_AUTOMATIC.takeIf { automatic })
            ?.let { TeamsAnswer(it.getBoolean(TeamsProvider.RESULT_STARTED), it.getString(TeamsProvider.RESULT_REASON)) }

    override fun watchTeams(onList: () -> Unit, onState: () -> Unit) {
        context.contentResolver.registerContentObserver(
            TeamsProvider.root,
            true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = onChange(selfChange, null)

                // By the path it came on (0.6.0 on); the root, or none, says either may have changed.
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    when (TeamsProvider.changed(uri)) {
                        TeamsProvider.Changed.List -> onList()
                        TeamsProvider.Changed.State -> onState()
                        TeamsProvider.Changed.Either -> {
                            onList()
                            onState()
                        }
                    }
                }
            },
        )
    }

    override fun teamsWidget(): TeamsWidget? = runCatching { TeamsProvider.state(context.contentResolver) }.getOrNull()?.let { state ->
        TeamsWidget(
            lastSuccessAt = state["last_success_at"] as? Long,
            listsSeenAt = state["lists_seen_at"] as? Long,
            noticedAt = state["noticed_at"] as? Long,
            note = TeamsRows.note(state),
        )
    }

    override fun calendarAllowed(): Boolean = CalendarReader.allowed(context)

    override fun readCalendar(from: Long, to: Long, excluded: Set<Long>): List<CalendarEvent> = CalendarReader.read(context, from, to, excluded)

    override suspend fun redraw() = WidgetUpdater.update(context)
}
