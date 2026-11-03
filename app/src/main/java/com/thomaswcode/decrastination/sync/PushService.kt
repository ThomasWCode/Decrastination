package com.thomaswcode.decrastination.sync

import android.annotation.SuppressLint
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.thomaswcode.decrastination.AppGraph
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
        if (replica.paired) ReplicaWorker.now(applicationContext)
    }

    /** The server's said it's changed: what's there is pulled by work of its own, as this has only seconds. */
    override fun onMessageReceived(message: RemoteMessage) {
        val revision = message.data["revision"]?.toLongOrNull() ?: return
        Log.i(AppGraph.TAG, "The server says it's at revision $revision")
        if (AppGraph.get(applicationContext).replica.paired) ReplicaWorker.told(applicationContext, revision)
    }
}
