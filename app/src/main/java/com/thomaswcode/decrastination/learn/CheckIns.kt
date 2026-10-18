package com.thomaswcode.decrastination.learn

import android.content.Context
import com.thomaswcode.decrastination.AppGraph

/** The Sunday check-in's reminder (docs/spec/learning.md, item 6). */
object CheckIns {
    /** [Conversation.remindCheckIn], till its callers call it there. */
    suspend fun remind(context: Context) = AppGraph.get(context).conversation.remindCheckIn()
}
