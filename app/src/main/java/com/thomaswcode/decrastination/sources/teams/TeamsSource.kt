package com.thomaswcode.decrastination.sources.teams

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Fetched
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.ReadContext
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.sources.SourceUnavailable
import com.thomaswcode.decrastination.sources.TaskSource
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import kotlinx.coroutines.withContext

/**
 * The Teams Assignments widget's provider (its `provider/AssignmentsContract.kt`, 0.3.0 on;
 * docs/spec/sources.md). Signature-protected: granted only because this app is signed with
 * the widget's key. Queries and calls block while the widget's process starts, so they run off
 * the main thread.
 */
object TeamsProvider {
    const val PACKAGE = "com.teamsassignments.widget"
    private const val AUTHORITY = "com.teamsassignments.widget.assignments"
    val root: Uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY).build()
    private val assignmentsUri: Uri = root.buildUpon().appendPath("assignments").build()
    private val stateUri: Uri = root.buildUpon().appendPath("state").build()
    private val returnedUri: Uri = root.buildUpon().appendPath("returned").build()

    const val METHOD_REQUEST_SYNC = "requestSync"
    const val METHOD_OPEN = "open"
    const val RESULT_STARTED = "started"
    const val RESULT_REASON = "reason"

    fun assignments(resolver: ContentResolver): List<Map<String, Any?>> = resolver.rows(assignmentsUri)

    fun state(resolver: ContentResolver): Map<String, Any?> = resolver.rows(stateUri).single()

    /**
     * The work handed back to you with feedback (NF-06), from a widget that lists it; none from one
     * that doesn't, which refuses a path it doesn't know.
     */
    fun returned(resolver: ContentResolver): List<Map<String, Any?>> = try {
        resolver.rows(returnedUri)
    } catch (e: IllegalArgumentException) {
        emptyList()
    }

    /** Starts something in Teams that takes over the screen, so only at a deliberate moment. */
    fun call(resolver: ContentResolver, method: String, arg: String? = null): Bundle? = resolver.call(root, method, arg, null)

    /** Every row of [uri] as column → value. */
    private fun ContentResolver.rows(uri: Uri): List<Map<String, Any?>> {
        val cursor = try {
            query(uri, null, null, null, null)
        } catch (e: SecurityException) {
            throw SourceUnavailable("The Teams widget refused access: is it signed with the same key? (${e.message})")
        } ?: throw SourceUnavailable("The Teams Assignments widget isn't installed, or is older than 0.3.0")
        return cursor.use { it.rows() }
    }

    private fun Cursor.rows(): List<Map<String, Any?>> = buildList {
        while (moveToNext()) {
            add(
                columnNames.indices.associate { i ->
                    columnNames[i] to when (getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
                        else -> getString(i)
                    }
                },
            )
        }
    }
}

/** Turns the provider's rows into tasks. Pure, for the tests. */
object TeamsRows {
    const val EXTRA_TAB = "tab"
    const val EXTRA_DUE_TEXT = "dueText"

    /** The web addresses its instructions link to (DX-08), one a line, as the widget gives them (0.4.0 on). */
    const val EXTRA_LINKS = Enrichments.EXTRA_LINKS

    /** On a returned assignment's feedback to read (NF-06): the assignment's key, for Open. */
    const val EXTRA_FEEDBACK_ON = "feedbackOn"

    /** How long after the work comes back its feedback is due to be read (NF-06), and how long reading it takes. */
    const val FEEDBACK_DAYS = 3L
    const val FEEDBACK_MIN = 10
    private const val DAY_MS = 24 * 3_600_000L

    fun fetched(row: Map<String, Any?>): Fetched? {
        // A blank key is no identity: every such row would be the same task.
        val key = (row["key"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return Fetched(
            sourceId = key,
            title = (row["title"] as? String).orEmpty().ifBlank { "Untitled assignment" },
            kind = Kind.Homework,
            detail = (row["description"] as? String).orEmpty(),
            className = (row["class_name"] as? String)?.takeIf { it.isNotBlank() },
            dueAt = row["due_at"] as? Long,
            extra = buildMap {
                (row["tab"] as? String)?.let { put(EXTRA_TAB, it) }
                (row["due_text"] as? String)?.let { put(EXTRA_DUE_TEXT, it) }
                // Its reference materials by name and its links (DX-08), where the widget gives them
                // (0.4.0 on): the files kept as an email's are, and their names sent with it.
                lines(row["attachments"]).takeIf { it.isNotEmpty() }?.let { names ->
                    put(com.thomaswcode.decrastination.sources.gmail.GmailThreads.EXTRA_ATTACHMENTS, com.thomaswcode.decrastination.sources.gmail.GmailThreads.attachmentsExtra(names.map { com.thomaswcode.decrastination.sources.gmail.Mime.Attachment(it, "") }))
                }
                lines(row["links"]).takeIf { it.isNotEmpty() }?.let { put(EXTRA_LINKS, it.joinToString("\n")) }
            },
        )
    }

    /** A column of lines, each once, blanks left out; none where the widget gives no such column. */
    private fun lines(value: Any?): List<String> = (value as? String).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** [task]'s links (DX-08): none for one read before the widget gave them. */
    fun linksOf(task: TaskItem): List<String> = task.extra[EXTRA_LINKS].orEmpty().lines().filter { it.isNotBlank() }

    /**
     * Every row as a task. A row without its key is a broken read, not one to skip: skipped, its
     * assignment would be taken as handed in (BUG-P2-007).
     */
    fun all(rows: List<Map<String, Any?>>): List<Fetched> =
        rows.map { row -> fetched(row) ?: throw IOException("The Teams widget listed an assignment without its key") }

    /**
     * A returned assignment's feedback, as a task to read it (NF-06): "Read the feedback on …", Admin,
     * ten minutes, due three days after the work came back, the feedback its text, done by its tick
     * ([TaskItem.tickDone]) or once the widget no longer lists it. Null without a key.
     */
    fun returned(row: Map<String, Any?>): Fetched? {
        val key = (row["key"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val title = (row["title"] as? String)?.takeIf { it.isNotBlank() } ?: "an assignment"
        val returnedAt = row["returned_at"] as? Long
        return Fetched(
            // Each time it comes back, its own: a resubmission returned again is feedback to read again.
            sourceId = "$key:feedback" + (returnedAt?.let { ":$it" } ?: ""),
            title = "Read the feedback on $title",
            kind = Kind.Admin,
            detail = (row["feedback"] as? String).orEmpty(),
            className = (row["class_name"] as? String)?.takeIf { it.isNotBlank() },
            dueAt = returnedAt?.let { it + FEEDBACK_DAYS * DAY_MS },
            sourceEffortMin = FEEDBACK_MIN,
            extra = mapOf(TaskItem.EXTRA_TICK_DONE to "true", EXTRA_FEEDBACK_ON to key),
        )
    }

    /** Every returned assignment's feedback as a task; a row without its key fails the read, as [all]'s do. */
    fun allReturned(rows: List<Map<String, Any?>>): List<Fetched> =
        rows.map { row -> returned(row) ?: throw IOException("The Teams widget listed returned work without its key") }

    /** A line about the widget's own state worth showing, or null when all is well. */
    fun note(state: Map<String, Any?>): String? {
        val status = state["status"] as? String
        val message = (state["status_message"] as? String)?.takeIf { it.isNotBlank() }
        return when {
            (state["sync_service_enabled"] as? Long) == 0L -> "The Teams widget's sync service is off"
            status == "running" -> "The Teams widget is syncing"
            status == "failed" -> "The Teams widget's last sync failed" + (message?.let { ": $it" } ?: "")
            else -> null
        }
    }
}

class TeamsSource(private val resolver: ContentResolver) : TaskSource {
    override val source = Source.Teams

    override suspend fun read(context: ReadContext): SourceRead = withContext(Dispatchers.IO) {
        val rows = TeamsProvider.assignments(resolver)
        // Work handed back with feedback (NF-06), where the widget lists it.
        val returned = TeamsProvider.returned(resolver)
        val state = TeamsProvider.state(resolver)
        SourceRead(
            items = TeamsRows.all(rows) + TeamsRows.allReturned(returned),
            dataAsOf = state["last_success_at"] as? Long,
            note = TeamsRows.note(state),
        )
    }
}
