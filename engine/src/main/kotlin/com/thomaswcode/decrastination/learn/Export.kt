package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.ChatHistory
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.data.ActivityLog
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The record as CSV files (PL-11), to share: what was finished, each slot, each day, the blocks,
 * Felix's own messages (from the chat's [messages], its archive's with its window's) and the
 * calibration, a file each; times local, as the app shows them. Pure but for [zip].
 */
object Export {
    /** File name to its CSV text. */
    fun csv(log: ActivityLog, calibration: Calibration, zone: ZoneId, messages: List<ChatMessage> = emptyList()): Map<String, String> {
        fun t(at: Long?) = at?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().withNano(0).toString() }
        return linkedMapOf(
            "completions.csv" to table(
                listOf("task", "title", "source", "kind", "class", "estimate_min", "worked_min", "due", "hard_deadline", "first_seen", "done", "assessment", "note", "by_hand", "photo", "your_estimate"),
                log.completions.map { listOf(it.taskId, it.title, it.source, it.kind, it.className, it.estimateMin, it.workedMin, t(it.dueAt), it.hardDeadline, t(it.firstSeenAt), t(it.doneAt), it.assessment, it.note, it.byHand, it.photo, it.yourEstimate) },
            ),
            "slots.csv" to table(
                listOf("date", "piece", "task", "kind", "class", "label", "planned_min", "start", "end", "window", "outcome", "worked_min", "photo", "earned", "at"),
                log.slots.map { listOf(it.date, it.key, it.taskId, it.kind, it.className, it.label, it.plannedMin, t(it.start), t(it.end), it.window, it.outcome, it.workedMin, it.photo, it.earned, t(it.at)) },
            ),
            "days.csv" to table(
                listOf("date", "kind", "planned_min", "school_min", "done_min", "full"),
                log.days.map { listOf(it.date, it.kind, it.plannedMin, it.schoolMin, it.doneMin, it.full) },
            ),
            "blocks.csv" to table(listOf("at", "what", "why"), log.blocks.map { listOf(t(it.at), it.target, it.reason) }),
            "felix.csv" to table(
                listOf("at", "why", "said", "replies", "piece", "task", "reply", "replied"),
                ChatHistory.his(messages).map { m ->
                    val reply = ChatHistory.replyOf(m, messages)
                    listOf(t(m.at), m.kind, m.text, m.suggestedReplies.joinToString(" | "), m.pieceKey, m.about.taskId, reply?.text, t(reply?.at))
                },
            ),
            "calibration.csv" to table(
                listOf("what", "key", "value", "rests_on"),
                calibration.multipliers.entries.sortedBy { it.key }.map { listOf("multiplier", it.key, it.value, calibration.timed[it.key]) } +
                    calibration.marginDays.entries.sortedBy { it.key.ordinal }.map { listOf("margin_days", it.key, it.value, null) } +
                    calibration.yields.entries.sortedBy { it.key.ordinal }.map { listOf("yield", it.key, it.value, calibration.yieldDays[it.key]) },
            ),
        )
    }

    /**
     * A cell as CSV holds it (RFC 4180): quoted, its quotes doubled, where it holds a comma, a quote
     * or a line break. Text a spreadsheet would take for a formula (`=`, `+`, `-` or `@` first, or a
     * tab or a return) is led by `'`, so it's shown as it is; a number stays a number.
     */
    fun cell(text: String): String {
        val safe = if (text.isNotEmpty() && text[0] in FORMULA && text.toDoubleOrNull() == null) "'$text" else text
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private const val FORMULA = "=+-@\t\r"

    /** [header] and [rows] as a CSV table, lines ending CRLF. */
    fun table(header: List<String>, rows: List<List<Any?>>): String =
        (listOf(header) + rows.map { row -> row.map { it?.toString().orEmpty() } }).joinToString("") { line -> line.joinToString(",") { cell(it) } + "\r\n" }

    /** [files] zipped into [out]. */
    fun zip(files: Map<String, String>, out: File) {
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream()).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
