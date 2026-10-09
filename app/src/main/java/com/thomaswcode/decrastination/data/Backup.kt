package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.enrich.AiUsage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a backup keeps (PLAN.md Phase 6): the settings, the activity log, what the app has learned,
 * and your answers about calendar events: what the sources can't give back. Never the passwords
 * or keys, which stay in this phone's encrypted store, nor the tasks, which the next sync reads
 * again.
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
