package com.thomaswcode.decrastination.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

/** What Felix remembers (AI-21): facts about the student, each under a topic, and standing instructions on how he behaves. */
@Serializable
data class MemoryState(
    val facts: List<Fact> = emptyList(),
    val rules: List<Rule> = emptyList(),
    /** The shape this is in ([com.thomaswcode.decrastination.data.Migration]): raised with each change of it (2: a fact's and a rule's until, a fact's task, 10a). */
    val version: Int = 2,
)

/**
 * A fact worth keeping ("football is on Tuesdays"), under a short [topic] that a newer fact replaces; held
 * till [until] where it has one ("a cold this week"), or while its task [taskId] matters ("for the trip, I'm
 * sharing a room"), then forgotten ([Memories.expired], 10a).
 */
@Serializable
data class Fact(val topic: String, val text: String, val at: Long, val until: Long? = null, val taskId: String? = null)

/** A standing instruction on how Felix behaves ("don't message before 8", "be blunter"), held till [until] where it has one ("not tonight", 10a). */
@Serializable
data class Rule(val text: String, val at: Long, val until: Long? = null)

/**
 * What an answer asks to remember (AI-21): a fact under [topic], or ([kind] "rule") a standing
 * instruction in [text]. An empty [text] forgets: the fact of that topic, or the instruction whose
 * words [topic] gives. [until] (10a) how long it holds, a date (YYYY-MM-DD, through that day) or a date
 * and time (YYYY-MM-DDTHH:MM), empty for as long as it's true; [taskId] a fact's task, empty for none.
 * Plain strings, as the schema's list items' fields are kept.
 */
@Serializable
data class Given(val topic: String = "", val text: String = "", val kind: String = FACT, val until: String = "", val taskId: String = "") {
    companion object {
        const val FACT = "fact"
        const val RULE = "rule"
    }
}

/**
 * One thing remembering did (AI-21), kept on the answer that did it so its Undo there takes it back
 * (your call, 16 Oct): a fact kept ([factAfter]) with the one of its topic it replaced
 * ([factBefore]), or one forgotten ([factBefore] alone); a standing instruction added
 * ([ruleAfter]) or dropped ([ruleBefore]). With none of them, a line only ("Too many…"), with
 * nothing to undo. [evicted]: the oldest facts the room for its fact pushed out, back with its
 * Undo. [line] says it as the answer's foot does; [undone] once Undo has taken it back.
 */
@Serializable
data class MemoryEdit(
    val line: String,
    val factBefore: Fact? = null,
    val factAfter: Fact? = null,
    val ruleBefore: Rule? = null,
    val ruleAfter: Rule? = null,
    val evicted: List<Fact> = emptyList(),
    val undone: Boolean = false,
)

/** What's remembered, kept within its bounds (AI-21). Pure. */
object Memories {
    /** The facts' room: about 1 500 tokens, the profile's share of them. */
    const val FACT_BUDGET_CHARS = 6_000

    /** The furthest an until reaches (10a): one further out is cut to it. */
    const val MAX_UNTIL_MS = 365 * 24 * 3_600_000L

    /** How long a fact with a task is kept once its task has finished: the fortnight a finished task keeps its text (Stage 7). */
    const val TASK_KEEP_MS = 14 * 24 * 3_600_000L

    /**
     * [given]'s until, at [now] in [zone] (10a): a date holds through that day, so till the next one's start; a
     * date and time, till then. Null for none, or for one that doesn't read.
     */
    fun untilOf(given: String, zone: ZoneId): Long? {
        val g = given.trim().takeIf { it.isNotEmpty() } ?: return null
        runCatching { LocalDate.parse(g) }.getOrNull()?.let { return it.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }
        return runCatching { LocalDateTime.parse(g) }.getOrNull()?.atZone(zone)?.toInstant()?.toEpochMilli()
    }

    /**
     * [until] as a line says it: the last day it holds where it ends at a day's start ("Sun 25 Oct"), else the
     * day and the time it ends ("Wed 21 Oct 07:00").
     */
    fun untilLine(until: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(until).atZone(zone)
        return if (at.toLocalTime().toSecondOfDay() == 0) at.minusDays(1).format(DAY) else at.format(DAY_TIME)
    }

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

    /**
     * How long a fact or a rule holds, in words (10a): "until Sun 25 Oct", "with “Trip form”", both, or null for as long
     * as it's true; its task by [title] where it's still listed, else as one since deleted (held while its delete can be undone).
     */
    fun held(until: Long?, taskId: String?, title: (String) -> String?, zone: ZoneId): String? =
        listOfNotNull(until?.let { "until " + untilLine(it, zone) }, taskId?.let { id -> title(id)?.let { "with “$it”" } ?: "with a task since deleted" }).joinToString(", ").takeIf { it.isNotEmpty() }

    /** What a line of [edit]'s adds of how long it holds: ", until Sun 25 Oct", ", with “Trip form”". */
    private fun shelf(until: Long?, task: String?, zone: ZoneId): String = held(until, task, { it }, zone)?.let { ", $it" } ?: ""

    /** [words] with [after] them, a full stop they end with let go of first, so none comes before its comma. */
    private fun shelved(words: String, after: String): String = if (after.isEmpty()) words else words.removeSuffix(".") + after

    /**
     * [state] without what's run out at [now] (10a): a fact or a rule past its until; a fact with a task a
     * fortnight after its task finished ([TASK_KEEP_MS]: a finished task keeps its record past it, so it's there
     * to read), or, its task gone from [tasks], once that task's delete can't be undone ([held], what the chat's
     * acts still hold, as their Undo brings the task back), and, for a source's task, once that source has been
     * read since the fact was kept ([readAt]), so one restored from a backup before a sync keeps it. Forgetting is
     * silent. [state] itself where nothing has run out, so it's written only when it changes.
     */
    fun expired(state: MemoryState, tasks: Map<String, TaskItem>, now: Long, held: Set<String> = emptySet(), readAt: (Source) -> Long? = { null }): MemoryState {
        fun lasts(f: Fact): Boolean {
            if (f.until != null && f.until <= now) return false
            val id = f.taskId ?: return true
            val task = tasks[id] ?: return id in held || !TaskItem.goneSince(id, f.at, readAt)
            // A task closed with no time it was done at (a count missed) goes by when it was last seen.
            return task.isOpen || (task.doneAt ?: task.lastSeenAt) + TASK_KEEP_MS > now
        }
        val facts = state.facts.filter(::lasts)
        val rules = state.rules.filter { it.until == null || it.until > now }
        return if (facts.size == state.facts.size && rules.size == state.rules.size) state else state.copy(facts = facts, rules = rules)
    }
    const val RULE_MAX = 20

    /** A fact's topic, and a fact's or a rule's words, at most: what [apply] keeps. */
    const val MAX_TOPIC = 60
    const val MAX_TEXT = 300

    /** What [facts] take of their room: each topic and text. */
    fun chars(facts: List<Fact>): Int = facts.sumOf { it.topic.length + it.text.length }

    /**
     * [given] laid on [state] at [now], and a line for each thing it did: "Remembered: football: on
     * Tuesdays", "Forgot: football", "Felix will: be blunter", "Felix won't any more: …". A fact
     * replaces the one of its topic (trimmed, any case); a rule is added unless it's there already (with
     * another until, its until is the new one's); facts past [FACT_BUDGET_CHARS] lose their oldest; a rule
     * past [RULE_MAX] isn't added, and says so. Each until (10a) read in [zone] ([untilOf]): one gone by is
     * refused, saying so; one more than a year ahead cut to a year; a fact's task one of [listed] (ids, their
     * titles), else it's kept with none, and said.
     */
    fun apply(state: MemoryState, given: List<Given>, now: Long, zone: ZoneId = ZoneId.of("UTC"), listed: Map<String, String> = emptyMap()): Pair<MemoryState, List<String>> =
        edit(state, given, now, zone, listed).let { (after, edits) -> after to edits.map { it.line } }

    /** [apply], with what each line did, for its Undo ([undo]). */
    fun edit(state: MemoryState, given: List<Given>, now: Long, zone: ZoneId = ZoneId.of("UTC"), listed: Map<String, String> = emptyMap()): Pair<MemoryState, List<MemoryEdit>> {
        var facts = state.facts
        var rules = state.rules
        val edits = mutableListOf<MemoryEdit>()
        for (g in given) {
            val topic = g.topic.trim().take(MAX_TOPIC)
            val text = g.text.trim().take(MAX_TEXT)
            // How long it holds (10a): gone by refused, past a year cut to one.
            val until = untilOf(g.until, zone)?.coerceAtMost(now + MAX_UNTIL_MS)
            if (until != null && until <= now && text.isNotEmpty()) {
                edits += MemoryEdit("Not remembered: " + shelved(if (g.kind == Given.RULE) text else "$topic: $text", ", as ${untilLine(until, zone)} has gone by"))
                continue
            }
            if (g.kind == Given.RULE) {
                if (text.isEmpty()) {
                    val gone = rules.filter { same(it.text, topic) }
                    if (gone.isNotEmpty()) {
                        rules = rules - gone.toSet()
                        edits += MemoryEdit("Felix won't any more: ${gone.first().text}", ruleBefore = gone.first())
                    }
                } else {
                    val had = rules.firstOrNull { same(it.text, text) }
                    when {
                        had != null && had.until == until -> Unit
                        // The same instruction, for another while: its until is the new one's.
                        had != null -> {
                            val rule = Rule(text, now, until)
                            rules = rules - had + rule
                            edits += MemoryEdit("Felix will: " + shelved(text, shelf(until, null, zone)), ruleBefore = had, ruleAfter = rule)
                        }
                        rules.size >= RULE_MAX -> edits += MemoryEdit("Too many standing instructions to add “$text”: drop one first")
                        else -> {
                            val rule = Rule(text, now, until)
                            rules = rules + rule
                            edits += MemoryEdit("Felix will: " + shelved(text, shelf(until, null, zone)), ruleAfter = rule)
                        }
                    }
                }
                continue
            }
            if (topic.isEmpty()) continue
            val had = facts.firstOrNull { same(it.topic, topic) }
            facts = facts.filterNot { same(it.topic, topic) }
            if (text.isEmpty()) {
                if (had != null) edits += MemoryEdit("Forgot: $topic", factBefore = had)
            } else {
                // Its task, where it's one of those listed; one that isn't is said, and the fact kept without it.
                val asked = g.taskId.trim().takeIf { it.isNotEmpty() }
                val task = asked?.takeIf { it in listed }
                val fact = Fact(topic, text, now, until, task)
                facts = facts + fact
                // Within their room: the oldest go first, kept with the fact that took it, for its Undo.
                val held = facts
                while (facts.size > 1 && chars(facts) > FACT_BUDGET_CHARS) facts = facts.sortedBy { it.at }.drop(1)
                val unlisted = if (asked != null && task == null) " (its task isn't listed, so it's kept without one)" else ""
                edits += MemoryEdit("Remembered: $topic: " + shelved(text, shelf(until, task?.let(listed::get), zone)) + unlisted, factBefore = had, factAfter = fact, evicted = held - facts.toSet())
            }
        }
        while (facts.size > 1 && chars(facts) > FACT_BUDGET_CHARS) facts = facts.sortedBy { it.at }.drop(1)
        return MemoryState(facts.sortedBy { it.at }, rules) to edits
    }

    /**
     * [state] with [edit] taken back (its Undo under the answer that made it): a fact kept goes, and
     * the one it replaced is back, with those its room pushed out; one forgotten is back; an
     * instruction added goes; one dropped is back. Null if it can't be: what it did has changed since
     * (the fact rewritten, kept again or forgotten, the instruction gone), or what it would bring back
     * is there already (a newer fact of its topic, the same instruction) or has no room (the facts'
     * [FACT_BUDGET_CHARS], [RULE_MAX] instructions). Only the very fact it kept is taken: the same
     * words kept again since are a newer one's to undo.
     */
    fun undo(state: MemoryState, edit: MemoryEdit): MemoryState? {
        edit.factAfter?.let { after ->
            val now = state.facts.firstOrNull { it == after } ?: return null
            val rest = state.facts - now
            var back = rest + listOfNotNull(edit.factBefore).filter { f -> rest.none { same(it.topic, f.topic) } }
            // The one it replaced, longer than the room has left since: not undone, as it can't be whole.
            if (chars(back) > FACT_BUDGET_CHARS) return null
            // Those it pushed out, the newest first, where their topics are free still and they fit.
            for (f in edit.evicted.sortedByDescending { it.at }) {
                if (back.none { same(it.topic, f.topic) } && chars(back + f) <= FACT_BUDGET_CHARS) back = back + f
            }
            return state.copy(facts = back.sortedBy { it.at })
        }
        edit.factBefore?.let { before ->
            // Not over what the room holds: newer facts have filled it since.
            if (state.facts.any { same(it.topic, before.topic) } || chars(state.facts + before) > FACT_BUDGET_CHARS) return null
            return state.copy(facts = (state.facts + before).sortedBy { it.at })
        }
        edit.ruleAfter?.let { after ->
            val now = state.rules.firstOrNull { it == after } ?: return null
            // One that took another's place (the same instruction, for another while): that one back.
            val back = (state.rules - now) + listOfNotNull(edit.ruleBefore).filter { b -> state.rules.none { it != now && same(it.text, b.text) } }
            return state.copy(rules = back.sortedBy { it.at })
        }
        edit.ruleBefore?.let { before ->
            if (state.rules.any { same(it.text, before.text) } || state.rules.size >= RULE_MAX) return null
            return state.copy(rules = (state.rules + before).sortedBy { it.at })
        }
        return null
    }

    /**
     * What [line] did, for an answer from before edits were kept (16 Oct): the fact or the standing
     * instruction it names, while it's still as the line says, so its Undo forgets it (what it
     * replaced wasn't kept). Null for any other line.
     */
    fun editOf(line: String, state: MemoryState): MemoryEdit? =
        state.facts.firstOrNull { "Remembered: ${it.topic}: ${it.text}" == line }?.let { MemoryEdit(line, factAfter = it) }
            ?: state.rules.firstOrNull { "Felix will: ${it.text}" == line }?.let { MemoryEdit(line, ruleAfter = it) }

    /** The same topic, or the same instruction: trimmed, in any case. */
    private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
}
