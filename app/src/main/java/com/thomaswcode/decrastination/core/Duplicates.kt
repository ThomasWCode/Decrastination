package com.thomaswcode.decrastination.core

import kotlin.math.abs

/**
 * The same work from two sources (DX-15): open tasks from different sources, of the same subject
 * (the class map's, or the same class), due within a day of each other (or both undated), whose
 * titles share most of their words. Said on each, to make one the other's with a *Same?* (a
 * [ChangeType.SameAs]); a pair said to be different isn't asked about again. Pure.
 */
object Duplicates {
    /** The share of the shorter title's words the other must hold too. */
    const val SHARE = 0.6
    private const val MIN_LETTERS = 3
    private const val DAY_MS = 24 * 3_600_000L
    private val WORD = Regex("""[\p{L}\d]+""")

    /** [title]'s words that count: lower-cased, of three letters or more. */
    fun words(title: String): Set<String> = WORD.findAll(title.lowercase()).map { it.value }.filter { it.length >= MIN_LETTERS }.toSet()

    /** [task]'s subject, as the class map names it or its class as it is. */
    fun subject(task: TaskItem, classMap: Map<String, String>): String? =
        task.className?.trim()?.takeIf { it.isNotEmpty() }?.let { code -> (classMap[code]?.trim()?.takeIf { it.isNotEmpty() } ?: code).lowercase() }

    /** What a pair's said by, whichever way round: for one said to be different. */
    fun key(a: String, b: String): String = listOf(a, b).sorted().joinToString("|")

    /** The open tasks that look like the same work, each pair once, but those in [different]. */
    fun candidates(tasks: List<TaskItem>, classMap: Map<String, String>, different: Collection<String> = emptyList()): List<Pair<TaskItem, TaskItem>> {
        val open = tasks.filter { it.isOpen && !it.hidden && !it.derived && it.source != Source.Anki }
        return open.flatMapIndexed { i, a -> open.drop(i + 1).filter { b -> same(a, b, classMap) && key(a.id, b.id) !in different }.map { b -> a to b } }
    }

    /**
     * [tasks] for the plan, each one kept as another's same work credited with that one's minutes
     * worked (its sessions' and its photos'), so work done under either comes off what's left. The
     * stored tasks keep their own: taking the change back takes the credit with it.
     */
    fun credited(tasks: List<TaskItem>): List<TaskItem> {
        val credit = tasks.mapNotNull { t -> t.userSameAs?.let { it to t.workedMin + t.photoMin } }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.filterValues { it > 0 }
        if (credit.isEmpty()) return tasks
        return tasks.map { t -> credit[t.id]?.let { t.copy(workedMin = t.workedMin + it) } ?: t }
    }

    private fun same(a: TaskItem, b: TaskItem, classMap: Map<String, String>): Boolean {
        if (a.source == b.source) return false
        val subject = subject(a, classMap) ?: return false
        if (subject != subject(b, classMap)) return false
        val near = when {
            a.dueAt == null && b.dueAt == null -> true
            a.dueAt != null && b.dueAt != null -> abs(a.dueAt - b.dueAt) <= DAY_MS
            else -> false
        }
        if (!near) return false
        val wa = words(a.title)
        val wb = words(b.title)
        if (wa.isEmpty() || wb.isEmpty()) return false
        return (wa intersect wb).size >= SHARE * minOf(wa.size, wb.size)
    }
}
