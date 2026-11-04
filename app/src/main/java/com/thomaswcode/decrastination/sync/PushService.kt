package com.thomaswcode.decrastination.sync

import android.annotation.SuppressLint
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Source
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Firebase Cloud Messaging (W-18): where it reaches this phone, kept and sent to the server, and the server's word of a change
 * this phone didn't make, which starts an exchange at once rather than at the next 15-minute sync (docs/spec/sync.md §Told at
 * once). Only in a build made with the Firebase project's file (D-145): in one without, Firebase isn't set up, and this never
 * hears anything.
 */
// Lint asks for onNewToken, which Messaging 26 marks deprecated: a phone registered by its installation id hears of that through onRegistered.
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class PushService : FirebaseMessagingService() {
    /** Firebase has registered this phone, by its installation id: as it does at a start now and then, and whenever the id changes. */
    override fun onRegistered(installationId: String) {
        val replica = AppGraph.get(applicationContext).replica
        runBlocking { replica.registered(installationId) }
        // Not `now`, which a send already on its way would take the place of: it may be past sending the id.
        if (replica.paired) ReplicaWorker.again(applicationContext)
    }

    /**
     * The server's said it's changed: what's there is pulled by work of its own, as this has only seconds. Or it's asked for this
     * phone's sources read now (Felix's `sync` act, D-142): read by work of its own too, and the Teams widget asked to sync where the
     * chat's on screen, as his act asks it here (D-11).
     */
    override fun onMessageReceived(message: RemoteMessage) {
        message.data["read"]?.let { names ->
            val graph = AppGraph.get(applicationContext)
            val sources = names.split(",").mapNotNullTo(HashSet()) { name -> Source.entries.firstOrNull { it.name == name } }
            Log.i(AppGraph.TAG, "The server asks for ${sources.size} sources read")
            if (sources.isEmpty() || !graph.replica.paired) return
            SyncWorker.syncNow(applicationContext, sources)
            if (Source.Teams in sources && graph.conversation.chatInFront) graph.scope.launch { graph.sync.requestTeamsSync() }
            return
        }
        val revision = message.data["revision"]?.toLongOrNull() ?: return
        Log.i(AppGraph.TAG, "The server says it's at revision $revision")
        if (AppGraph.get(applicationContext).replica.paired) ReplicaWorker.told(applicationContext, revision)
    }
}
