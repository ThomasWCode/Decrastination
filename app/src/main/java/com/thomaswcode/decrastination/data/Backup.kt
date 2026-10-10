package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Instructions
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
) {
    companion object {
        const val APP = "Decrastination"
        const val FORMAT = 1
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
            if (log.sessions.any { it.workedMin !in 0..DAY_MIN || it.plannedMin !in 0..DAY_MIN || it.startedAt < 0 }) add("a focus session's minutes")
            if (log.completions.any { it.estimateMin < 0 || it.workedMin < 0 }) add("a finished task's minutes")
            if (log.days.any { it.plannedMin < 0 || (it.doneMin ?: 0) < 0 || runCatching { java.time.LocalDate.parse(it.date) }.isFailure }) add("a day's plan")
            if (log.checkIns.any { it.feel !in 1..5 }) add("a check-in's answer")
            val calibration = backup.calibration
            if (calibration.multipliers.values.any { !it.isFinite() || it !in Calibrator.MIN_MULTIPLIER..Calibrator.MAX_MULTIPLIER }) add("what the app learned about estimates")
            if (calibration.marginDays.values.any { it !in SettingsLimits.MARGIN_DAYS }) add("what the app learned about margins")
            if (calibration.boxMin.values.any { it !in SettingsLimits.BOX_MIN }) add("what the app learned about box lengths")
            if (backup.eventAnswers.values.any { !answer(it) }) add("a calendar answer")
            if (!backup.aiUsage.spentUsd.isFinite() || backup.aiUsage.spentUsd < 0 || backup.aiUsage.calls < 0) add("Claude's spending")
        }
        return bad.takeIf { it.isNotEmpty() }?.let { "it holds what this app never writes (${it.joinToString(", ")})" }
    }

    private const val DAY_MIN = 24 * 60

    /** A calendar answer as they're kept: "free", "busy", or "load:<minutes>" within a day. */
    private fun answer(value: String): Boolean =
        value == "free" || value == "busy" || value.removePrefix("load:").takeIf { it != value }?.toIntOrNull()?.let { it in 0..DAY_MIN } == true

    /**
     * The instructions to add from a backup's ([backed], those in use), beside those [here], each as
     * applying it here would go, in the order they were applied: one already here isn't added; one
     * that changes a due date, once [armed], comes back read, waiting for your dad's code; one that
     * would have tasks wait for each other in a circle comes back unclear, saying so. The rest are
     * in use at once.
     */
    fun restoredInstructions(backed: List<Instruction>, here: List<Instruction>, armed: Boolean): List<Instruction> {
        val added = mutableListOf<Instruction>()
        for (instruction in backed.filter { it.state == InstructionStatus.Applied }.sortedBy { it.appliedAt ?: it.at }) {
            if ((here + added).any { it.id == instruction.id }) continue
            val inUse = InstructionState(here + added).applied
            added += when {
                Instructions.makesCircle(instruction.changes, inUse) ->
                    instruction.copy(state = InstructionStatus.Unclear, appliedAt = null, note = "It would have tasks wait for each other in a circle with those here, so it wasn't applied")
                armed && Instructions.needsCode(instruction.changes) -> instruction.copy(state = InstructionStatus.Understood, appliedAt = null)
                else -> instruction
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
        backup.settings.copy(armed = asked.armed, aiKeyActive = asked.aiKeyActive)

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
     * [current] with [imported]'s records added. One already here (the same moment and task, the
     * same day or week) isn't doubled, and this phone's own version of it wins.
     */
    fun mergeLog(current: ActivityLog, imported: ActivityLog, now: Long): ActivityLog = ActivityLog(
        sessions = (current.sessions + imported.sessions).distinctBy { it.taskId to it.startedAt }.sortedBy { it.startedAt },
        completions = (current.completions + imported.completions).distinctBy { it.taskId to it.doneAt }.sortedBy { it.doneAt },
        blocks = (current.blocks + imported.blocks).distinctBy { Triple(it.at, it.target, it.reason) }.sortedBy { it.at },
        protection = (current.protection + imported.protection).distinctBy { it.at }.sortedBy { it.at },
        days = (current.days + imported.days).distinctBy { it.date }.sortedBy { it.date },
        checkIns = (current.checkIns + imported.checkIns).distinctBy { it.weekOf }.sortedBy { it.at },
        reviews = (current.reviews + imported.reviews).distinctBy { it.at }.sortedBy { it.at },
    ).trimmed(now)
}
