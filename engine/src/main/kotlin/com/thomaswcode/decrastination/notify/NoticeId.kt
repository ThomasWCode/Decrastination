package com.thomaswcode.decrastination.notify

/**
 * Every notification the app makes, named in one place (docs/plan_v4/architecture.md §`Notifier`): its [id],
 * and the request codes its tap and buttons take ([codes]), a range of its own, so no two pending
 * intents can collide. Ids that stay
 * keep a notification already showing where it is through an update; those retired ([RETIRED]) are
 * named still, so one left showing from before is taken away.
 */
enum class NoticeId(val id: Int, val codes: IntRange = IntRange.EMPTY) {
    /** Work under way, shown by WorkManager (on older Android versions): a sync, readings, the review, a reply answered, Felix's look, your reminder's words. */
    Sync(1001),
    Readings(1002),
    ReviewWork(1003),
    ReplyWork(1004),
    FelixWork(1005),
    ReminderWork(1006),

    /** Free time being spent, and its last minute (IMP-P2-011): each one's *Back to work*. */
    FreeTime(2003, 2003..2003),
    FreeTimeEnding(2004, 2004..2004),

    /** A timer on a piece (D-112): the running one's tap and its *Stop*; the "time's up" one's tap. */
    Timer(2005, 2005..2006),
    TimerOver(2007, 2007..2007),

    /** The watchdog's: blocking or protection off. */
    Protection(3001, 3001..3001),

    /** The check-in's form reminder: its tap. The morning's list, the week's review and its summary, retired: Felix's messages. */
    Briefing(4001, 4001..4001),
    CheckIn(4002, 4002..4002),
    Review(4003, 4003..4003),
    Summary(4004, 4004..4004),

    /** "How was it?" with Claude off, one a completion (tagged), retired: Felix's message. */
    Assessment(4100, 4100..4103),

    /** Felix's message, one at a time, retired: his conversation is one notification ([Felix]). */
    FelixMessage(4200, 4200..4202),

    /** A move of yours in the calendar put back, one a slot (tagged). */
    CalendarMove(4300),

    /** The calendar's questions, one an event (tagged), retired: Felix's messages. */
    CalendarQuestion(4400, 4400..4403),

    /** A write to Gmail given up, one a conversation (tagged by its thread, D-106): its tap opens the task's sheet. */
    GmailWrite(4500, 4500..4509),

    /** Claude's plan for a task dropped (tagged), and its key not working: each one's tap. */
    ModelDropped(5001, 5001..5001),
    ModelKey(5002, 5002..5002),

    /** His conversation, one notification holding his unread messages: its tap, and its reply with his replies as choices. */
    Felix(6000, 6001..6099),
    ;

    /** The [index]th of [codes] (from 0): a pending intent's request code of this notification's own. */
    fun code(index: Int = 0): Int {
        val code = codes.first + index
        require(index >= 0 && code in codes) { "$name has no request code $index" }
        return code
    }

    companion object {
        /** No longer made, as each is Felix's message in the chat now: taken away at start where one's left ([Notifier.retire]). */
        val RETIRED: Set<NoticeId> = setOf(Briefing, Review, Summary, Assessment, FelixMessage, CalendarQuestion)
    }
}
