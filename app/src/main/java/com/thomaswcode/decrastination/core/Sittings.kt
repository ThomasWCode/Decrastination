package com.thomaswcode.decrastination.core

/**
 * Work over an hour in sittings, by time, never by what's in it (your call, 17 Oct): even, none
 * longer than [MAX_MIN], the first the work itself, then "Continue: …", and the last "Continue and
 * finish: …". Claude's steps are cut so as they're read
 * ([com.thomaswcode.decrastination.enrich.Answers.fitParts]); the planner cuts a step still over an
 * hour as it plans it the same way (the calibration's minutes, yours shared out, a step read before
 * sittings: 3.1.2's review). Never steps you gave, typed or Felix's: they're as you gave them,
 * however long ([TaskItem.yourSteps], your call, 17 Oct).
 */
object Sittings {
    /** The longest a sitting is (your call, 17 Oct: an hour; three before). */
    const val MAX_MIN = 60

    const val CONTINUE = "Continue: "
    const val FINISH = "Continue and finish: "

    /** How many sittings [minutes] take: one for an hour or less. */
    fun count(minutes: Int): Int = ((minutes + MAX_MIN - 1) / MAX_MIN).coerceAtLeast(1)

    /** [minutes] in [count] even sittings, the odd minutes first: 100 in two is 50 and 50; 125 in three, 42, 42 and 41. */
    fun even(minutes: Int, count: Int): List<Int> = List(count) { i -> minutes / count + if (i < minutes % count) 1 else 0 }

    /** The work a sitting's [title] names, without a sitting's words. */
    fun work(title: String): String = title.removePrefix(FINISH).removePrefix(CONTINUE)

    /**
     * Whether a step titled [title] ends its work, so its last sitting finishes it: a last sitting,
     * or a step of its own; not a sitting carried on after it, as one is where it's the first and
     * [next], the step after it, carries it on.
     */
    fun finishes(title: String, next: String?): Boolean = when {
        title.startsWith(FINISH) -> true
        title.startsWith(CONTINUE) -> false
        else -> next == null || work(next) == next || !title.startsWith(work(next))
    }

    /**
     * The titles of a step titled [title] in [count] sittings: the first its own (a last sitting's
     * carried on), the last "Continue and finish: …" where it [finishes] its work, the others
     * "Continue: …".
     */
    fun titles(title: String, count: Int, finishes: Boolean): List<String> = List(count) { i ->
        when {
            i == 0 && !title.startsWith(FINISH) -> title
            i == count - 1 && finishes -> FINISH + work(title)
            else -> CONTINUE + work(title)
        }
    }
}
