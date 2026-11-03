package com.thomaswcode.decrastination.sync.protocol

import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskPhoto
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.learn.AnkiReading
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.sync.SyncReport
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// What only the phone can know (W-24, docs/spec/sync.md §Observations): each a command of its own kind, applied on both sides
// through the same owner, so what the phone saw reaches the server, and a pull, which takes the server's stores whole, doesn't
// take it back. The phone makes them only once the two keep in step by commands; while mirroring, its stores carry them.

/** A source as this phone read it ([Source.SERVERS] aside): what it listed, why it couldn't be read, or neither, read the same as last sent. */
@Serializable
data class SourceObserved(val source: Source, val startedAt: Long, val read: SourceRead? = null, val failed: String? = null)

/** Teams or Anki as this phone read it, one command a source, merged as a read here is ([com.thomaswcode.decrastination.sync.Syncer.observe]). */
@Serializable
@SerialName("ObserveSources")
data class ObserveSources(val reads: List<SourceObserved>) : Action, Answers<SyncReport> {
    override suspend fun run(owners: Owners): SyncReport = owners.sources.observe(reads)
}

/** A source's note, where its status shows it: the Teams widget refusing to sync, say. */
@Serializable
@SerialName("NoteSource")
data class NoteSource(val source: Source, val note: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.sources.note(source, note)
}

/** The calendar as this phone last read it, with your answers about its events: the server can't read it, and plans by it. */
@Serializable
@SerialName("ObserveCalendar")
data class ObserveCalendar(val read: CalendarRead) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.calendarSeen(read)
}

/** An event the rules can't judge, asked about in Felix's name, as this phone's calendar and what it's asked decide. */
@Serializable
@SerialName("AskAboutEvent")
data class AskAboutEvent(val key: String, val title: String, val day: String, val start: Long, val answers: List<String>) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.askAboutEvent(key, title, day, start, answers)
}

/** His questions about the events named [key] settled, an instruction of yours having answered them. */
@Serializable
@SerialName("SettleEvent")
data class SettleEvent(val key: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.settleEvent(key)
}

/** AnkiDroid come to the front at [at], its decks as this phone read them then: none where they couldn't be. */
@Serializable
@SerialName("AnkiCame")
data class AnkiCame(val at: Long, val reading: AnkiReading? = null) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.ankiWatch.came(at, reading)
}

/** AnkiDroid gone from the front at [at], its decks as read then: the time in it work, as [com.thomaswcode.decrastination.block.AnkiWatch] counts it. */
@Serializable
@SerialName("AnkiWent")
data class AnkiWent(val at: Long, val reading: AnkiReading? = null) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.ankiWatch.went(at, reading)
}

/** The focus service started again: a stretch in AnkiDroid begun before it stopped let go, as when it ended isn't known. */
@Serializable
@SerialName("AnkiReset")
data object AnkiReset : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.ankiWatch.resetStretch()
}

/** A blocked app or site covered, as this phone's blocker saw it, for the record. */
@Serializable
@SerialName("RecordBlock")
data class RecordBlock(val target: String, val reason: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.focus.recordBlock(target, reason)
}

/**
 * [ms] of free time spent on [day], a blocked app run on what you'd earned: one a half-minute, merged with the one before it till
 * it's sent (W-24, [Merging]), so a pull, taken only with nothing waiting, can neither give it back nor take earned time.
 */
@Serializable
@SerialName("SpendFreeTime")
data class SpendFreeTime(val day: String, val ms: Long) : Action, Answers<Unit>, Merging {
    override suspend fun run(owners: Owners) = owners.focus.spend(ms, LocalDate.parse(day))

    override fun merged(after: Action): Action? = (after as? SpendFreeTime)?.takeIf { it.day == day }?.let { SpendFreeTime(day, ms + it.ms) }
}

/** The timer's zero reached, as this phone's alarm heard it: told once, so its "time's up" isn't posted again at each pull. */
@Serializable
@SerialName("TimerOver")
data object TimerOver : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean = owners.focus.timerOver()
}

/** Protection as this phone's watchdog found it, a lapse or its end, and whether it was put right, for the record. */
@Serializable
@SerialName("RecordProtection")
data class RecordProtection(val problems: List<String>, val repaired: Boolean) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.focus.recordProtection(problems, repaired)
}

/** A photo of [taskId]'s work kept with it (AI-23), as [photo] names it: the file's this phone's. */
@Serializable
@SerialName("AttachPhoto")
data class AttachPhoto(val taskId: String, val photo: TaskPhoto) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.photos.keep(taskId, photo)

    override fun stale(owners: Owners) = if (owners.tasks.value.tasks.none { it.id == taskId }) "That task had gone" else null
}

/** An action that can take in the one made after it while neither's been sent, as one ([merged]), or null where it can't. */
interface Merging {
    fun merged(after: Action): Action?
}
