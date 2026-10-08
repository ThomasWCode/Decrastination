package com.thomaswcode.decrastination.probe

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri

/**
 * The Teams Assignments widget's provider (its `provider/AssignmentsContract.kt`, 0.3.0 on).
 * Signature-protected: only granted because this app is signed with the widget's key.
 */
object TeamsProvider {
    private const val AUTHORITY = "com.teamsassignments.widget.assignments"
    val root: Uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY).build()
    private val assignmentsUri: Uri = root.buildUpon().appendPath("assignments").build()
    private val stateUri: Uri = root.buildUpon().appendPath("state").build()

    const val METHOD_REQUEST_SYNC = "requestSync"
    const val METHOD_OPEN = "open"

    fun assignments(resolver: ContentResolver): List<Map<String, Any?>> = resolver.rows(assignmentsUri)

    fun state(resolver: ContentResolver): Map<String, Any?> = resolver.rows(stateUri).single()

    /** Starts something in Teams that takes over the screen: only on a tap. */
    fun call(resolver: ContentResolver, method: String, arg: String? = null): Bundle? = resolver.call(root, method, arg, null)
}

/** AnkiDroid's FlashCardsContract provider. Needs the READ_WRITE_DATABASE runtime permission. */
object AnkiProvider {
    const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    private val decksUri: Uri = "content://com.ichi2.anki.flashcards/decks".toUri()

    fun decks(resolver: ContentResolver): List<Map<String, Any?>> =
        resolver.rows(decksUri, arrayOf("deck_id", "deck_name", "deck_count"))
}

/** Every row of [uri] as column → value. Throws when there's no such provider. */
private fun ContentResolver.rows(uri: Uri, projection: Array<String>? = null): List<Map<String, Any?>> =
    query(uri, projection, null, null, null)?.use { it.rows() }
        ?: throw IllegalStateException("No provider answers $uri")

private fun Cursor.rows(): List<Map<String, Any?>> = buildList {
    while (moveToNext()) {
        add(
            columnNames.indices.associate { i ->
                columnNames[i] to when (getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
                    Cursor.FIELD_TYPE_BLOB -> "<${getBlob(i).size} bytes>"
                    else -> getString(i)
                }
            },
        )
    }
}
