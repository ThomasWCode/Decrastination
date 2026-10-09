package com.thomaswcode.decrastination.sources.anki

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.sources.ReadContext
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.sources.SourceUnavailable
import com.thomaswcode.decrastination.sources.TaskSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AnkiDroid's own provider (`FlashCardsContract`, docs/data-sources.md §3), behind the runtime
 * permission AnkiDroid defines. Queries can block while AnkiDroid's process starts, so they run
 * off the main thread.
 */
object AnkiProvider {
    const val PACKAGE = "com.ichi2.anki"
    const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    private val decksUri = "content://com.ichi2.anki.flashcards/decks".toUri()
    private val notesUri = "content://com.ichi2.anki.flashcards/notes".toUri()
    private val selectedDeckUri = "content://com.ichi2.anki.flashcards/selected_deck".toUri()

    fun decks(resolver: ContentResolver): List<Deck> =
        resolver.query(decksUri, arrayOf("deck_id", "deck_name", "deck_count"), null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val counts = parseCounts(cursor.getString(2)) ?: continue
                    add(Deck(cursor.getLong(0), cursor.getString(1), counts[0], counts[1], counts[2]))
                }
            }
        } ?: throw SourceUnavailable("AnkiDroid isn't answering (is it installed?)")

    /** Notes in [deckName] with a card never studied, by Anki's own search; the provider answers null for none. */
    fun unseenNotes(resolver: ContentResolver, deckName: String): Int {
        val query = "deck:\"${deckName.replace("\"", "\\\"")}\" is:new"
        return resolver.query(notesUri, arrayOf("_id"), query, null, null)?.use { it.count } ?: 0
    }

    /** Makes [deckId] the deck AnkiDroid opens next. */
    fun selectDeck(resolver: ContentResolver, deckId: Long): Boolean =
        runCatching { resolver.update(selectedDeckUri, ContentValues().apply { put("deck_id", deckId) }, null, null) > 0 }
            .getOrDefault(false)

    /** `deck_count` is a JSON array, `[learn, review, new]`; null unless it's exactly three whole numbers. */
    fun parseCounts(json: String?): IntArray? {
        val body = json?.trim()?.takeIf { it.startsWith("[") && it.endsWith("]") } ?: return null
        val numbers = body.substring(1, body.length - 1).split(",").map { it.trim().toIntOrNull() ?: return null }
        return numbers.takeIf { it.size == 3 }?.toIntArray()
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    enum class Opened { Studying, DeckList, NotInstalled }

    /**
     * Opens AnkiDroid with [deckId] selected: studying it, with [study] (its reviewer is exported
     * and opens on the selected deck, tried on the phone on 9 Oct), or on the deck list, where the
     * selected deck is highlighted. The reviewer is started only if the deck was selected, or it
     * would open on whichever deck was before; otherwise the deck list.
     */
    fun open(context: Context, deckId: Long?, study: Boolean): Opened {
        val selected = deckId != null && selectDeck(context.contentResolver, deckId)
        if (selected && study) {
            val reviewer = Intent().setClassName(PACKAGE, "$PACKAGE.Reviewer").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(reviewer) }.isSuccess) return Opened.Studying
        }
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return Opened.NotInstalled
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return Opened.DeckList
    }
}

class AnkiSource(private val context: Context) : TaskSource {
    override val source = Source.Anki

    override suspend fun read(context: ReadContext): SourceRead = withContext(Dispatchers.IO) {
        if (!AnkiProvider.hasPermission(this@AnkiSource.context)) {
            throw SourceUnavailable("AnkiDroid's permission isn't granted")
        }
        val resolver = this@AnkiSource.context.contentResolver
        val decks = AnkiProvider.decks(resolver)
        val unseen = HashMap<Long, Int>()
        val count = { deck: Deck -> unseen.getOrPut(deck.id) { AnkiProvider.unseenNotes(resolver, deck.name) } }
        val textbook = context.settings.ankiTextbook
        val homework = AnkiRules.homeworkDecks(decks, textbook, context.known, count, context.now, context.zone)
        val homeworkDecks = homework.filter { !it.done }.mapNotNull { it.extra[AnkiRules.EXTRA_DECK_ID]?.toLongOrNull() }.toSet()
        val (quota, day) = AnkiRules.quota(decks, textbook, context.ankiDay, context.now, context.zone, context.settings.ankiDeadlineMin, homeworkDecks, count)
        SourceRead(items = listOfNotNull(quota) + homework, ankiDay = day)
    }
}
