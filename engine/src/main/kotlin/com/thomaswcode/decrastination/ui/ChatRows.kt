package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Speaker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The chat's list as it's drawn (docs/spec/felix.md §The chat), the same on the phone and on the website: its rows, the words
 * over them, and how long each of its moments lasts. A screen holds where you are in it (`ChatScreenState` on the phone).
 */
object ChatRows {
    /** One row of the list: a day's date, the divider over the first unread, a message, or the row that loads the archive's next page. */
    sealed interface Item {
        val key: String

        /** A day's date over its [first] message: keyed by that, as a message out of time order can bring a date back. */
        data class Day(val date: LocalDate, val first: String) : Item {
            override val key: String get() = "day-$first"
        }

        data object Divider : Item {
            override val key: String get() = "unread"
        }

        /**
         * A [message], with the message it replies to where that isn't the one above it ([quote]), shown over
         * its words; one from the archive ([archived]) shown with nothing to do, as only the window is acted on.
         */
        data class Message(val message: ChatMessage, val quote: ChatMessage? = null, val archived: Boolean = false) : Item {
            override val key: String get() = message.id
        }

        data object Earlier : Item {
            override val key: String get() = "earlier"
        }
    }

    /**
     * The list as it's drawn: the [archive]'s [earlier] newest messages, then the [window]'s, each day's
     * date over its first, the divider over [firstUnread], and over a reply what it replies to where
     * that isn't the message above it (from the [quotes] read of the archive, past its pages); the row that
     * loads the next page at the top while the archive has more, or holds some not read yet ([unread]). His
     * silences (nothing to say) aren't drawn.
     */
    fun items(window: List<ChatMessage>, archive: List<ChatMessage>, earlier: Int, firstUnread: String?, zone: ZoneId, unread: Boolean = false, quotes: List<ChatMessage> = emptyList()): List<Item> = buildList {
        if (earlier < archive.size || unread) add(Item.Earlier)
        val byId = (quotes + archive + window).associateBy { it.id }
        val live = window.mapTo(HashSet()) { it.id }
        var day: LocalDate? = null
        var above: ChatMessage? = null
        for (m in (archive.takeLast(earlier) + window).distinctBy { it.id }) {
            if (m.from == Speaker.Felix && m.text.isBlank()) continue
            val on = Instant.ofEpochMilli(m.at).atZone(zone).toLocalDate()
            if (on != day) {
                add(Item.Day(on, m.id))
                day = on
            }
            if (m.id == firstUnread) add(Item.Divider)
            add(Item.Message(m, m.replyTo?.takeIf { it != above?.id }?.let(byId::get)?.takeIf { it.text.isNotBlank() }, archived = m.id !in live))
            above = m
        }
    }

    /** The archive read back a page at a time. */
    const val PAGE = 100

    /** How long the chat is on screen before a visit's seen (Q120): long enough to pass through to Day, short enough that a real look counts. */
    const val READ_AFTER_MS = 3_000L

    /** How long Mark answered's Undo stays at the foot (D-99): six seconds. */
    const val UNDO_BAR_MS = 6_000L

    /** How long a call runs before you can stop waiting for it (AC-08). */
    const val CANCEL_AFTER_S = 10L

    /** The examples the empty chat shows. */
    val EXAMPLES = listOf(
        "I need to write my UCAS personal statement by 1 December, about 8 hours",
        "The physics prep will take 90 minutes",
        "Move the German vocab to Thursday",
        "I'm out on Saturday evening from 7",
        "Remind me at 7 to call gran",
        "Note on the essay: use the second source",
        "I'm done with the trip email",
    )

    /** What an empty chat says of [name]. */
    fun intro(name: String): String = "Tell $name about your work: a task to add, a deadline or how long something takes, a day you can't work, a step done. Whatever he does is made at once, and its Undo takes it back."

    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
    private val DAY_YEAR = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

    /** [date] as the line between days says it, [today] being today: "Today", "Yesterday", "Tuesday 13 October" (with its year where it isn't this one). */
    fun dayLine(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(if (date.year == today.year) DAY else DAY_YEAR)
    }
}

private val ABOUT_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

/** What an instruction is about, in words. */
fun aboutLine(about: About, now: Long, zone: ZoneId): String = when {
    about.taskId != null -> "About the task “${about.taskTitle ?: about.taskId}”"
    about.eventKey != null -> "About “${about.eventTitle ?: about.eventKey}”" + (about.eventStart?.let { ", " + Format.at(it, now, zone) } ?: "")
    about.day != null -> "About " + (runCatching { LocalDate.parse(about.day).format(ABOUT_DAY) }.getOrNull() ?: about.day)
    else -> "General"
}

/** A card's task in a line: its kind and subject, when it's due (or starts), how long it takes. */
fun taskLine(task: OwnTask, now: Long, zone: ZoneId): String = listOfNotNull(
    task.kind.label,
    task.className,
    // Your own task's date is yours, so a real deadline.
    task.dueAt?.let { if (task.kind == Kind.Event) Format.at(it, now, zone) else Format.due(it, hard = true, now, zone) } ?: "No due date",
    task.startFrom?.let { "not before " + Format.at(it, now, zone) },
    if (task.kind == Kind.Event) null else Format.minutes(if (task.steps.isEmpty()) task.minutes else task.steps.sumOf { it.minutes }),
).joinToString(" · ")

/** A step in a line, as a task's sheet and a card have it: its minutes, its dates, and that it doesn't need the steps before it (D-109). */
fun stepLine(title: String, minutes: Int, from: Long?, dueAt: Long?, anyOrder: Boolean, now: Long, zone: ZoneId): String {
    val dates = listOfNotNull(from?.let { "from " + Format.at(it, now, zone) }, dueAt?.let { Format.due(it, hard = false, now, zone) }).joinToString(", ")
    val details = listOfNotNull(Format.minutes(minutes), dates.ifEmpty { null }, "in any order".takeIf { anyOrder }).joinToString("; ")
    return "$title ($details)"
}
