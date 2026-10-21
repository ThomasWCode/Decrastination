package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.AnkiRules
import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Sittings
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Where
import com.thomaswcode.decrastination.core.carryMarks
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlin.math.abs
import java.util.Locale
import java.time.ZoneId
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.Instant

/** The model's answers, read and checked: anything out of range is dropped rather than trusted. */
object ReadingAnswers {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * An answer read (AI-01): its enrichment (null if it isn't one), and the check it failed, said
     * so the model can put it right ([ReadingPrompts.repair]); null when it passed them all.
     */
    data class Parsed(val enrichment: Enrichment?, val complaint: String? = null)

    @Serializable
    private data class Triage(
        val kind: String,
        val actionableFrom: String? = null,
        val deadline: String? = null,
        /** Whether [deadline] is the email's own words (AI-12): missing, it isn't, so it's never taken as hard. */
        val deadlineQuoted: Boolean = false,
        val effortMin: Int,
        val nextStep: String,
        val question: String? = null,
        val reason: String = "",
        val blocks: List<Step> = emptyList(),
        val summary: String = "",
    )

    @Serializable
    private data class Step(val title: String, val minutes: Int, val ankiSections: List<String> = emptyList(), val from: String? = null, val due: String? = null, val where: String? = null)

    @Serializable
    private data class Split(val subSteps: List<Step>, val effortMin: Int, val ankiSections: List<String> = emptyList(), val testDate: String? = null, val question: String? = null, val reason: String = "", val summary: String = "")

    @Serializable
    private data class Estimate(val effortMin: Int, val blocks: List<Step> = emptyList(), val summary: String = "")

    internal const val MAX_EFFORT = 600

    /** The longest question put to you. */
    private const val MAX_QUESTION = 200

    private fun question(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_QUESTION)

    /** The longest phrase a reading's reason quotes (AI-09). */
    private const val MAX_REASON = 160

    private fun reason(text: String): String? = text.trim().trim('"', '“', '”').trim().takeIf { it.isNotEmpty() }?.take(MAX_REASON)

    /** The longest summary of a long text kept (D-93): its 400 and some slack, for a model that runs over. */
    private const val MAX_SUMMARY = 500

    private fun summary(text: String): String? = text.trim().takeIf { it.isNotEmpty() }?.take(MAX_SUMMARY)

    /**
     * The Email job's kinds (AI-02): Admin, as a starred email is always something to do (16 Oct);
     * an older answer's Event is read as one.
     */
    private val EMAIL_KINDS = mapOf("Admin" to Kind.Admin, "Event" to Kind.Admin)

    /**
     * The most an email or planner item in blocks is taken to be: twenty hours, as one email can
     * hold months of applications (a calendar of five months' came to 13). Past it, an estimate
     * gone wrong.
     */
    internal const val MAX_BLOCKS_EFFORT = 1_200

    /** The most steps an assignment's reading gives: past it, the rest are folded ([fold]). Its sittings may be more. */
    internal const val MAX_STEPS = 12
    private const val MAX_TITLE = 80
    private const val MAX_NEXT_STEP = 120
    private const val STEPS_SLACK_MIN = 10

    /** The most of a date that can't be read put back to the model. */
    private const val MAX_DATE_SAID = 30
    private val SECTION = Regex("""^[1-9]\.[1-9]$""")

    /**
     * The model's steps or blocks as checked: those the planner can take, or none, and then why not;
     * and the lines saying whose dates were left out to keep them ([adjusted], AI-01).
     */
    private class Checked(val steps: List<SubStep>?, val dropped: String? = null, val adjusted: List<String> = emptyList())

    /** [minutes] in hours, for a reason given: "21 hours", "20.8 hours". */
    private fun hours(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60} hours" else "%.1f hours".format(Locale.UK, minutes / 60.0)

    /**
     * The longest a step is planned in one sitting (your call, 17 Oct: an hour; three before): a
     * longer one is cut into even sittings, as the app can't know which part of the work takes how
     * long, so they're split by time, never by what's in them ([fitParts]).
     */
    const val MAX_PART_MIN = Sittings.MAX_MIN

    /**
     * [step] in even sittings no longer than [MAX_PART_MIN]: the first the step itself, then
     * "Continue: …", and the last "Continue and finish: …" ([Sittings], which the planner cuts by too).
     */
    internal fun fitParts(step: SubStep): List<SubStep> {
        val parts = Sittings.count(step.minutes)
        if (parts <= 1) return listOf(step)
        val minutes = Sittings.even(step.minutes, parts)
        return Sittings.titles(step.title, parts, finishes = true).mapIndexed { i, title -> step.copy(title = title.take(MAX_TITLE), minutes = minutes[i]) }
    }

    /**
     * [steps] in sittings ([fitParts]), each part keeping its step's dates, so ticking a piece never
     * ticks off more than it did; but not one that takes the mark of a step of [before] done
     * ([com.thomaswcode.decrastination.core.carryMarks]): done, it isn't planned, and cut, the parts
     * after its first would lose the mark (#48's review).
     */
    internal fun sittings(steps: List<SubStep>, before: List<SubStep>): List<SubStep> =
        carryMarks(steps, before).zip(steps).flatMap { (marked, step) -> if (marked.done) listOf(step) else fitParts(step) }

    /** A test's day: work must be done before school that morning. */
    private val SCHOOL_STARTS: LocalTime = LocalTime.of(8, 30)

    /** [text] as [job]'s answer, or null if it isn't one: a step's dates that can't be left out ([read] with salvage). */
    fun parse(job: Enrichments.Job, text: String, task: TaskItem, by: String, now: Long, zone: ZoneId): Enrichment? = read(job, text, task, by, now, zone, salvage = true).enrichment

    /**
     * [text] as [job]'s answer (AI-01). Not [salvage] (the first time of asking), any check failed
     * is its [Parsed.complaint], to put back to the model; with it (asked again, or not asked), a
     * step or block whose dates can't be (a start after it's due, a start or due after its task is
     * due) keeps its place and minutes with none, said in [Enrichment.adjusted], and the rest still
     * fail it.
     */
    fun read(job: Enrichments.Job, text: String, task: TaskItem, by: String, now: Long, zone: ZoneId, salvage: Boolean): Parsed {
        // What the checks couldn't take but needn't drop the answer for: a date that can't be read,
        // a kind that isn't one: said, so the answer's put back to be put right (AI-01).
        val problems = mutableListOf<String>()
        return runCatching {
            val made = reading(job, text, task, by, now, zone, salvage, problems)
            Parsed(made, (listOfNotNull(made?.dropped) + problems).firstOrNull()?.replaceFirstChar(Char::uppercase))
        }.getOrElse { Parsed(null, "The answer isn't JSON the schema takes (${it.message?.lineSequence()?.firstOrNull()?.take(120)})") }
    }

    private fun reading(job: Enrichments.Job, text: String, task: TaskItem, by: String, now: Long, zone: ZoneId, salvage: Boolean, problems: MutableList<String>): Enrichment? {
        val base = Enrichment(inputHash = Enrichments.inputHash(task), by = by, at = now, promptVersion = ReadingPrompts.VERSION)
        return when (job) {
            Enrichments.Job.Email -> json.decodeFromString(Triage.serializer(), text).let { t ->
                // Starred, an email is always something to do (AI-02, 16 Oct): Admin. Any other kind is
                // no answer at all, and the rules' reading stands.
                val kind = EMAIL_KINDS[t.kind] ?: return null.also { problems += "kind is \"${t.kind.take(20)}\": it must be Admin" }
                val total = effort(t.effortMin)
                if (total == null && t.blocks.isEmpty()) problems += "effortMin is ${t.effortMin}, not from 1 to $MAX_EFFORT minutes"
                val deadline = checkedTime("deadline", t.deadline, now, problems) { dateTime(it, zone) }
                val opens = checkedTime("actionableFrom", t.actionableFrom, now, problems) { date(it, zone, LocalTime.MIDNIGHT) }
                // Blocks of work, each perhaps with its own dates (Q12): not trusted, the triage stands
                // without them, and the task says why.
                val checked = trusted(steps(t.blocks, now, zone, vocabulary = false, maxMinutes = MAX_EFFORT, dueBy = deadline ?: sourceDue(task), opens = opens, noun = "block", salvage = salvage), t.effortMin, t.blocks.size)
                val blocks = checked.steps?.let { sittings(it, task.subSteps) }
                base.copy(
                    // Blocks of work make it something to do, whatever else it says: the ones given,
                    // trusted or not. Dropped, its whole estimate is planned instead, and its warning
                    // shows; an email hidden as an event would hide both (BUG-P2-004).
                    kind = if (t.blocks.isNotEmpty()) Kind.Admin else kind,
                    actionableFrom = opens,
                    deadline = deadline,
                    // Quoted only of a deadline kept: one dropped as out of range quotes nothing.
                    deadlineQuoted = t.deadlineQuoted && deadline != null,
                    effortMin = blocks?.sumOf { it.minutes } ?: total,
                    nextStep = t.nextStep.trim().takeIf { it.isNotEmpty() }?.take(MAX_NEXT_STEP),
                    subSteps = blocks,
                    dropped = checked.dropped,
                    adjusted = checked.adjusted,
                    question = question(t.question),
                    reason = reason(t.reason),
                    summary = summary(t.summary),
                )
            }
            Enrichments.Job.Assignment -> json.decodeFromString(Split.serializer(), text).let { s ->
                // One step it can't take (no title, minutes or a date out of range) and the split isn't
                // trusted: the task's whole estimate is planned instead, none of it lost with that step.
                val testDate = checkedTime("testDate", s.testDate, now, problems) { date(it, zone, SCHOOL_STARTS) }
                // By the test, where there's one before the source's deadline: it's the task's then.
                val read = steps(s.subSteps, now, zone, vocabulary = true, maxMinutes = MAX_EFFORT, dueBy = listOfNotNull(sourceDue(task), testDate).minOrNull(), salvage = salvage)
                val usable = read.steps != null
                val valid = read.steps.orEmpty()
                // More steps than the model may give: the rest folded, so none of the work goes (see
                // [fold]); cut into sittings only after, a fold too ([sittings]).
                val steps = if (valid.size <= MAX_STEPS) valid else valid.take(MAX_STEPS - 1) + fold(valid.drop(MAX_STEPS - 1))
                val sections = sections(s.ankiSections + steps.flatMap { it.ankiSections })
                // All vocabulary (no steps, its sections named): a step for it all the same, at the
                // task's own estimate. Where decks hold those sections the planner leaves it out, and
                // handing in is what's left; where none does (no such deck yet), it's planned.
                val allVocabulary = usable && steps.isEmpty() && sections.isNotEmpty()
                val planned = if (allVocabulary) {
                    listOf(SubStep(("Learn vocabulary " + sections.joinToString("/")).take(MAX_TITLE), task.copy(aiEffortMin = null).effortMin, ankiSections = sections))
                } else {
                    steps
                }
                // Steps that don't add up to the total (within a tenth, or ten minutes): one of the two
                // is wrong, and planning the steps could lose work, so the total is planned instead.
                // All vocabulary, the model's total is the hand-in alone: the step's estimate stands.
                val total = effort(s.effortMin).takeUnless { allVocabulary }
                val sum = planned.sumOf { it.minutes }
                // A total past the most one task is taken to be isn't one to plan by, nor are steps
                // adding up past it: the task's own estimate is planned instead.
                val inRange = sum <= MAX_EFFORT && (allVocabulary || s.effortMin <= MAX_EFFORT)
                val agree = inRange && (total == null || abs(sum - total) <= maxOf(STEPS_SLACK_MIN, total / 10))
                // Steps given but not planned, and why: said on the task, so it can be checked.
                val dropped = when {
                    !usable -> read.dropped
                    agree || planned.isEmpty() -> null
                    sum > MAX_EFFORT -> "the steps come to ${hours(sum)}, over the ${MAX_EFFORT / 60} hours one task can take"
                    !allVocabulary && s.effortMin > MAX_EFFORT -> "effortMin is ${hours(s.effortMin)}, over the ${MAX_EFFORT / 60} hours one task can take"
                    else -> "the steps add up to $sum minutes, not the ${s.effortMin} effortMin gives"
                }
                base.copy(
                    effortMin = total ?: sum.takeIf { agree && it > 0 },
                    subSteps = planned.takeIf { agree }?.let { sittings(it, task.subSteps) }.orEmpty().ifEmpty { null },
                    dropped = dropped,
                    adjusted = read.adjusted,
                    // A split not trusted hands no vocabulary to a deck either: its total, planned
                    // whole, already holds it.
                    ankiSections = if (usable && agree) sections else emptyList(),
                    testDate = testDate,
                    question = question(s.question),
                    reason = reason(s.reason),
                    summary = summary(s.summary),
                )
            }
            Enrichments.Job.Effort -> json.decodeFromString(Estimate.serializer(), text).let { e ->
                val total = effort(e.effortMin)
                // Big enough to do in parts: its blocks, each perhaps with its own dates (Q12).
                val checked = trusted(steps(e.blocks, now, zone, vocabulary = false, maxMinutes = MAX_EFFORT, dueBy = sourceDue(task), noun = "block", salvage = salvage), e.effortMin, e.blocks.size)
                val blocks = checked.steps?.let { sittings(it, task.subSteps) }
                base.copy(effortMin = blocks?.sumOf { it.minutes } ?: total, subSteps = blocks, dropped = checked.dropped, adjusted = checked.adjusted, summary = summary(e.summary))
            }
        }
    }

    /**
     * [given] as steps the planner can take, or none if any can't be, and why, naming the step and
     * what's wrong with it ([noun] names them; AI-01): no title, minutes out of range (up to
     * [maxMinutes]), a date that can't be read or is out of range, a deadline before its task can
     * be started ([opens]), or an order its dates forbid (a step that can't start till after one
     * listed later is due). Dates that can't be (a start after its own deadline, a start or a
     * deadline after its task's, [dueBy]) fail it too, unless it's to [salvage]: then the step keeps
     * its place and minutes with no dates, planned by its task's, and a line in [Checked.adjusted]
     * says so. Each keeps its own dates; one over [MAX_PART_MIN] is cut into sittings after, once
     * folded or trusted ([sittings]). A step tagged as vocabulary
     * ([vocabulary] jobs only) that asks for anything else too stays planned whole.
     */
    private fun steps(given: List<Step>, now: Long, zone: ZoneId, vocabulary: Boolean, maxMinutes: Int, dueBy: Long?, opens: Long? = null, noun: String = "step", salvage: Boolean = true): Checked {
        fun no(why: String) = Checked(null, why)
        // A time as the model wrote it: a date at midnight alone, as "from" is.
        fun at(time: Long): String = Instant.ofEpochMilli(time).atZone(zone).toLocalDateTime().let { if (it.toLocalTime() == LocalTime.MIDNIGHT) it.toLocalDate().toString() else it.toString().take(16) }
        fun which(i: Int, title: String) = "$noun ${i + 1}" + title.trim().takeIf { it.isNotEmpty() }?.let { " ('${it.take(MAX_TITLE)}')" }.orEmpty()
        val adjusted = mutableListOf<String>()
        val read = given.mapIndexed { i, step ->
            val name = which(i, step.title)
            if (step.title.isBlank()) return no("$name has no title")
            if (step.minutes < 1) return no("$name has no minutes")
            if (step.minutes > maxMinutes) return no("$name is ${step.minutes} minutes, over the $maxMinutes allowed")
            var from = step.from?.let { date(it, zone, LocalTime.MIDNIGHT) ?: return no("$name has a date that can't be read: '${it.take(MAX_DATE_SAID)}'") }
            var due = step.due?.let { dateTime(it, zone) ?: return no("$name has a date that can't be read: '${it.take(MAX_DATE_SAID)}'") }
            listOfNotNull(from, due).firstOrNull { !plausible(it, now) }?.let { return no("$name has a date out of range: ${at(it)}") }
            // Dates that can't be: put right by the model, or, asked again, left out (AI-01).
            val wrong = when {
                from != null && due != null && from > due -> "starts on ${at(from)}, after its own due time ${at(due)}" to "its start was after its own due time"
                from != null && dueBy != null && from > dueBy -> "starts on ${at(from)}, after the due time ${at(dueBy)}" to "its start was after the hand-in"
                due != null && dueBy != null && due > dueBy -> "is due ${at(due)}, after the due time ${at(dueBy)}" to "it was due after the hand-in"
                else -> null
            }
            if (wrong != null) {
                if (!salvage) return no("$name ${wrong.first}")
                adjusted += "'${step.title.trim().take(MAX_TITLE)}': ${wrong.second}, so it's planned by the task's date"
                from = null
                due = null
            }
            if (due != null && opens != null && due < opens) return no("$name is due ${at(due)}, before the email can be started on ${at(opens)}")
            val sections = if (vocabulary && AnkiRules.vocabularyOnly(step.title)) sections(step.ankiSections) else emptyList()
            SubStep(step.title.trim().take(MAX_TITLE), step.minutes, ankiSections = sections, from = from, dueAt = due, where = Where.of(step.where)?.name?.lowercase())
        }
        for (i in read.indices) {
            val from = read[i].from ?: continue
            val j = read.indices.drop(i + 1).firstOrNull { later -> read[later].dueAt?.let { it < from } == true } ?: continue
            return no("${which(i, read[i].title)} can't start till ${at(from)}, after ${which(j, read[j].title)} is due ${at(read[j].dueAt!!)}")
        }
        return Checked(read, adjusted = adjusted)
    }

    /**
     * An email's or planner item's blocks ([checked]), if they can be planned: some, no more than
     * [ReadingPrompts.MAX_BLOCKS] as [given] (before a long one was cut into parts that fit a session),
     * within the most a task in blocks is taken to be ([MAX_BLOCKS_EFFORT]), and adding up to its
     * [total] (within a tenth, or ten minutes), which has to be within it too. Otherwise none, and
     * why: its estimate is planned whole. None given, there's nothing to drop.
     */
    private fun trusted(checked: Checked, total: Int, given: Int): Checked {
        val blocks = checked.steps ?: return checked
        if (blocks.isEmpty()) return Checked(null)
        fun no(why: String) = Checked(null, why)
        if (given > ReadingPrompts.MAX_BLOCKS) return no("there are $given blocks, over the ${ReadingPrompts.MAX_BLOCKS} allowed")
        val sum = blocks.sumOf { it.minutes }
        val most = maxOf(sum, total)
        if (most > MAX_BLOCKS_EFFORT) return no("the blocks come to ${hours(most)}, over the ${MAX_BLOCKS_EFFORT / 60} hours one task can take")
        if (total < 1) return no("the blocks come with no effortMin")
        if (abs(sum - total) > maxOf(STEPS_SLACK_MIN, total / 10)) return no("the blocks add up to $sum minutes, not the $total effortMin gives")
        return checked
    }

    /**
     * [tail], the steps past what the planner takes, with each run of plain steps made one ("The
     * rest: …") in its place. Vocabulary (its decks may hold it) and a step with dates of its own
     * keep a step of their own, between the runs, so no step moves ahead of one before it.
     */
    private fun fold(tail: List<SubStep>): List<SubStep> {
        val out = mutableListOf<SubStep>()
        val run = mutableListOf<SubStep>()
        fun flush() {
            when (run.size) {
                0 -> Unit
                1 -> out += run.single()
                // At home if any of it must be: the rest is done where its hardest part can be (PL-15).
                else -> out += SubStep(
                    ("The rest: " + run.joinToString("; ") { it.title }).take(MAX_TITLE),
                    run.sumOf { it.minutes },
                    where = if (run.any { Where.of(it.where) == Where.Home }) "home" else run.map { it.where }.distinct().singleOrNull(),
                )
            }
            run.clear()
        }
        for (step in tail) {
            if (step.ankiSections.isNotEmpty() || step.from != null || step.dueAt != null) {
                flush()
                out += step
            } else {
                run += step
            }
        }
        flush()
        return out
    }

    /** What [task]'s source says it's due by, not what an older enrichment laid over it. */
    private fun sourceDue(task: TaskItem): Long? = task.sourceValues.let { if (it != null) it.dueAt else task.dueAt }

    private fun effort(minutes: Int): Int? = minutes.takeIf { it in 1..MAX_EFFORT }

    private fun sections(given: List<String>): List<String> = given.map { it.trim() }.filter { SECTION.matches(it) }.distinct()

    private fun date(text: String, zone: ZoneId, time: LocalTime): Long? = runCatching {
        LocalDate.parse(text.trim().take(10)).atTime(time).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    /** "YYYY-MM-DDTHH:MM", or a date alone, meaning the end of that day. */
    private fun dateTime(text: String, zone: ZoneId): Long? = runCatching {
        val trimmed = text.trim()
        if (trimmed.length <= 10) date(trimmed, zone, LocalTime.of(23, 59))
        else LocalDateTime.parse(trimmed.take(16)).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    /** Within a month before now and a year and a bit after: anything else is a misreading. */
    private fun plausible(time: Long, now: Long): Boolean = time in (now - 31 * DAY_MS)..(now + 400 * DAY_MS)

    /**
     * [field]'s [given] text as a time by [read], if it's plausible ([plausible]): one that can't be
     * read, or is further ahead than the app plans, is said in [problems] to be put right. One long
     * gone (an old email's date) is no mistake: it's left out without a word.
     */
    private fun checkedTime(field: String, given: String?, now: Long, problems: MutableList<String>, read: (String) -> Long?): Long? {
        val text = given?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val time = read(text) ?: return null.also { problems += "$field \"${text.take(40)}\" isn't a date (YYYY-MM-DD) or a local date and time (YYYY-MM-DDTHH:MM)" }
        if (time > now + 400 * DAY_MS) return null.also { problems += "$field $text is more than 400 days ahead, further than the app plans: give it only if the text says so" }
        return time.takeIf { plausible(it, now) }
    }

    private const val DAY_MS = 24 * 3_600_000L
}
