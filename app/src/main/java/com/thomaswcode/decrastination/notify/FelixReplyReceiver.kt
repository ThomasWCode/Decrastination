package com.thomaswcode.decrastination.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.thomaswcode.decrastination.AppGraph
import kotlinx.coroutines.launch

/**
 * What's done from Felix's notification (AI-24): a reply, typed or one of his, goes on in the chat
 * ([AppGraph.replyToFelix], whose answer comes as his next notification, made as work of its own,
 * [FelixReplyWorker]); a card's button adds the task or applies the changes, as the chat's Add and
 * Apply do.
 */
class FelixReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = AppGraph.get(context)
        val pending = goAsync()
        graph.scope.launch {
            try {
                when (intent.action) {
                    FelixNotifications.ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(FelixNotifications.KEY_REPLY)?.toString()?.trim()
                        val id = intent.getStringExtra(FelixNotifications.EXTRA_ID)
                        // Only the reply's own handling waits here; the answer comes after, by itself.
                        if (!text.isNullOrEmpty() && id != null) graph.replyToFelix(text, id)
                    }
                    FelixNotifications.ACTION_CARD -> {
                        val message = intent.getStringExtra(FelixNotifications.EXTRA_MESSAGE)
                        val card = intent.getStringExtra(FelixNotifications.EXTRA_CARD)
                        if (message != null && card != null) {
                            graph.applyProposal(message, card)
                            FelixNotifications.cancel(context)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(AppGraph.TAG, "Felix's notification action failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
