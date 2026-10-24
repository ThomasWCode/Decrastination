package com.thomaswcode.decrastination.felix

/**
 * The lines the app says in Felix's name with no call made (docs/spec/felix-inputs.md §In his name, but not his words: a message
 * of his `byApp`), in one place, in his register (docs/spec/felix.md §Who he is, D-57), so the app's
 * words in his bubble don't jar against his own. Pure.
 */
object FelixWords {
    /** The Sunday check-in's five questions (LR-02), one a message, as the form asks them. */
    val CHECK_IN = listOf(
        "ok so, 1 to 5, how was the week?",
        "what did you put off, and why?",
        "what got in the way?",
        "what would you do differently next week?",
        "when did you have the most energy?",
    )

    /** Before the first question again, when its answer wasn't a number from 1 to 5. */
    const val CHECK_IN_AGAIN = "need a number for that one. "

    /** After the check-in's last answer. */
    const val CHECK_IN_THANKS = "nice, the week's review is next."

    /** The week's summary written (NF-02), with its button to send it (which opens your email app: he doesn't send it). */
    const val SUMMARY_READY = "the week's summary's ready. send it?"

    /** How [title], finished, went (LR-05), asked with Claude off: the three answers its replies. */
    fun howItWent(title: String): String = "done: $title. how'd it go?"

    /** A calendar event the plan can't judge, [title] on [day] (Q7): how much of it it takes, its three answers the replies. The event's name as your calendar has it. */
    fun eventQuestion(title: String, day: String): String = "$title, ${day.lowercase()}: how much of your day does that take? the plan leaves you that much less."

    /** The morning's list when he has no words for it (LR-04): the day's [title], then its [lines]. */
    fun morningList(title: String, lines: List<String>): String = (listOf(title) + lines).joinToString("\n")

    /** A reminder of yours, [words] as you set them, where he has no words of his own for it: never nothing. */
    fun reminder(words: String): String = "reminder: $words"

    /** After a reminder that comes late (the phone off through it), the [time] it was for. */
    fun late(time: String): String = "(late: it was for $time)"

    /** A reminder of yours, [words], let go before it came, as its task, [title], [why]. */
    fun letGo(words: String, title: String, why: String): String = "your reminder “$words” is off: “$title” $why."

    /** A reminder of yours, [words], let go and back, as its task, [title], [why]. */
    fun back(words: String, title: String, why: String): String = "your reminder “$words” is back on: “$title” $why."

    /** His line under an answer that asked [source] to read (G2): no Undo, as a read takes nothing back. */
    fun reading(source: String): String = "Asked $source to read"

    /** Teams's, with the chat on screen (D-11): its widget asked to sync it, as a tap on its refresh asks it. */
    const val TEAMS_SYNCING = "Asked Teams to read, the Teams widget syncing it now"

    /** Teams's where its widget's sync wasn't started, [why]: what the widget has. */
    fun readingWidget(why: String): String = "Asked Teams to read what the Teams widget has: $why"

    /** [source]'s read of [title], as it changed (G2): "Teams says “Forces worksheet” is handed in." */
    fun read(source: String, title: String, what: String): String = "$source says “$title” $what."

    /** A reminder's replies (D-100): it's done, or it comes again in 15 minutes or an hour, each with no call; taken whatever their case. */
    const val REMINDER_DONE = "done"
    const val REMINDER_SNOOZE = "snooze 15 min"
    const val REMINDER_SNOOZE_HOUR = "snooze 1 h"
    val REMINDER_REPLIES = listOf(REMINDER_DONE, REMINDER_SNOOZE, REMINDER_SNOOZE_HOUR)

    /**
     * How work went (LR-05), as his chips say it, each beside the answer the app keeps (`Assessment.ANSWERS`'
     * words, which a reply from before, or typed, may still be): taken in either form, whatever the case ([chip]).
     */
    private val HOW_IT_WENT = listOf("harder" to "Harder", "about right" to "As expected", "easier" to "Easier")

    /** How work went's chips, in his register. */
    val HOW_IT_WENT_REPLIES: List<String> = HOW_IT_WENT.map { it.first }

    /** [words] as the answer the app keeps where they're one of his chips' ("about right" is "As expected"), else as they are. */
    fun chip(words: String): String = words.trim().let { said -> HOW_IT_WENT.firstOrNull { it.first.equals(said, ignoreCase = true) }?.second ?: said }

    /** A calendar event's question's chips (Q7), in his register: the answers `CalendarTime.ANSWERS` keeps, taken whatever their case. */
    fun eventReplies(answers: List<String>): List<String> = answers.map { it.lowercase() }
}
