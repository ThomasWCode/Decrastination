package com.thomaswcode.decrastination.notify

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.ui.SettingsActivity

/** The server's alert that the storage nears a free allowance's end (6.10.0, D-161): a notification of its own, its tap Settings' Storage. */
object StorageNotice {
    fun show(context: Context, words: String) {
        val open = Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.STORAGE_PAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        AppGraph.get(context).notifier.post(
            NoticeId.Storage,
            Notify.builder(context, Channels.STORAGE)
                .setContentTitle("Storage")
                .setContentText(words)
                .setStyle(NotificationCompat.BigTextStyle().bigText(words))
                .let { Notify.opening(it, context, NoticeId.Storage.code(), open) }
                .setAutoCancel(true)
                .build(),
        )
    }
}
