package com.thomaswcode.decrastination.felix


/**
 * What the chat's answer asks for before it's his: a web search (16 Oct), tasks' whole texts (D-93),
 * the week's numbers and the calendar past the fortnight (G6, G7: 8c), each round asking him again
 * with what it brought. Pure.
 */
object ChatRounds {
    /**
     * The next round: what to search for, if anything, and the tasks to read, all of them so far; whether the
     * week's numbers come, and which of the calendar ([ChatAnswers.Reading.calendar]), each as it stands so far.
     */
    data class Next(val search: String?, val read: List<String>, val stats: Boolean = false, val calendar: String? = null)

    /**
     * After [reading]: a search where it asks for one and none has been made or tried ([searched]),
     * while searches are on ([searches]); the tasks it asks to read added to those [read] already,
     * [ChatPrompts.MAX_READ] at most in all; the week's numbers where it asks for them and they haven't
     * come ([stats]); and the calendar it asks for where none has come ([calendar]), each brought once. Null
     * with nothing new to ask for: his answer is that one.
     */
    fun next(reading: ChatAnswers.Reading, searched: Boolean, searches: Boolean, read: List<String>, stats: Boolean = false, calendar: String? = null): Next? {
        val search = reading.research?.takeIf { !searched && searches }
        val more = (read + reading.details).distinct().take(ChatPrompts.MAX_READ)
        val week = stats || reading.stats
        val month = calendar ?: reading.calendar
        if (search == null && more.size == read.size && week == stats && month == calendar) return null
        return Next(search, more, week, month)
    }

    /** The most rounds a message takes after his first answer: a search and the texts, or each after the other. */
    const val MAX_ROUNDS = 2
}
