package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.GmailWrite
import com.thomaswcode.decrastination.core.GmailWrites
import com.thomaswcode.decrastination.core.DueKind
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Memories
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.Mine
import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Reminders
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.SettingsLimits
import com.thomaswcode.decrastination.core.SlotState
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WeeklyRule
import com.thomaswcode.decrastination.core.WeeklyRules
import com.thomaswcode.decrastination.core.enriched
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.learn.AnkiProgress
import com.thomaswcode.decrastination.learn.Calibrator
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What a backup keeps (docs/spec/storage-and-backups.md): what the sources can't give back. The settings,
 * the activity log, what the app has learned, your answers about calendar events, your instructions in
 * use and your own tasks, the school calendar, what Felix remembers, today's slots, the chat and its
 * archive, his notes and reminders, the month's Claude cost and Gmail's receipts. Never the passwords
 * or keys, which stay in this phone's encrypted store, nor a source's tasks, which the next sync reads again.
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
    /** Your own tasks, open or lately done: no source can give them back; none in a backup from before them. */
    val ownTasks: List<TaskItem> = emptyList(),
    /** The school calendar (PL-16): days off and half-days, none in a backup from before. */
    val school: SchoolCalendar = SchoolCalendar(),
    /** What Felix remembers (AI-21): none in a backup from before. */
    val memory: MemoryState = MemoryState(),
    /** Today's slots' starts and misses (AR-07), for a restore the same day. */
    val slots: SlotState = SlotState(),
    /**
     * The chat (AR-07, LR-08), so a reset's copy holds what it clears: restored where this phone's is
     * empty, Felix's state with it ([com.thomaswcode.decrastination.core.ChatState.felix]). None in a backup from before.
     */
    val chat: ChatState = ChatState(),
    /** The chat's earlier messages, while the backup has room for them, the oldest left out first ([Backups.fitted]). */
    val archive: ChatArchive = ChatArchive(),
    /** Felix's notes and reminders (4.1.0): none in a backup from before. */
    val felixStore: FelixStore = FelixStore(),
    /** The receipts of writes made to Gmail (4.3.0), while their Undo can stand, as the chat restored may undo them: none in a backup from before. */
    val gmailWrites: List<GmailWrite> = emptyList(),
    /**
     * The version of each store its parts come from, by file (docs/plan_v4/data-and-migrations.md §Versions and migrations): an
     * older one's parts are brought to this build's shapes as it's read ([Backups.decode]). None in a
     * backup from before: version 1.
     */
    val versions: Map<String, Int> = emptyMap(),
) {
    companion object {
        const val APP = "Decrastination"
        /**
         * 2 with your own tasks, 3 with the school calendar (PL-16). An
         * older version, which would restore the rest and quietly lose them, refuses it whole as from
         * a newer one; an older backup is read here with none.
         *
         * 4 since slots replaced sessions in the log (LR-09, AR-09), 5 with what's remembered
         * (AI-21), 6 with Felix's state, today's slots and the chat (AR-07), 7 with the chat's earlier
         * messages (4.0.0), 8 with Felix's notes and reminders (4.1.0), 9 with the receipts of writes to
         * Gmail (4.3.0): an older app refuses it rather than losing them.
         */
        const val FORMAT = 9
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

    /**
     * [backup] as text within [cap] bytes, as Import reads it ([read]): the chat's earlier messages
     * ([Backup.archive]) left out, the oldest first, as far as it takes; and how many were.
     */
    fun fitted(backup: Backup, cap: Int = MAX_BYTES): Pair<String, Int> {
        fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size
        val whole = encode(backup)
        val all = backup.archive.messages
        if (bytes(whole) <= cap || all.isEmpty()) return whole to 0
        // The fewest left out that fit: more left out never makes it longer.
        var fits = all.size
        var over = 0
        while (fits - over > 1) {
            val mid = (over + fits) / 2
            if (bytes(encode(backup.copy(archive = backup.archive.copy(messages = all.drop(mid))))) <= cap) fits = mid else over = mid
        }
        return encode(backup.copy(archive = backup.archive.copy(messages = all.drop(fits)))) to fits
    }

    /**
     * [text] as one of this app's backups, or null: not JSON, not this app's, a newer format than this
     * version reads, from a store it carries that's newer than this build's (wherever its parts are), or
     * with a part a migration fails on. Parts from a store older than [shapes] says (each JSON store's
     * shape by file, [Stores.shapes]) are brought to it first.
     */
    fun decode(text: String, shapes: Map<String, Shape>): Backup? {
        val tree = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        // A migration that fails on a part makes a backup this build can't read, as one that doesn't parse is.
        val upgraded = runCatching { upgraded(tree, shapes) }.getOrNull() ?: return null
        return runCatching { json.decodeFromJsonElement(Backup.serializer(), upgraded) }.getOrNull()?.takeIf { it.app == Backup.APP && it.format <= Backup.FORMAT }
    }

    /**
     * This phone's writes to Gmail with [backup]'s receipts it hasn't, by id, in the order they were made, so a
     * backup's older one never comes after this phone's newer for its task: a pending one of the backup's isn't there
     * to bring (a backup keeps none), and this phone's own stand as they are.
     */
    fun mergedWrites(writes: List<GmailWrite>, backup: List<GmailWrite>): List<GmailWrite> {
        val here = writes.mapTo(HashSet()) { it.id }
        return (writes + GmailWrites.receipts(backup).filter { it.id !in here }).sortedBy { it.at }
    }

    /**
     * Where each of a backup's parts comes from: its store's file, and its field there (null: the part is
     * the store whole). A store kept in part (tasks.json's own tasks, instructions.json's, and one field
     * each of learning.json, calendar.json, model.json, session.json and gmail-writes.json) is migrated as an object holding only those fields, so its migrations must
     * leave alone what isn't there. `instructions` is the state's applied ones under its `instructions`
     * field: the same type, so a migration of that field is one of these too.
     */
    private val PARTS: Map<String, Pair<String, String?>> = mapOf(
        "settings" to ("settings.json" to null), "log" to ("log.json" to null), "school" to ("school.json" to null),
        "memory" to ("memory.json" to null), "chat" to ("chat.json" to null), "archive" to ("chat-archive.json" to null), "felixStore" to ("felix.json" to null),
        "instructions" to ("instructions.json" to "instructions"), "ownTasks" to ("tasks.json" to "tasks"),
        "calibration" to ("learning.json" to "calibration"), "eventAnswers" to ("calendar.json" to "eventAnswers"),
        "aiUsage" to ("model.json" to "aiUsage"), "slots" to ("session.json" to "slots"), "gmailWrites" to ("gmail-writes.json" to "writes"),
    )

    /**
     * The chat's move ([ChatMigration.MOVE]) made on a backup from before it, as at a start: Felix's records
     * (in its log's part) and his state (a part of its own then, the runtime's `felix`) into its chat, put in
     * [out] in the chat's new shape; the log's part loses them by its own migration after. The version the
     * chat then has, or null where there's nothing to move ([said] or its chat is of that [shape] already).
     */
    private fun moved(backup: JsonObject, out: MutableMap<String, JsonElement>, said: Int?, shape: Shape?): Int? {
        val chat = backup["chat"] as? JsonObject
        if ((said ?: chat?.let(Migration::versionOf) ?: 1) >= 2 || (shape?.version ?: 1) < 2) return null
        val runtime = JsonObject(listOfNotNull((backup["felix"] as? JsonObject)?.let { "felix" to it }).toMap())
        val titles = (backup["ownTasks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { t -> (t["id"] as? JsonPrimitive)?.contentOrNull?.let { id -> id to ((t["title"] as? JsonPrimitive)?.contentOrNull ?: "") } }.toMap()
        val merged = ChatMigration.merged(chat ?: JsonObject(emptyMap()), (backup["log"] as? JsonObject)?.get("felix") as? JsonArray, runtime, backup["memory"] as? JsonObject, titles)
        out["chat"] = JsonObject(merged + (Migration.VERSION to JsonPrimitive(2)))
        out.remove("felix")
        return 2
    }

    /**
     * [backup]'s parts brought from the versions their stores had (its `versions`; else a whole part's
     * own; else 1) to [shapes]' by each store's migrations, a store's parts together as its file would
     * hold them; its `versions` then [shapes]'. Null where a store it carries is newer than this build's
     * (wherever its parts are), or a part can't be brought: newer than this build's, or a step on the way missing.
     */
    fun upgraded(backup: JsonObject, shapes: Map<String, Shape>): JsonObject? {
        val out = backup.toMutableMap()
        val given = (backup["versions"] as? JsonObject).orEmpty()
        // Moves between stores first, as at a start ([Stores.MOVES]): the chat's says its new version.
        val versions = given + listOfNotNull(moved(backup, out, (given["chat.json"] as? JsonPrimitive)?.intOrNull, shapes["chat.json"])?.let { "chat.json" to JsonPrimitive(it) })
        // The tasks' ([TasksMigration.MOVE]): its instructions marked Felix's where the chat it carries holds their cards.
        val instructionsAt = (given["instructions.json"] as? JsonPrimitive)?.intOrNull ?: (backup["instructions"] as? JsonObject)?.let(Migration::versionOf) ?: 1
        if (instructionsAt < 2 && (shapes["instructions.json"]?.version ?: 1) >= 2) {
            (backup["instructions"] as? JsonArray)?.let { out["instructions"] = TasksMigration.backedUp(it, listOfNotNull(backup["chat"] as? JsonObject, backup["archive"] as? JsonObject)) }
        }
        for ((file, shape) in shapes) {
            val said = (versions[file] as? JsonPrimitive)?.intOrNull
            // A store the backup carries (PARTS) that's newer than this build's isn't read, wherever its parts
            // are: one of its parts renamed or moved there would otherwise be passed over, and the backup
            // restored without it. One it doesn't carry (`runtime.json`, say) has nothing in it to lose.
            if (said != null && said > shape.version && PARTS.values.any { it.first == file }) return null
            val parts = PARTS.filterValues { it.first == file }.keys.filter { it in backup }
            if (parts.isEmpty()) continue
            val whole = parts.singleOrNull { PARTS.getValue(it).second == null }
            if (whole != null) {
                val part = out[whole] as? JsonObject ?: continue
                val at = said ?: Migration.versionOf(part)
                out[whole] = Migration.upgrade(JsonObject(part + (Migration.VERSION to JsonPrimitive(at))), shape.version, shape.migrations) ?: return null
            } else {
                // The fields a store's parts are, put together as its file would hold them, and taken out again.
                // As the moves left them ([out]).
                val held = JsonObject(parts.associate { PARTS.getValue(it).second!! to out.getValue(it) } + (Migration.VERSION to JsonPrimitive(said ?: 1)))
                val brought = Migration.upgrade(held, shape.version, shape.migrations) ?: return null
                parts.forEach { part -> brought[PARTS.getValue(part).second!!]?.let { out[part] = it } }
            }
        }
        out["versions"] = JsonObject(versions + shapes.mapValues { JsonPrimitive(it.value.version) })
        return JsonObject(out)
    }

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
            if (log.days.any { it.plannedMin < 0 || (it.doneMin ?: 0) < 0 || runCatching { LocalDate.parse(it.date) }.isFailure }) add("a day's plan")
            if (log.checkIns.any { it.feel !in 1..5 }) add("a check-in's answer")
            // The weeks' rollups (PL-10), as a review writes them: each from its Monday, no count below
            // nothing, and none past what a week holds.
            if (log.weeks.any { w ->
                    runCatching { LocalDate.parse(w.week) }.getOrNull()?.dayOfWeek != DayOfWeek.MONDAY ||
                        listOf(w.minutes, w.finished, w.onTime, w.dated, w.blocks, w.fullDays, w.slotsKept, w.slotsMissed).any { it < 0 } ||
                        w.onTime > w.finished || w.dated > w.finished || w.fullDays > 7 || w.minutes > 7 * DAY_MIN
                } || log.weeks.map { it.week }.toSet().size != log.weeks.size
            ) add("a week's totals")
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
            val kept = Memories
            if (kept.chars(memory.facts) > kept.FACT_BUDGET_CHARS || memory.rules.size > kept.RULE_MAX ||
                memory.facts.any { it.topic.isBlank() || it.text.isBlank() || it.topic.length > kept.MAX_TOPIC || it.text.length > kept.MAX_TEXT } ||
                memory.rules.any { it.text.isBlank() || it.text.length > kept.MAX_TEXT }
            ) add("what's remembered")
            // What a card takes in AnkiDroid (DX-11): within the bounds it's learned in, each from its stretches.
            if (calibration.ankiSecondsPerCard.values.any { !it.isFinite() || it !in AnkiProgress.MIN_SECONDS..AnkiProgress.MAX_SECONDS } ||
                calibration.ankiSecondsPerCard.keys != calibration.ankiStretches.keys || calibration.ankiStretches.values.any { it !in 1..AnkiProgress.STRETCHES }
            ) add("what the app learned about Anki")
            if (backup.eventAnswers.values.any { !answer(it) }) add("a calendar answer")
            if (!backup.aiUsage.spentUsd.isFinite() || backup.aiUsage.spentUsd < 0 || backup.aiUsage.calls < 0) add("Claude's spending")
            if (backup.instructions.any { !wellFormed(it, backup.exportedAt) }) add("an instruction")
            if (backup.ownTasks.any { !wellFormed(it) } || backup.ownTasks.map { it.id }.toSet().size != backup.ownTasks.size) add("a task of your own")
            if (!wellFormed(backup.school)) add("the school calendar")
            if (!wellFormed(backup.felixStore)) add("Felix's notes and reminders")
            if (backup.gmailWrites.any { !GmailWrites.wellFormed(it) }) add("a write to Gmail")
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
        val minutes = 0..OwnTaskRules.MINUTES.last
        return task.source == Source.Own && task.id == TaskItem.id(Source.Own, task.sourceId) &&
            task.workedMin in minutes && task.photoMin in minutes && task.stepsWorkedMin in minutes &&
            (task.mine.minutes?.minutes == null || task.mine.minutes?.minutes in OwnTaskRules.MINUTES) &&
            (task.peakEffortMin == null || task.peakEffortMin in minutes) &&
            // No source to say how far it is: yours are done or not, never part way. Nor to hide it
            // till a day: a start of yours is when its work is planned from (notBefore).
            task.sourceProgress == 0.0 && (task.firstProgress ?: 0.0) == 0.0 &&
            task.availableFrom == null && task.sourceValues?.availableFrom == null &&
            // Nor anything else only a source gives (an Anki deck's day's cards, the enrichment's
            // estimate, a source's extras), nor a miss, which only a source's counts and a week's end can make (D-124).
            task.stepsPerDay == null && !task.derived && task.aiEffortMin == null &&
            // Its one extra of yours: where it's done, when that's at home (PL-15).
            task.extra.keys.all { it == OwnTaskRules.EXTRA_WHERE } && task.extra[OwnTaskRules.EXTRA_WHERE].let { it == null || it == "home" } &&
            (task.status != Status.Missed || task.weekly != null) && task.weekly?.let { runCatching { LocalDate.parse(it.week) }.isSuccess } != false && task.photosCounted.values.all { it in minutes } &&
            task.subSteps.all { it.timedMin in minutes } &&
            task.enrichment == null &&
            OwnTaskRules.problem(OwnTaskRules.draftOf(task)) == null
    }

    /**
     * The tasks of your own to add from a backup's ([backed]), beside those [here]: those not here
     * already, as they were, but for what instructions laid over them, which is laid again from the
     * instructions here. One here wins: it's the newer.
     */
    fun restoredOwnTasks(backed: List<TaskItem>, here: List<TaskItem>): List<TaskItem> = backed.filter { b -> here.none { it.id == b.id } }
        .map { it.copy(mine = Mine(sameAs = it.mine.sameAs)).enriched() }

    /** The most changes one instruction holds, as Claude's reading of one is held to. */
    private const val MAX_CHANGES = 40

    /**
     * Whether [instruction] is one this app could have made: an id, and each change of its kind with
     * what it needs, in range (times within a day, a weekday 1 to 7, a date that reads, a date and
     * time within a couple of years of when the instruction was made, which is no later than [exportedAt]):
     * its own making, not the backup's, as an instruction in use stays in use, and every backup holds it,
     * however old. The tasks and events it names aren't checked: on a phone just set up, their sources
     * haven't been read yet.
     */
    private fun wellFormed(instruction: Instruction, exportedAt: Long): Boolean {
        if (instruction.id.isBlank() || instruction.changes.size > MAX_CHANGES || instruction.at > exportedAt + DAY_MS) return false
        fun named(id: String?) = !id.isNullOrBlank()
        fun day(change: Change) = when {
            change.date != null -> change.weekday == null && runCatching { LocalDate.parse(change.date) }.isSuccess
            else -> change.weekday in 1..7
        }
        fun time(at: Long?) = at != null && at in (instruction.at - 730 * DAY_MS)..(instruction.at + 400 * DAY_MS)
        return instruction.changes.all { change ->
            when (change.type) {
                ChangeType.NotATask -> named(change.taskId)
                ChangeType.StartFrom, ChangeType.DueBy -> named(change.taskId) && time(change.time)
                ChangeType.Minutes -> named(change.taskId) && change.minutes in OwnTaskRules.MINUTES
                ChangeType.After -> named(change.taskId) && named(change.afterTaskId) && change.afterTaskId != change.taskId
                ChangeType.EventTime -> named(change.eventKey) && change.eventAnswer?.let(::answer) == true
                ChangeType.DayLimit -> change.freeMin in 0..DAY_MIN && day(change)
                ChangeType.BusyTime -> change.startMin in 0 until DAY_MIN && (change.endMin ?: -1) in ((change.startMin ?: DAY_MIN) + 1)..DAY_MIN && day(change) &&
                    (change.title?.length ?: 0) <= InstructionRules.MAX_BUSY_TITLE
                // Kept in the school calendar once applied, never in an instruction in use; well formed all the same.
                ChangeType.NoSchool -> isDate(change.date) && change.endDate.let { end -> end == null || isDate(end) && end >= change.date!! }
                ChangeType.HalfDay -> isDate(change.date) && change.endMin in 0..DAY_MIN
                // A day or a time for a task (PL-04).
                ChangeType.OnDay -> named(change.taskId) && isDate(change.date)
                ChangeType.AtTime -> named(change.taskId) && isDate(change.date) && change.startMin in 0 until DAY_MIN && time(change.time)
                ChangeType.SameAs -> named(change.taskId) && named(change.afterTaskId) && change.afterTaskId != change.taskId
                // A task's steps (AI-19): some, each named, with its minutes.
                ChangeType.Steps -> named(change.taskId) && change.steps.orEmpty().let { steps -> steps.size in 1..OwnTaskRules.MAX_STEPS && steps.all { it.title.isNotBlank() && it.minutes in OwnTaskRules.MINUTES } }
                // A task's name: one, within a name's length.
                ChangeType.Rename -> named(change.taskId) && change.title?.let { it.isNotBlank() && it.length <= OwnTaskRules.MAX_TITLE } == true
                // A day for only some tasks (D-115): a task named, a date, and what else may come in, nothing or one of the two.
                ChangeType.DayOnly -> named(change.taskId) && isDate(change.date) && change.alsoAllow in setOf(null, InstructionRules.ALSO_ANKI, InstructionRules.ALSO_DEADLINES)
                // A day's breaks outside (D-116): a date; a break moved by its own time to a minute of the day, one not laid by its own
                // time or all of the day's (-1), one added at a minute of the day.
                ChangeType.OutsideAt -> isDate(change.date) && change.outsideMin in 0 until DAY_MIN && change.startMin in 0 until DAY_MIN
                ChangeType.NoOutside -> isDate(change.date) && (change.outsideMin == -1 || change.outsideMin in 0 until DAY_MIN)
                ChangeType.OutsideAdded -> isDate(change.date) && change.startMin in 0 until DAY_MIN
                // Work every week (D-124): a rule as the app makes one, or the one ended.
                ChangeType.Weekly, ChangeType.WeeklyEnd -> change.weekly?.let(::wellFormed) == true
            }
        }
    }

    /** Whether [rule] is one this app could have made: an id, a name, a week's minutes, days of the week and a session in range. */
    private fun wellFormed(rule: WeeklyRule): Boolean =
        rule.id.isNotBlank() && rule.title.isNotBlank() && rule.title.length <= WeeklyRules.MAX_TITLE && rule.minutes in WeeklyRules.MINUTES &&
            rule.days.all { it in 1..7 } && rule.days.size <= rule.minutes && (rule.sessionMin == 0 || rule.sessionMin in WeeklyRules.SESSION) &&
            rule.where in setOf("anywhere", "home") && (rule.subject?.length ?: 0) <= OwnTaskRules.MAX_CLASS

    private fun isDate(text: String?): Boolean = text != null && runCatching { LocalDate.parse(text) }.isSuccess

    /**
     * Whether [felix] is what the app keeps: each note and reminder under an id of its own, with
     * words within their length, and a task's id, where one's given, not blank; a note on a task or standing,
     * never both nor neither, a standing one's scope named within [Notes.MAX_SCOPE]; a reminder due as
     * its kind needs (a time, its task's slot from a time, minutes before its task's deadline). Not how
     * many are still to come: a snooze brings one that came back among them past [Reminders.MAX_PENDING],
     * and the restore adds a backup's within it ([mergedFelix]).
     */
    fun wellFormed(felix: FelixStore): Boolean {
        fun words(text: String, max: Int) = text.isNotBlank() && text.length <= max
        val notes = felix.notes.all { n ->
            n.id.isNotBlank() && words(n.text, Notes.MAX_TEXT) && n.taskId?.isBlank() != true && (n.taskId != null) != (n.scope != null) && n.scope?.isEmpty != true &&
                n.scope?.let { s -> listOfNotNull(s.subject, s.className, s.titleContains).all { it.length <= Notes.MAX_SCOPE } } != false
        }
        val reminders = felix.reminders.all { r ->
            r.id.isNotBlank() && words(r.text, Reminders.MAX_TEXT) && r.taskId?.isBlank() != true && when (r.due.kind) {
                DueKind.At -> r.due.at != null
                DueKind.SlotStart -> r.taskId != null && r.due.at != null
                DueKind.BeforeDue -> r.taskId != null && (r.due.minutesBefore ?: -1) in 0..Reminders.MAX_BEFORE_MIN
            }
        }
        return notes && reminders && felix.notes.map { it.id }.toSet().size == felix.notes.size && felix.reminders.map { it.id }.toSet().size == felix.reminders.size
    }

    /**
     * Felix's notes and reminders [here], with those of a backup's ([backed]) not here, by id, added: this phone's own
     * win. Within what the app keeps, as each was set (Reminders.MAX_PENDING still to come, [Notes.PER_TASK] on a
     * task and [Notes.MAX_STANDING] standing, none deleted counted), the backup's past it left out, in its order;
     * with how many were.
     */
    fun mergedFelix(here: FelixStore, backed: FelixStore): Pair<FelixStore, Int> {
        var left = 0
        var pending = here.reminders.count(Reminders::pending)
        val reminders = backed.reminders.filter { b -> here.reminders.none { it.id == b.id } }.filter { b ->
            if (!Reminders.pending(b)) return@filter true
            (pending < Reminders.MAX_PENDING).also { if (it) pending++ else left++ }
        }
        val live = Notes.live(here.notes)
        val onTask = live.filter { it.taskId != null }.groupingBy { it.taskId!! }.eachCount().toMutableMap()
        var standing = live.count { it.scope != null }
        val notes = backed.notes.filter { b -> here.notes.none { it.id == b.id } }.filter { b ->
            val task = b.taskId
            when {
                b.deletedAt != null -> true
                task != null -> ((onTask[task] ?: 0) < Notes.PER_TASK).also { if (it) onTask[task] = (onTask[task] ?: 0) + 1 else left++ }
                else -> (standing < Notes.MAX_STANDING).also { if (it) standing++ else left++ }
            }
        }
        return here.copy(notes = here.notes + notes, reminders = here.reminders + reminders) to left
    }

    /** Whether [school] is one this app could have kept: dates that read, stretches in order, an end within the day. */
    fun wellFormed(school: SchoolCalendar): Boolean =
        school.noSchool.all { isDate(it.from) && isDate(it.to) && it.from <= it.to } && school.halfDays.all { isDate(it.date) && it.endsAtMin in 0..DAY_MIN }

    /** A calendar answer as they're kept: "free", "busy", or "load:<minutes>" within a day. */
    private fun answer(value: String): Boolean =
        value == "free" || value == "busy" || value.removePrefix("load:").takeIf { it != value }?.toIntOrNull()?.let { it in 0..DAY_MIN } == true

    /**
     * The instructions to add from a backup's ([backed]), beside those [here]: those in use each as
     * applying it here would go, in the order they were applied: one already here isn't added; one
     * that would have tasks wait for each other in a circle comes back unclear, saying so. The rest,
     * due dates as well, armed or not (D-46), are in use at once. Those not in use (a reset's copy
     * holds them) come back as they were, after them: one being read is read when Claude can be asked.
     */
    fun restoredInstructions(backed: List<Instruction>, here: List<Instruction>): List<Instruction> {
        val added = mutableListOf<Instruction>()
        for (instruction in backed.filter { it.state == InstructionStatus.Applied }.sortedBy { it.appliedAt ?: it.at }) {
            if ((here + added).any { it.id == instruction.id }) continue
            val inUse = InstructionState(here + added).applied
            added += if (InstructionRules.makesCircle(instruction.changes, inUse)) {
                instruction.copy(state = InstructionStatus.Unclear, appliedAt = null, note = "It would have tasks wait for each other in a circle with those here, so it wasn't applied")
            } else {
                instruction
            }
        }
        for (instruction in backed.filter { it.state != InstructionStatus.Applied }) {
            if ((here + added).none { it.id == instruction.id }) added += instruction
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
     * key is this phone's. They go through [com.thomaswcode.decrastination.protect.Protection.changeSettings],
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
    fun mergedMemory(here: MemoryState, backup: MemoryState): MemoryState {
        fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
        // Each with its own time, so the room is kept oldest first, wherever each came from.
        val facts = (here.facts + backup.facts.filter { f -> here.facts.none { same(it.topic, f.topic) } }.distinctBy { it.topic.trim().lowercase() }).sortedBy { it.at }.toMutableList()
        while (facts.size > 1 && Memories.chars(facts) > Memories.FACT_BUDGET_CHARS) facts.removeAt(0)
        val rules = (here.rules + backup.rules.filter { r -> here.rules.none { same(it.text, r.text) } }.distinctBy { it.text.trim().lowercase() }).take(Memories.RULE_MAX)
        return MemoryState(facts, rules)
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
        weeks = (current.weeks + imported.weeks).distinctBy { it.week }.sortedBy { it.week },
    ).trimmed(now)
}
