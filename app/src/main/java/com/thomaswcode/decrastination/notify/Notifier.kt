package com.thomaswcode.decrastination.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.thomaswcode.decrastination.block.BlockPolicy
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.HeldNotification
import com.thomaswcode.decrastination.data.HeldTap
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What a service needs of notifications (docs/plan_v4/architecture.md §Ports): one posted
 * or cancelled by its [NoticeId], one of many of a kind told apart by a tag; whether a channel's reach
 * the screen; and what was held back posted. [Notifier] is the app's; a test's keeps a list.
 */
interface Notices {
    fun post(id: NoticeId, notification: Notification, tag: String? = null)

    fun cancel(id: NoticeId, tag: String? = null)

    /** Whether [channel]'s notifications reach the screen: allowed, and the channel not switched off. */
    fun shown(channel: String): Boolean

    /** What was held back (LR-07), posted now, as it was made. */
    suspend fun postHeld()
}

/**
 * Every notification the app posts goes through here: only when they're allowed (a runtime permission
 * since Android 13), the routine ones within their ceiling and outside quiet hours ([NotifyRules],
 * LR-07), the rest held for the next morning's briefing ([postHeld]), made again with their tap and
 * buttons ([Notify.rebuild]). The one place Android's notification manager is called is [tray].
 */
class Notifier(
    private val tray: Tray,
    private val runtime: JsonStore<RuntimeState>,
    private val settings: JsonStore<Settings>,
    private val clock: WallClock,
    /** Where the count and the held list are written, after the decision. */
    private val scope: CoroutineScope,
) : Notices {

    /** What Android does with notifications: [AndroidTray] on the phone, a list in a test. */
    interface Tray {
        /** Whether the app may post at all (the permission, or notifications switched on). */
        fun allowed(): Boolean

        /** Whether [channel]'s reach the screen: allowed, and the channel not switched off (one not made yet is made on). */
        fun shown(channel: String): Boolean

        fun notify(id: Int, tag: String?, notification: Notification)

        fun cancel(id: Int, tag: String?)

        fun cancelAll()

        /** The notifications showing now, by id and tag. */
        fun active(): List<Pair<Int, String?>>

        /** [notification] as a held one keeps it: its channel, title, words and taps. Null with no channel. */
        fun read(notification: Notification): Read?

        /** [held] made again to post, its tap and buttons with it. */
        fun rebuilt(held: HeldNotification): Notification
    }

    /** What a notification is, as holding it back needs: its [channel], and what it says and does. */
    data class Read(val channel: String, val title: String, val text: String, val taps: List<HeldTap>)

    /** Whether the app may post at all. */
    fun allowed(): Boolean = tray.allowed()

    override fun shown(channel: String): Boolean = tray.shown(channel)

    override fun post(id: NoticeId, notification: Notification, tag: String?) {
        if (tray.allowed() && !held(id, tag, notification)) tray.notify(id.id, tag, notification)
    }

    /**
     * [id]'s notification of [tag] taken away: from the tray, and, held back for the morning (LR-07), from
     * what the briefing posts, so one out of date (a key mended, a task finished) doesn't come back then.
     * Ordered with a hold still being written ([held]): one decided before this cancel and not yet written finds the
     * cancel and writes nothing, and one being written as it comes (the file replaced, the state not yet changed) is
     * taken out by this one's own write after it, which comes even when nothing's held yet, as the store's lock takes
     * them in turn; a write that changes nothing writes nothing.
     */
    override fun cancel(id: NoticeId, tag: String?) {
        tray.cancel(id.id, tag)
        val key = id.id to tag
        synchronized(counting) { cancelled[key] = ++decisions }
        scope.launch { runtime.update { s -> s.copy(heldNotifications = s.heldNotifications.filterNot { it.id == id.id && it.tag == tag }) } }
    }

    /** The tags of [id]'s notifications held back for the morning (LR-07). */
    fun heldTags(id: NoticeId): List<String?> = runtime.value.heldNotifications.filter { it.id == id.id }.map { it.tag }

    /** Every notification of the app's gone (the debug `clean-up`). */
    fun cancelAll() = tray.cancelAll()

    /**
     * The notifications of [ids], no longer made: one still showing from before taken away, whatever
     * its tag, and one held back for the morning (LR-07) let go, so neither's tap names what's gone.
     */
    suspend fun retire(ids: Set<NoticeId>) {
        val numbers = ids.mapTo(HashSet()) { it.id }
        tray.active().filter { it.first in numbers }.forEach { (id, tag) -> tray.cancel(id, tag) }
        if (runtime.value.heldNotifications.any { it.id in numbers }) runtime.update { s -> s.copy(heldNotifications = s.heldNotifications.filterNot { it.id in numbers }) }
    }

    /** The tags of [id]'s notifications showing now. */
    fun showing(id: NoticeId): List<String?> = tray.active().filter { it.first == id.id }.map { it.second }

    /** Held while a routine one's ceiling is decided and counted, so two at once don't both take the last place. */
    private val counting = Any()

    /** Today's count as this run has it: ahead of the file, which is written after. */
    private var count: Pair<String, Int>? = null

    /** Each hold and cancel numbered as it's decided, under [counting], so a hold's write knows a cancel came after it. */
    private var decisions = 0L

    /** The number of the last cancel of each id and tag. */
    private val cancelled = HashMap<Pair<Int, String?>, Long>()

    /**
     * Whether [notification] is held back (LR-07): one of the routine channels' in quiet hours, or
     * past today's ceiling, decided and counted in one step. Held, its words, tap and buttons are
     * kept for the next briefing; gone, it's counted. One on a channel you've switched off isn't
     * shown, so it's neither.
     */
    private fun held(id: NoticeId, tag: String?, notification: Notification): Boolean {
        val read = tray.read(notification) ?: return false
        val channel = read.channel
        if (channel !in NotifyRules.CEILINGED || !tray.shown(channel)) return false
        val now = clock.now()
        val date = Instant.ofEpochMilli(now).atZone(clock.zone()).toLocalDate().toString()
        val quiet = BlockPolicy.isQuiet(now, clock.zone(), settings.value)
        val goes: Boolean
        val sent: Int
        val decided: Long
        synchronized(counting) {
            val before = count?.takeIf { it.first == date }?.second ?: (runtime.value.notifiedToday[date] ?: 0)
            goes = NotifyRules.goesNow(channel, before, quiet)
            sent = if (goes) before + 1 else before
            count = date to sent
            decided = ++decisions
        }
        scope.launch {
            runtime.update { s ->
                if (goes) {
                    s.copy(notifiedToday = mapOf(date to maxOf(sent, s.notifiedToday[date] ?: 0)))
                } else if (synchronized(counting) { (cancelled[id.id to tag] ?: 0L) > decided }) {
                    // Cancelled since it was held: not kept for the morning.
                    s
                } else {
                    s.copy(heldNotifications = (s.heldNotifications.filterNot { it.id == id.id && it.tag == tag } + HeldNotification(channel, id.id, tag, now, read.title, read.text, read.taps)).takeLast(NotifyRules.MAX_HELD))
                }
            }
        }
        return !goes
    }

    /**
     * What was held back (LR-07), posted now (the morning's briefing) as it was made, its tap and
     * buttons with it (one with none kept opens the app), outside the ceiling; then let go. Not while
     * notifications aren't allowed: then kept for the next briefing, not lost.
     */
    override suspend fun postHeld() {
        val held = runtime.value.heldNotifications
        if (held.isEmpty() || !tray.allowed()) return
        for (h in held) tray.notify(h.id, h.tag, tray.rebuilt(h))
        runtime.update { s -> s.copy(heldNotifications = s.heldNotifications - held.toSet()) }
    }
}

/** The phone's notifications: Android's manager, called here and nowhere else. */
class AndroidTray(private val context: Context) : Notifier.Tray {
    private val manager: NotificationManagerCompat get() = NotificationManagerCompat.from(context)

    override fun allowed(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            manager.areNotificationsEnabled()
        }

    override fun shown(channel: String): Boolean {
        if (!allowed()) return false
        if (!manager.areNotificationsEnabled()) return false
        // Not made yet: it's made switched on, at its first use.
        val made = manager.getNotificationChannelCompat(channel) ?: return true
        return made.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    // Notifier.post checks allowed() first.
    @SuppressLint("MissingPermission")
    override fun notify(id: Int, tag: String?, notification: Notification) {
        if (tag != null) manager.notify(tag, id, notification) else manager.notify(id, notification)
    }

    override fun cancel(id: Int, tag: String?) {
        if (tag != null) manager.cancel(tag, id) else manager.cancel(id)
    }

    override fun cancelAll() = manager.cancelAll()

    override fun active(): List<Pair<Int, String?>> =
        context.getSystemService(NotificationManager::class.java)?.activeNotifications.orEmpty().map { it.id to it.tag }

    override fun read(notification: Notification): Notifier.Read? {
        val channel = notification.channelId ?: return null
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        return Notifier.Read(channel, title, text, Notify.taps(notification))
    }

    override fun rebuilt(held: HeldNotification): Notification = Notify.rebuild(context, held)
}
