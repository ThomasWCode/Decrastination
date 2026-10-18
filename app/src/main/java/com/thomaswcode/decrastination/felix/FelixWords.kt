package com.thomaswcode.decrastination.felix

/**
 * The lines the app says in Felix's name with no call made (docs/plan_v4/05-the-chat.md §1: a message
 * of his `byApp`), in one place, as they are; his register reaches them with his prompts' (Stage 10b).
 * Pure.
 */
object FelixWords {
    /** The Sunday check-in's five questions (LR-02), one a message, as the form asks them. */
    val CHECK_IN = listOf(
        "How was the week, 1 to 5?",
        "What did you avoid, and why?",
        "What got in the way?",
        "What would you change?",
        "When did you have most energy?",
    )

    /** Before the first question again, when its answer wasn't a number from 1 to 5. */
    const val CHECK_IN_AGAIN = "A number from 1 to 5: "

    /** After the check-in's last answer. */
    const val CHECK_IN_THANKS = "Thanks: the week's review comes next."

    /** The week's summary written (NF-02), with its button to send it. */
    const val SUMMARY_READY = "The week's summary is ready; send it?"


    /** How [title], finished, went (LR-05), asked with Claude off: the three answers its replies. */
    fun howItWent(title: String): String = "Done: $title. How was it?"

    /** A calendar event the plan can't judge, [title] on [day] (Q7): how much of it it takes, its three answers the replies. */
    fun eventQuestion(title: String, day: String): String = "$title, $day: how much of the day does it take? The plan leaves you that much less time."

    /** The morning's list when he has no words for it (LR-04): the day's [title], then its [lines]. */
    fun morningList(title: String, lines: List<String>): String = (listOf(title) + lines).joinToString("\n")
}
