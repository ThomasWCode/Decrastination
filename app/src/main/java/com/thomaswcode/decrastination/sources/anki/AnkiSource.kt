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
import java.io.IOException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    /**
     * Every deck with today's counts. One whose counts can't be read fails the read rather than
     * being left out: left out, its deck task would be dropped as missed (BUG-P2-007).
     */
    fun decks(resolver: ContentResolver): List<Deck> =
        resolver.query(decksUri, arrayOf("deck_id", "deck_name", "deck_count", "options"), null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    val counts = parseCounts(cursor.getString(2)) ?: throw IOException("AnkiDroid's counts for $name can't be read")
                    add(Deck(cursor.getLong(0), name, counts[0], counts[1], counts[2], newPerDay(cursor.getString(3))))
                }
            }
        } ?: throw SourceUnavailable("AnkiDroid isn't answering (is it installed?)")

    /**
     * Cards in [deckName] never studied, by Anki's own search. The provider searches notes, so
     * each side is counted apart (`card:1`, the first template; `card:2`, a reversed card's), and a
     * note learnt both ways counts twice, as Anki shows it twice. The provider answers null for none.
     */
    fun unseenCards(resolver: ContentResolver, deckName: String): Int {
        val deck = "deck:\"${deckName.replace("\"", "\\\"")}\""
        return (1..MAX_TEMPLATES).sumOf { side -> resolver.query(notesUri, arrayOf("_id"), "$deck is:new card:$side", null, null)?.use { it.count } ?: 0 }
    }

    /** The most cards a note in a vocabulary deck makes: one a side, and a spare. */
    private const val MAX_TEMPLATES = 4

    /**
     * The new cards a day a deck's options allow (AnkiDroid's "New cards/day", `new.perDay` in the
     * options it gives): 0 is a real limit, none; the default only where they can't be read.
     */
    fun newPerDay(options: String?): Int = runCatching {
        Json.parseToJsonElement(options!!).jsonObject["new"]!!.jsonObject["perDay"]!!.jsonPrimitive.int
    }.getOrNull()?.takeIf { it in 0..MAX_PER_DAY } ?: AnkiRules.NEW_PER_DAY

    private const val MAX_PER_DAY = 9_999

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
        val count = { deck: Deck -> unseen.getOrPut(deck.id) { AnkiProvider.unseenCards(resolver, deck.name) } }
        val textbook = context.settings.ankiTextbook
        val homework = AnkiRules.homeworkDecks(decks, textbook, context.known, count, context.now, context.zone)
        val homeworkDecks = homework.filter { !it.done }.mapNotNull { it.extra[AnkiRules.EXTRA_DECK_ID]?.toLongOrNull() }.toSet()
        val reviewsOnly = AnkiRules.homeworkDueSoon(homework, context.now, context.zone)
        val (quota, day) = AnkiRules.quota(decks, textbook, context.ankiDay, context.now, context.zone, context.settings.ankiDeadlineMin, homeworkDecks, reviewsOnly, count)
        SourceRead(items = listOfNotNull(quota) + homework, ankiDay = day)
    }
}
