package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnTasks
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.enriched
import com.thomaswcode.decrastination.learn.Calibrator
import com.thomaswcode.decrastination.enrich.AiUsage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a backup keeps (PLAN.md Phase 6): the settings, the activity log, what the app has learned,
 * your answers about calendar events and, since 1.7.0, your instructions in use: what the sources
 * can't give back. Never the passwords or keys, which stay in this phone's encrypted store, nor the
 * tasks, which the next sync reads again.
 */
@Serializable
data class Backup(
    val app: String = APP,
    val format: Int = FORMAT,
    val exportedAt: Long,
    /** The app's version that wrote it, for reading it later. */
    val versionName: String = "",
    val settings: Settings,
    val log: ActivityLog,
    val calibration: Calibration = Calibration(),
    val eventAnswers: Map<String, String> = emptyMap(),
    /** What Claude has cost this month, so a restore onto a fresh install doesn't give the month's cap again. */
    val aiUsage: AiUsage = AiUsage(),
    /** Your instructions in use (applied), in the order they were: none in a backup from before 1.7.0. */
    val instructions: List<Instruction> = emptyList(),
    /** Your own tasks (2.0.0), open or lately done: no source can give them back. */
    val ownTasks: List<TaskItem> = emptyList(),
    /** The school calendar (PL-16): days off and half-days, none in a backup from before. */
    val school: SchoolCalendar = SchoolCalendar(),
    /** What Felix remembers (AI-21): none in a backup from before. */
    val memory: com.thomaswcode.decrastination.core.MemoryState = com.thomaswcode.decrastination.core.MemoryState(),
    /** Felix's own state (AR-07): when he looks next, what he's waiting to say. None in a backup from before. */
    val felix: FelixState = FelixState(),
    /** Today's slots' starts and misses (AR-07), for a restore the same day. */
    val slots: com.thomaswcode.decrastination.core.SlotState = com.thomaswcode.decrastination.core.SlotState(),
) {
    companion object {
        const val APP = "Decrastination"
        /**
         * 2 since 2.0.0, which keeps your own tasks; 3 since review v2's school calendar (PL-16). An
         * older version, which would restore the rest and quietly lose them, refuses it whole as from
         * a newer one; an older backup is read here with none.
         */
        /**
         * 4 since slots replaced sessions in the log (LR-09, AR-09), 5 with what's remembered
         * (AI-21), 6 with Felix's state and today's slots (AR-07): an older app refuses it rather
         * than losing them.
         */
        const val FORMAT = 6
    }
}

object Backups {
    /** Far more than a year's log: a bigger file isn't one of these. */
    const val MAX_BYTES = 8 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        prettyPrint = true
    }

    fun encode(backup: Backup): String = json.encodeToString(Backup.serializer(), backup)

    /** [text] as one of this app's backups, or null: not JSON, not this app's, or a newer format than this version reads. */
    fun decode(text: String): Backup? = runCatching { json.decodeFromString(Backup.serializer(), text) }.getOrNull()
        ?.takeIf { it.app == Backup.APP && it.format <= Backup.FORMAT }

    /**
     * What's wrong with [backup] as this app's state, in words, or null if nothing is: JSON of the
     * right shape can still hold what the app never writes (negative minutes, a multiplier past its
     * bounds, a setting the screen wouldn't take), and restoring that could break planning or the
     * stats (BUG-P2-002). Checked whole before anything is restored, so nothing is half done.
     */
    fun problem(backup: Backup): String? {
        val bad = buildList {
            addAll(SettingsLimits.problems(backup.settings))
            val log = backup.log
            if (log.slots.any { it.workedMin !in 0..DAY_MIN || it.plannedMin !in 0..DAY_MIN || it.start < 0 || it.end < it.start }) add("a slot's minutes")
            if (log.completions.any { it.estimateMin < 0 || it.workedMin < 0 }) add("a finished task's minutes")
            if (log.days.any { it.plannedMin < 0 || (it.doneMin ?: 0) < 0 || runCatching { java.time.LocalDate.parse(it.date) }.isFailure }) add("a day's plan")
            if (log.checkIns.any { it.feel !in 1..5 }) add("a check-in's answer")
            val calibration = backup.calibration
            if (calibration.multipliers.values.any { !it.isFinite() || it !in Calibrator.MIN_MULTIPLIER..Calibrator.MAX_MULTIPLIER }) add("what the app learned about estimates")
            if (calibration.marginDays.values.any { it !in SettingsLimits.MARGIN_DAYS }) add("what the app learned about margins")
            // The days' yields (PL-06), as the review learns them: a half to all of a day, from a week or two of days.
            if (calibration.yields.values.any { !it.isFinite() || it !in Calibrator.MIN_YIELD..Calibrator.MAX_YIELD }) add("what the app learned about the days' time")
            if (calibration.yieldDays.values.any { it !in Calibrator.YIELD_DAYS..Calibrator.YIELD_LOOK_BACK } || calibration.yields.keys != calibration.yieldDays.keys) add("what the app learned about the days' time's evidence")
            if (!evidence(calibration)) add("what the app learned's evidence")
            // What's remembered as the app keeps it (AI-21): in its room, every fact with a topic, and
            // each within the bounds it's kept to, as all of it goes with every call.
            val memory = backup.memory
            val kept = com.thomaswcode.decrastination.core.Memories
            if (kept.chars(memory.facts) > kept.FACT_BUDGET_CHARS || memory.rules.size > kept.RULE_MAX ||
                memory.facts.any { it.topic.isBlank() || it.text.isBlank() || it.topic.length > kept.MAX_TOPIC || it.text.length > kept.MAX_TEXT } ||
                memory.rules.any { it.text.isBlank() || it.text.length > kept.MAX_TEXT }
            ) add("what's remembered")
            // What a card takes in AnkiDroid (DX-11): within the bounds it's learned in, each from its stretches.
            if (calibration.ankiSecondsPerCard.values.any { !it.isFinite() || it !in com.thomaswcode.decrastination.learn.AnkiProgress.MIN_SECONDS..com.thomaswcode.decrastination.learn.AnkiProgress.MAX_SECONDS } ||
                calibration.ankiSecondsPerCard.keys != calibration.ankiStretches.keys || calibration.ankiStretches.values.any { it !in 1..com.thomaswcode.decrastination.learn.AnkiProgress.STRETCHES }
            ) add("what the app learned about Anki")
            if (backup.eventAnswers.values.any { !answer(it) }) add("a calendar answer")
            if (!backup.aiUsage.spentUsd.isFinite() || backup.aiUsage.spentUsd < 0 || backup.aiUsage.calls < 0) add("Claude's spending")
            if (backup.instructions.any { !wellFormed(it, backup.exportedAt) }) add("an instruction")
            if (backup.ownTasks.any { !wellFormed(it) } || backup.ownTasks.map { it.id }.toSet().size != backup.ownTasks.size) add("a task of your own")
            if (!wellFormed(backup.school)) add("the school calendar")
        }
        return bad.takeIf { it.isNotEmpty() }?.let { "it holds what this app never writes (${it.joinToString(", ")})" }
    }

    /**
     * Whether [calibration]'s timed counts are ones the app could have kept: each for a kind it knows,
     * at least one; and, kept since 1.8.0, a class's own multiplier only on its [Calibrator.MIN_CLASS_TIMED].
     */
    private fun evidence(calibration: Calibration): Boolean {
        fun known(key: String) = '|' in key && Kind.entries.any { it.name == key.substringBefore('|') }
        if (calibration.timed.any { (key, n) -> !known(key) || n < 1 }) return false
        if (!calibration.counted) return true
        return calibration.multipliers.keys.all { key -> key.substringAfter('|').isEmpty() || (calibration.timed[key] ?: 0) >= Calibrator.MIN_CLASS_TIMED }
    }

    private const val DAY_MIN = 24 * 60
    private const val DAY_MS = 24 * 3_600_000L

    /**
     * Whether [task] is one of your own as the app makes them: under its id, as the form would take
     * it, and its own bookkeeping such as the app keeps (minutes worked and ticked, never below
     * nothing nor past a day's; an estimate of yours in range). What instructions laid over it isn't
     * restored, but laid again from those here ([restoredOwnTasks]).
     */
    private fun wellFormed(task: TaskItem): Boolean {
        val minutes = 0..OwnTasks.MINUTES.last
        return task.source == Source.Own && task.id == TaskItem.id(Source.Own, task.sourceId) &&
            task.workedMin in minutes && task.photoMin in minutes && task.stepsWorkedMin in minutes &&
            (task.userEffortMin == null || task.userEffortMin in OwnTasks.MINUTES) &&
            (task.peakEffortMin == null || task.peakEffortMin in minutes) &&
            // No source to say how far it is: yours are done or not, never part way. Nor to hide it
            // till a day: a start of yours is when its work is planned from (notBefore).
            task.sourceProgress == 0.0 && (task.firstProgress ?: 0.0) == 0.0 &&
            task.availableFrom == null && task.sourceValues?.availableFrom == null &&
            // Nor anything else only a source gives (an Anki deck's day's cards, the enrichment's
            // estimate, a source's extras), nor a miss, which only a source's counts can make.
            task.stepsPerDay == null && !task.derived && task.aiEffortMin == null &&
            // Its one extra of yours: where it's done, when that's at home (PL-15).
            task.extra.keys.all { it == OwnTasks.EXTRA_WHERE } && task.extra[OwnTasks.EXTRA_WHERE].let { it == null || it == "home" } &&
            task.status != com.thomaswcode.decrastination.core.Status.Missed && task.photosCounted.values.all { it in minutes } &&
            task.subSteps.all { it.timedMin in minutes } &&
            task.enrichment == null &&
            OwnTasks.problem(OwnTasks.draftOf(task)) == null
    }

    /**
     * The tasks of your own to add from a backup's ([backed]), beside those [here]: those not here
     * already, as they were, but for what instructions laid over them, which is laid again from the
     * instructions here. One here wins: it's the newer.
     */
    fun restoredOwnTasks(backed: List<TaskItem>, here: List<TaskItem>): List<TaskItem> = backed.filter { b -> here.none { it.id == b.id } }
        .map { it.copy(userNotATask = false, userFrom = null, userAfter = null, userDueAt = null, userEffortMin = null, userOnDay = null, userAtTime = null).enriched() }

    /** The most changes one instruction holds, as Claude's reading of one is held to. */
    private const val MAX_CHANGES = 40

    /**
     * Whether [instruction] is one this app could have made: an id, and each change of its kind with
     * what it needs, in range (times within a day, a weekday 1 to 7, a date that reads, a date and
     * time within a couple of years of [exportedAt]). The tasks and events it names aren't checked:
     * on a phone just set up, their sources haven't been read yet.
     */
    private fun wellFormed(instruction: Instruction, exportedAt: Long): Boolean {
        if (instruction.id.isBlank() || instruction.changes.size > MAX_CHANGES) return false
        fun named(id: String?) = !id.isNullOrBlank()
        fun day(change: Change) = when {
            change.date != null -> change.weekday == null && runCatching { java.time.LocalDate.parse(change.date) }.isSuccess
            else -> change.weekday in 1..7
        }
        fun time(at: Long?) = at != null && at in (exportedAt - 730 * DAY_MS)..(exportedAt + 400 * DAY_MS)
        return instruction.changes.all { change ->
            when (change.type) {
                ChangeType.NotATask -> named(change.taskId)
                ChangeType.StartFrom, ChangeType.DueBy -> named(change.taskId) && time(change.time)
                ChangeType.Minutes -> named(change.taskId) && change.minutes in OwnTasks.MINUTES
                ChangeType.After -> named(change.taskId) && named(change.afterTaskId) && change.afterTaskId != change.taskId
                ChangeType.EventTime -> named(change.eventKey) && change.eventAnswer?.let(::answer) == true
                ChangeType.DayLimit -> change.freeMin in 0..DAY_MIN && day(change)
                ChangeType.BusyTime -> change.startMin in 0 until DAY_MIN && (change.endMin ?: -1) in ((change.startMin ?: DAY_MIN) + 1)..DAY_MIN && day(change)
                // Kept in the school calendar once applied, never in an instruction in use; well formed all the same.
                ChangeType.NoSchool -> isDate(change.date) && (change.endDate == null || isDate(change.endDate) && change.endDate >= change.date!!)
                ChangeType.HalfDay -> isDate(change.date) && change.endMin in 0..DAY_MIN
                // A day or a time for a task (PL-04).
                ChangeType.OnDay -> named(change.taskId) && isDate(change.date)
                ChangeType.AtTime -> named(change.taskId) && isDate(change.date) && change.startMin in 0 until DAY_MIN && time(change.time)
                ChangeType.SameAs -> named(change.taskId) && named(change.afterTaskId) && change.afterTaskId != change.taskId
                // A task of your own's steps (AI-19): some, each named, with its minutes.
                ChangeType.Steps -> named(change.taskId) && change.steps.orEmpty().let { steps -> steps.size in 1..OwnTasks.MAX_STEPS && steps.all { it.title.isNotBlank() && it.minutes in OwnTasks.MINUTES } }
            }
        }
    }

    private fun isDate(text: String?): Boolean = text != null && runCatching { java.time.LocalDate.parse(text) }.isSuccess

    /** Whether [school] is one this app could have kept: dates that read, stretches in order, an end within the day. */
    fun wellFormed(school: SchoolCalendar): Boolean =
        school.noSchool.all { isDate(it.from) && isDate(it.to) && it.from <= it.to } && school.halfDays.all { isDate(it.date) && it.endsAtMin in 0..DAY_MIN }

    /** A calendar answer as they're kept: "free", "busy", or "load:<minutes>" within a day. */
    private fun answer(value: String): Boolean =
        value == "free" || value == "busy" || value.removePrefix("load:").takeIf { it != value }?.toIntOrNull()?.let { it in 0..DAY_MIN } == true

    /**
     * The instructions to add from a backup's ([backed], those in use), beside those [here], each as
     * applying it here would go, in the order they were applied: one already here isn't added; one
     * that would have tasks wait for each other in a circle comes back unclear, saying so. The rest,
     * due dates as well, armed or not (12 Oct), are in use at once.
     */
    fun restoredInstructions(backed: List<Instruction>, here: List<Instruction>): List<Instruction> {
        val added = mutableListOf<Instruction>()
        for (instruction in backed.filter { it.state == InstructionStatus.Applied }.sortedBy { it.appliedAt ?: it.at }) {
            if ((here + added).any { it.id == instruction.id }) continue
            val inUse = InstructionState(here + added).applied
            added += if (Instructions.makesCircle(instruction.changes, inUse)) {
                instruction.copy(state = InstructionStatus.Unclear, appliedAt = null, note = "It would have tasks wait for each other in a circle with those here, so it wasn't applied")
            } else {
                instruction
            }
        }
        return added
    }

    /** [input] whole, or null if it's over [cap] bytes. */
    fun read(input: InputStream, cap: Int = MAX_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            if (out.size() > cap) return null
        }
    }

    /**
     * The settings an import proposes: the backup's, but this phone's own protection and Claude's
     * key stay as they are. Arming needs the device admin and the parent code set up here, and a
     * key is this phone's. They go through [com.thomaswcode.decrastination.AppGraph.changeSettings],
     * so once armed a loosening one waits like any other.
     */
    fun importedSettings(asked: Settings, backup: Backup): Settings =
        // The calendars are this phone's, by ids another phone's mean nothing to (NF-07, DX-13).
        backup.settings.copy(armed = asked.armed, aiKeyActive = asked.aiKeyActive, calendarWriteId = asked.calendarWriteId, calendarsExcluded = asked.calendarsExcluded)

    /**
     * [current]'s usage with [imported]'s for the same month taken in: the higher of each count, so
     * a restore never lowers what's been spent (nor raises it past what either phone saw). Another
     * month's is nothing to this one.
     */
    fun mergeUsage(current: AiUsage, imported: AiUsage, month: String): AiUsage {
        val here = current.forMonth(month)
        if (imported.month != month) return here
        return here.copy(
            spentUsd = maxOf(here.spentUsd, imported.spentUsd),
            calls = maxOf(here.calls, imported.calls),
            refused = maxOf(here.refused, imported.refused),
            failed = maxOf(here.failed, imported.failed),
        )
    }

    /**
     * What's remembered here, with the backup's added (AI-21): a fact of a topic not here, a rule not
     * here; this phone's own wins, and what's past the room goes, oldest first.
     */
    fun mergedMemory(here: com.thomaswcode.decrastination.core.MemoryState, backup: com.thomaswcode.decrastination.core.MemoryState): com.thomaswcode.decrastination.core.MemoryState {
        fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
        // Each with its own time, so the room is kept oldest first, wherever each came from.
        val facts = (here.facts + backup.facts.filter { f -> here.facts.none { same(it.topic, f.topic) } }.distinctBy { it.topic.trim().lowercase() }).sortedBy { it.at }.toMutableList()
        while (facts.size > 1 && com.thomaswcode.decrastination.core.Memories.chars(facts) > com.thomaswcode.decrastination.core.Memories.FACT_BUDGET_CHARS) facts.removeAt(0)
        val rules = (here.rules + backup.rules.filter { r -> here.rules.none { same(it.text, r.text) } }.distinctBy { it.text.trim().lowercase() }).take(com.thomaswcode.decrastination.core.Memories.RULE_MAX)
        return com.thomaswcode.decrastination.core.MemoryState(facts, rules)
    }

    /**
     * [current] with [imported]'s records added. One already here (the same moment and task, the
     * same day or week) isn't doubled, and this phone's own version of it wins.
     */
    fun mergeLog(current: ActivityLog, imported: ActivityLog, now: Long): ActivityLog = ActivityLog(
        // A task in boxes keeps one key for its next box: two kept the same day are told apart by when.
        slots = (current.slots + imported.slots).distinctBy { listOf(it.key, it.start, it.outcome, it.at) }.sortedBy { it.at },
        completions = (current.completions + imported.completions).distinctBy { it.taskId to it.doneAt }.sortedBy { it.doneAt },
        blocks = (current.blocks + imported.blocks).distinctBy { Triple(it.at, it.target, it.reason) }.sortedBy { it.at },
        protection = (current.protection + imported.protection).distinctBy { it.at }.sortedBy { it.at },
        days = (current.days + imported.days).distinctBy { it.date }.sortedBy { it.date },
        checkIns = (current.checkIns + imported.checkIns).distinctBy { it.weekOf }.sortedBy { it.at },
        reviews = (current.reviews + imported.reviews).distinctBy { it.at }.sortedBy { it.at },
        felix = (current.felix + imported.felix).distinctBy { it.id }.sortedBy { it.at },
    ).trimmed(now)
}
