package com.thomaswcode.decrastination.sync

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.CalendarReader
import com.thomaswcode.decrastination.sources.teams.TeamsProvider
import com.thomaswcode.decrastination.widget.WidgetUpdater

/** [SyncPorts] on the phone. */
class AndroidSyncPorts(private val context: Context) : SyncPorts {
    override fun syncNow(sources: Set<Source>?) = SyncWorker.syncNow(context, sources)

    override fun requestTeamsSync(): TeamsAnswer? = TeamsProvider.call(context.contentResolver, TeamsProvider.METHOD_REQUEST_SYNC)
        ?.let { TeamsAnswer(it.getBoolean(TeamsProvider.RESULT_STARTED), it.getString(TeamsProvider.RESULT_REASON)) }

    override fun watchTeams(onChange: () -> Unit) {
        context.contentResolver.registerContentObserver(
            TeamsProvider.root,
            true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = onChange()
            },
        )
    }

    override fun calendarAllowed(): Boolean = CalendarReader.allowed(context)

    override fun readCalendar(from: Long, to: Long, excluded: Set<Long>): List<CalendarEvent> = CalendarReader.read(context, from, to, excluded)

    override suspend fun redraw() = WidgetUpdater.update(context)
}
