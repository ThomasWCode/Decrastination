package com.thomaswcode.decrastination.server

import com.interaso.webpush.VapidKeys
import com.interaso.webpush.WebPush
import com.interaso.webpush.WebPushService
import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.views.PushView
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A browser asking to be told: where its push service takes a message, what it's sealed for, and of what. */
@Serializable
data class PushSubscription(val endpoint: String, val p256dh: String, val auth: String, val channels: Set<String>)

/** Which browser's meant. */
@Serializable
data class PushEndpoint(val endpoint: String)

/** What a browser's asked to be told of. */
@Serializable
data class PushChannels(val channels: Set<String>)

/** What a browser shows: [url] is where a tap goes, and one of the same [tag] takes the place of the last. */
@Serializable
data class PushMessage(val title: String, val body: String, val url: String, val tag: String)

/**
 * Telling a closed tab (W-9): the browsers that asked, each for what it asked, kept here and not in a store, as a
 * subscription is one browser's and nothing the phone holds or a backup should carry. Nothing's sent without a [sender],
 * the box's VAPID keys. A send goes on its own time, never in a command, as a push service can take seconds.
 */
class Pushes(private val db: Database, private val scope: CoroutineScope, private val sender: Sender?, private val clock: () -> Long = System::currentTimeMillis) {
    /** Hands a message to a browser's push service. */
    fun interface Sender {
        /** [payload] to [subscription]: false if that subscription's gone for good. */
        fun send(subscription: PushSubscription, payload: String): Boolean
    }

    private val json = Json

    val on: Boolean get() = sender != null

    /** [given] kept, for what's known of what it asks: false, and nothing kept, if it isn't a push service's address. */
    fun keep(given: PushSubscription): Boolean {
        if (!fit(given.endpoint)) return false
        db.transaction { c ->
            c.prepareStatement(
                """INSERT INTO push_subscriptions(endpoint, p256dh, auth, channels, created_at) VALUES(?, ?, ?, ?, ?)
                   ON CONFLICT(endpoint) DO UPDATE SET p256dh = excluded.p256dh, auth = excluded.auth, channels = excluded.channels""",
            ).use { q ->
                q.setString(1, given.endpoint)
                q.setString(2, given.p256dh)
                q.setString(3, given.auth)
                q.setString(4, given.channels.filter { it in KNOWN }.joinToString(","))
                q.setLong(5, clock())
                q.executeUpdate()
            }
        }
        return true
    }

    fun forget(endpoint: String) {
        db.transaction { c ->
            c.prepareStatement("DELETE FROM push_subscriptions WHERE endpoint = ?").use { q ->
                q.setString(1, endpoint)
                q.executeUpdate()
            }
        }
    }

    /** What the browser at [endpoint] asked for: nothing, if it isn't one that asked. */
    fun channels(endpoint: String): Set<String> = subscriptions().firstOrNull { it.endpoint == endpoint }?.channels.orEmpty()

    private fun subscriptions(): List<PushSubscription> = db.read { c ->
        c.prepareStatement("SELECT endpoint, p256dh, auth, channels FROM push_subscriptions").use { q ->
            q.executeQuery().use { r ->
                buildList {
                    while (r.next()) add(PushSubscription(r.getString(1), r.getString(2), r.getString(3), r.getString(4).split(",").filter(String::isNotEmpty).toSet()))
                }
            }
        }
    }

    /** [message] to each browser that asked for [channel]. One that's gone is let go; one that fails is only logged, by kind, its address being as good as a password. */
    fun push(channel: String, message: PushMessage) {
        val sender = sender ?: return
        val payload = json.encodeToString(message)
        scope.launch(Dispatchers.IO) {
            subscriptions().filter { channel in it.channels }.forEach { one ->
                try {
                    if (!sender.send(one, payload)) forget(one.endpoint)
                } catch (failed: Exception) {
                    Log.w(Log.TAG, "A push failed: ${failed::class.simpleName}")
                }
            }
        }
    }

    companion object {
        const val FELIX = PushView.FELIX
        const val SLOTS = PushView.SLOTS
        const val TIMER = PushView.TIMER
        private val KNOWN = PushView.CHANNELS.map { it.key }.toSet()

        /** What a push service's address runs to at most. */
        private const val MAX_ENDPOINT = 1024

        /** How much of his words a browser's sent: it shows no more. */
        private const val MAX_WORDS = 300

        /** [text] as a browser can show it: whole sentences, as far as they go, or whole words where one runs on past that. */
        fun words(text: String): String {
            if (text.length <= MAX_WORDS) return text
            val room = text.take(MAX_WORDS)
            val end = room.indexOfLast { it in ".?!" }
            return if (end > 0) room.take(end + 1) else room.substringBeforeLast(' ') + "…"
        }

        /**
         * Whether [endpoint] could be a push service's: https, and a name, as the server posts to whatever it's given, so never
         * an address of its own or of what's beside it.
         */
        fun fit(endpoint: String): Boolean {
            if (endpoint.length > MAX_ENDPOINT) return false
            val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
            val host = uri.host ?: return false
            return uri.scheme == "https" && '.' in host && host.any(Char::isLetter) && ':' !in host && !host.endsWith(".internal") && !host.endsWith(".local")
        }
    }
}

/** The real thing: the box's keys ([keys]) and the sender's name ([subject]), through its push service. */
class WebPushSender(subject: String, private val keys: VapidKeys) : Pushes.Sender {
    private val service = WebPushService(subject, keys)

    /** The public key, as a browser's `applicationServerKey`. */
    val applicationKey: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(keys.applicationServerKey)

    override fun send(subscription: PushSubscription, payload: String): Boolean =
        service.send(payload, subscription.endpoint, subscription.p256dh, subscription.auth, TTL_SECONDS) != WebPush.SubscriptionState.EXPIRED

    private companion object {
        /** A day: what's told after that is no use. */
        const val TTL_SECONDS = 86_400
    }
}
