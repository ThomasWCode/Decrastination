package com.thomaswcode.decrastination.felix


/**
 * What the chat's answer asks for before it's his: a web search (16 Oct) and tasks' whole texts
 * (D-93), each round asking him again with what it brought. Pure.
 */
object ChatRounds {
    /** The next round: what to search for, if anything, and the tasks to read, all of them so far. */
    data class Next(val search: String?, val read: List<String>)

    /**
     * After [reading]: a search where it asks for one and none has been made or tried ([searched]),
     * while searches are on ([searches]); and the tasks it asks to read added to those [read] already,
     * [ChatPrompts.MAX_READ] at most in all. Null with nothing new to ask for: his answer is that one.
     */
    fun next(reading: ChatAnswers.Reading, searched: Boolean, searches: Boolean, read: List<String>): Next? {
        val search = reading.research?.takeIf { !searched && searches }
        val more = (read + reading.details).distinct().take(ChatPrompts.MAX_READ)
        if (search == null && more.size == read.size) return null
        return Next(search, more)
    }

    /** The most rounds a message takes after his first answer: a search and the texts, or each after the other. */
    const val MAX_ROUNDS = 2
}
