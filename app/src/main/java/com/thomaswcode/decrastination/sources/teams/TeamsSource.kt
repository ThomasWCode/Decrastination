package com.thomaswcode.decrastination.sources.teams

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.sources.ReadContext
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.sources.SourceUnavailable
import com.thomaswcode.decrastination.sources.TaskSource
import kotlinx.coroutines.Dispatchers
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
