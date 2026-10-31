package com.thomaswcode.decrastination.felix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.sync.protocol.ReplyFromNotification
import kotlinx.coroutines.launch

/**
 * A reply from Felix's notification (AI-24), typed or one of his: kept in the chat under the message it
 * answers and routed by its kind, as any reply is ([Conversation.replyFromNotification]); a chat turn's
 * answer comes as his next message, made as work of its own ([FelixReplyWorker]).
 */
class FelixReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = AppGraph.get(context)
        val pending = goAsync()
        graph.scope.launch {
            try {
                if (intent.action == FelixNotifications.ACTION_REPLY) {
                    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(FelixNotifications.KEY_REPLY)?.toString()?.trim()
                    val id = intent.getStringExtra(FelixNotifications.EXTRA_ID)
                    // Only the reply's own handling waits here; the answer comes after, by itself.
                    if (!text.isNullOrEmpty() && id != null) graph.act(ReplyFromNotification(text, id))
                }
            } catch (e: Exception) {
                Log.w(AppGraph.TAG, "Felix's notification action failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
