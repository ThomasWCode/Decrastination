package com.thomaswcode.decrastination.felix


/**
 * What the chat's answer asks for before it's his: a web search, tasks' whole texts (D-93),
 * the week's numbers and the calendar past the fortnight (G6, G7), each round asking him again
 * with what it brought. Pure.
 */
object ChatRounds {
    /**
     * The next round: what to search for, if anything, and the tasks to read, all of them so far; whether the
     * week's numbers come, and the spans of the calendar that do ([ChatAnswers.Reading.calendar]), all so far; the photos kept that
     * he sees again (6.8.0), all so far.
     */
    data class Next(val search: String?, val read: List<String>, val stats: Boolean = false, val calendar: List<String> = emptyList(), val see: List<String> = emptyList())

    /**
     * After [reading]: a search where it asks for one and none has been made or tried ([searched]),
     * while searches are on ([searches]); the tasks it asks to read added to those [read] already,
     * [ChatPrompts.MAX_READ] at most in all; the week's numbers where it asks for them and they haven't
     * come ([stats]); and a span of the calendar it asks for added to those that have ([calendar]), [MAX_SPANS]
     * at most, each brought once; the photos kept he asks to see added to those [seen], [ChatPrompts.MAX_PICTURES] at most (6.8.0).
     * Null with nothing new to ask for: his answer is that one.
     */
    fun next(reading: ChatAnswers.Reading, searched: Boolean, searches: Boolean, read: List<String>, stats: Boolean = false, calendar: List<String> = emptyList(), seen: List<String> = emptyList()): Next? {
        val search = reading.research?.takeIf { !searched && searches }
        val more = (read + reading.details).distinct().take(ChatPrompts.MAX_READ)
        val week = stats || reading.stats
        val spans = (calendar + listOfNotNull(reading.calendar)).distinct().take(MAX_SPANS)
        val photos = (seen + reading.see).distinct().take(ChatPrompts.MAX_PICTURES)
        if (search == null && more.size == read.size && week == stats && spans.size == calendar.size && photos.size == seen.size) return null
        return Next(search, more, week, spans, photos)
    }

    /** The calendar's spans a message can be brought: a month, then another, or the four weeks and a month. */
    const val MAX_SPANS = 2

    /**
     * Whether [reading] still asks for something it hasn't been brought, past what [next] brings: a search not
     * made nor tried while searches are on, a task's text not read (a fourth among them), the week's numbers, a span
     * of the calendar (a third among them), a photo not seen again (6.8.0). Said under his answer, so his "looking" isn't passed off as one.
     */
    fun asking(reading: ChatAnswers.Reading, searched: Boolean, searches: Boolean, read: List<String>, stats: Boolean, calendar: List<String>, seen: List<String> = emptyList()): Boolean =
        (reading.research != null && !searched && searches) || reading.details.any { it !in read } || (reading.stats && !stats) || (reading.calendar != null && reading.calendar !in calendar) ||
            reading.see.any { it !in seen }

    /** The most rounds a message takes after his first answer: what it asks for (a search, texts, the week's numbers, the calendar), together or one after another. */
    const val MAX_ROUNDS = 2
}
