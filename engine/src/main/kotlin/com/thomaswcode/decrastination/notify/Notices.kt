package com.thomaswcode.decrastination.notify

/**
 * What a service needs of notifications (docs/plan_v4/architecture.md §Ports): one cancelled by its
 * [NoticeId], one of many of a kind told apart by a tag; whether a channel's reach the screen; and what
 * was held back posted. The phone's `Notifier` is the app's; a test's keeps a list.
 */
interface Notices {
    fun cancel(id: NoticeId, tag: String? = null)

    /** Whether [channel]'s notifications reach the screen: allowed, and the channel not switched off. */
    fun shown(channel: String): Boolean

    /** What was held back (LR-07), posted now, as it was made. */
    suspend fun postHeld()
}
