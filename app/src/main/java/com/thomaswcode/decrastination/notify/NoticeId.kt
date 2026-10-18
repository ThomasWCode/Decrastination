package com.thomaswcode.decrastination.notify

/**
 * Every notification the app makes, named in one place (docs/plan_v4/05-the-chat.md §3): its [id],
 * and the request codes its tap and buttons take ([codes]), a range of its own, so no two pending
 * intents can collide. Those named ahead of the stage that uses them are said to be. Ids that stay
 * keep a notification already showing where it is through an update.
 */
enum class NoticeId(val id: Int, val codes: IntRange = IntRange.EMPTY) {
    /** Work under way, shown by WorkManager (on older Android versions): a sync, readings, the review, a reply answered, Felix's look. */
    Sync(1001),
    Readings(1002),
    ReviewWork(1003),
    ReplyWork(1004),
    FelixWork(1005),

    /** Free time being spent, and its last minute (IMP-P2-011): each one's *Back to work*. */
    FreeTime(2003, 2003..2003),
    FreeTimeEnding(2004, 2004..2004),

    /** The watchdog's: blocking or protection off. */
    Protection(3001, 3001..3001),

    /** The morning's list, the check-in's form reminder, the week's review and its summary: each one's tap. */
    Briefing(4001, 4001..4001),
    CheckIn(4002, 4002..4002),
    Review(4003, 4003..4003),
    Summary(4004, 4004..4004),

    /** "How was it?" with Claude off, one a completion (tagged): its tap and its three answers. */
    Assessment(4100, 4100..4103),

    /** Felix's message, one at a time: its tap and reply, and up to two cards' buttons. Retired when his conversation is one notification (Stage 5a). */
    FelixMessage(4200, 4200..4202),

    /** A move of yours in the calendar put back, one a slot (tagged). */
    CalendarMove(4300),

    /** The calendar's questions, one an event (tagged): their three answers. Retired when they're his messages (Stage 5a). */
    CalendarQuestion(4400, 4400..4403),

    /** A Gmail write that couldn't be made, one a conversation (tagged): its tap. Named ahead of Stage 11. */
    GmailWrite(4500, 4500..4509),

    /** Claude's plan for a task dropped (tagged), and its key not working: each one's tap. */
    ModelDropped(5001, 5001..5001),
    ModelKey(5002, 5002..5002),

    /** His conversation, one notification holding his unread messages: its tap, reply and choices. Named ahead of Stage 5a. */
    Felix(6000, 6001..6099),
    ;

    /** The [index]th of [codes] (from 0): a pending intent's request code of this notification's own. */
    fun code(index: Int = 0): Int {
        val code = codes.first + index
        require(index >= 0 && code in codes) { "$name has no request code $index" }
        return code
    }
}
