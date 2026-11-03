package com.thomaswcode.decrastination.sync

import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.CalendarState
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.felix.FelixWords
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.ports.Log
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the reading of the sources and the calendar asks of Android (docs/plan_v4/architecture.md
 * §Ports), so it's tested without it: a sync as a job, the Teams widget asked and watched, the calendar
 * read, the widget drawn. `AndroidSyncPorts` on the phone.
 */
interface SyncPorts {
    /** Every source read now, or only [sources], as a job that keeps its network after the screen that asked has gone (`SyncWorker.syncNow`). */
    fun syncNow(sources: Set<Source>? = null)

    /** The Teams widget asked to sync Teams itself: whether it started and why not, or null if it didn't answer. */
    fun requestTeamsSync(): TeamsAnswer?

    /** [onChange] at each change the Teams widget announces to its list. */
    fun watchTeams(onChange: () -> Unit)

    /** Whether the calendar may be read. */
    fun calendarAllowed(): Boolean

    /** The calendar's events between [from] and [to], but those you've declined and those of [excluded] calendars. */
    fun readCalendar(from: Long, to: Long, excluded: Set<Long>): List<CalendarEvent>

    /** The widget drawn again. */
    suspend fun redraw()
}

/** The Teams widget's answer to being asked to sync: [started], or why not ([reason]: "service_off", "busy", or another). */
data class TeamsAnswer(val started: Boolean, val reason: String?)

/**
 * What the app reads (docs/spec/sources.md): the sources, through the syncer ([sync],
 * [refreshAll]); the Teams widget asked to
 * sync ([requestTeamsSync]) and watched ([start]); and the phone's calendar as busy time
 * ([refreshCalendar], [calendarTime]), what it can't judge asked about in Felix's name. Built like
 * [com.thomaswcode.decrastination.block.Focus]: its stores, the clock, the syncer, [SyncPorts] for
 * what Android does, and small callbacks.
 */
@OptIn(FlowPreview::class)
class Sync(
    private val settings: JsonStore<Settings>,
    private val calendar: JsonStore<CalendarState>,
    /** Your instructions, read for what they say about events. */
    private val instructions: JsonStore<InstructionState>,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    private val syncer: Syncer,
    private val ports: SyncPorts,
    /** The plan kept for the minute let go, the calendar it was made from changed ([com.thomaswcode.decrastination.block.Focus.forgetPlan]). */
    private val forgetPlan: () -> Unit,
    /** The calendar watched for changes, once it may be read ([com.thomaswcode.decrastination.calendar.CalendarWriter.watchCalendar]). */
    private val watchCalendar: () -> Unit,
    /**
     * An event the rules can't judge asked about in Felix's name ([com.thomaswcode.decrastination.felix.Conversation.askAboutEvent]),
     * and put on record as asked once its question is ([eventAsked]).
     */
    private val askAboutEvent: suspend (key: String, title: String, day: String, start: Long, answers: List<String>) -> Unit,
    /** His questions about the events named a key settled ([com.thomaswcode.decrastination.felix.Conversation.settleEvent]). */
    private val settleEvent: suspend (String) -> Unit,
    /** The plan made again ([com.thomaswcode.decrastination.plan.PlanWatcher.poke]). */
    private val replan: () -> Unit,
    /** A source's note where its status shows it ([Syncer.note]), or, once the two keep in step by commands, sent as one. */
    private val noteSource: suspend (Source, String) -> Unit = { source, note -> syncer.note(source, note) },
) {
    /** Every source read now, or only [only], one sync at a time ([Syncer.sync]). */
    suspend fun sync(only: Set<Source>? = null): SyncReport = syncer.sync(only)

    /** [sources] read now, or every one, as a job that keeps its network after what asked has gone (Felix's `sync`, G2). */
    fun syncNow(sources: Set<Source>? = null) = ports.syncNow(sources)

    /**
     * The calendar's busy time and per-day loads, as last read and judged: the calendar as the
     * planner counts it, from its last read. A plan made before that read is dropped with it.
     */
    @Volatile
    var calendarTime: EventJudge.Time = EventJudge.Time(emptyList(), emptyMap(), emptyList())
        private set(value) {
            field = value
            forgetPlan()
            calendarState.value = value
        }

    /** [calendarTime] as it changes, for a screen open while the calendar is read again. */
    val calendarState = MutableStateFlow(calendarTime)

    /**
     * Reads every source now, as a job (it keeps its network after the screen that asked has
     * gone). With [teams], it first asks the Teams widget to sync Teams itself, which takes over
     * the screen for a minute, so only on a tap (Q21); what that finds comes back through the
     * widget's change notifications.
     */
    fun refreshAll(teams: Boolean) {
        if (teams) {
            scope.launch {
                // Refused (its service off, already syncing): say so where the widget shows it.
                // The next read of Teams replaces the note with the widget's own state.
                requestTeamsSync()?.let { reason -> noteSource(Source.Teams, reason) }
            }
        }
        ports.syncNow()
    }

    /** Asks the Teams widget to sync Teams. Returns why it didn't start, or null if it did. */
    suspend fun requestTeamsSync(): String? = withContext(Dispatchers.IO) {
        val result = runCatching { ports.requestTeamsSync() }.getOrNull()
        when {
            result?.started == true -> null
            result == null -> "The Teams widget didn't answer"
            else -> when (result.reason) {
                "service_off" -> "The Teams widget's sync service is off"
                "busy" -> TEAMS_BUSY
                else -> "The Teams widget didn't start a sync"
            }
        }
    }

    /**
     * The Teams widget announces every change to its list: a sync, or a hand-in it saw while
     * Teams was open. Reading it needs no network, so it's read here, once the changes stop, and
     * Anki with it: its homework decks come from Teams' assignments (Anki is read after Teams).
     */
    private val teamsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The calendar as last read, for the server, which can't read it: your answers about its events with it. */
    fun calendarRead(): CalendarRead = calendarTime.let { CalendarRead(it.events, calendar.value.eventAnswers, it.read?.first ?: 0, it.read?.second ?: 0) }

    /** Held while the calendar's read and judged: refreshes at once (a start, a sync, the calendar changing) can't ask an event twice. */
    private val refreshing = Mutex()

    /** The events being asked about, by key, from their read till they're on record as asked ([eventAsked]): under [refreshing]. */
    private val asking = HashSet<String>()

    /**
     * Reads the calendar, judges it for the planner, and asks about what it can't judge. What's said in Felix's name of it is said
     * once the calendar's let go, as it goes as a command once the phone sends them, and a command being applied can be waiting to
     * read the calendar again meanwhile (a source read's after-work does): asked for under the lock, it would wait on that command.
     * An event's on record as asked once its question is ([eventAsked]), so one not asked, stopped first, is asked the next time.
     */
    suspend fun refreshCalendar() {
        val (settled, toAsk) = refreshing.withLock { reading() }
        var asked = 0
        try {
            settled.forEach { settleEvent(it) }
            toAsk.forEach { event ->
                ask(event, clock.zone())
                asked++
            }
        } finally {
            // Those not asked, failed or stopped first, are asked the next time; those asked stay marked till they're on record.
            val left = toAsk.drop(asked).map(EventJudge::key)
            if (left.isNotEmpty()) withContext(NonCancellable) { refreshing.withLock { asking -= left.toSet() } }
        }
    }

    /**
     * [key], an event asked about, on record as asked once its question is: by its asking, at once while mirroring, and once the
     * phone sends commands as its command's applied here ([com.thomaswcode.decrastination.sync.protocol.AskAboutEvent]), which,
     * made inside another's, is once that's closed: so a stop before leaves it to be asked again, never marked and not asked.
     */
    suspend fun eventAsked(key: String) = refreshing.withLock {
        calendar.update { it.copy(eventsAsked = it.eventsAsked + key) }
        asking -= key
    }

    /** The calendar read and judged: what of your instructions settles his questions, and what's to be asked, then being asked. */
    private suspend fun reading(): Pair<Collection<String>, List<CalendarEvent>> {
        if (!ports.calendarAllowed()) {
            calendarTime = EventJudge.Time(emptyList(), emptyMap(), emptyList())
            return NOTHING_SAID
        }
        watchCalendar()
        val now = clock.now()
        // Your instructions about events over your answers to its questions; a question one of them
        // has answered since it was asked is withdrawn.
        val yours = InstructionRules.eventAnswers(instructions.value.applied)
        val picked = settings.value
        val excluded = (picked.calendarsExcluded + listOfNotNull(picked.calendarWriteId)).toSet()
        val events = runCatching { withContext(Dispatchers.IO) { ports.readCalendar(now - CalendarTime.DAY_MS, CalendarTime.readUntil(now, clock.zone()), excluded) } }
            .onFailure { Log.w(Log.TAG, "Can't read the calendar", it) }
            .getOrNull() ?: run {
            // Not read this time: the events last read, judged again with the answers as they are
            // now, so an instruction applied or taken back counts all the same.
            calendarTime = EventJudge.time(calendarTime.events, calendar.value.eventAnswers + yours, clock.zone(), askUntil = now + CalendarTime.ASK_AHEAD_MS).copy(read = calendarTime.read)
            return NOTHING_SAID
        }
        // Not counted as asked: taken back, the instruction leaves it to be asked again.
        if (calendar.value.eventsAsked.any { it in yours }) calendar.update { it.copy(eventsAsked = it.eventsAsked - yours.keys) }
        val answers = calendar.value.eventAnswers + yours
        val time = EventJudge.time(events, answers, clock.zone(), askUntil = now + CalendarTime.ASK_AHEAD_MS).copy(read = (now - CalendarTime.DAY_MS) to CalendarTime.readUntil(now, clock.zone()))
        calendarTime = time
        // In the chat at once, whatever the hour: his notification leaves them out in quiet hours, till
        // the morning (NotifyRules.waitsForMorning). Those being asked already aren't asked again.
        val toAsk = CalendarTime.questions(time.toAsk, calendar.value.eventsAsked + asking, now)
        asking += toAsk.map(EventJudge::key)
        runCatching { ports.redraw() }
        return yours.keys to toAsk
    }

    /** [event] asked about as Felix's message, one a name: its three answers his chips, one of them kept for every event of its name. */
    private suspend fun ask(event: CalendarEvent, zone: ZoneId) =
        askAboutEvent(EventJudge.key(event), event.title, CalendarTime.questionDay(event, zone), event.start, FelixWords.eventReplies(CalendarTime.ANSWERS.map { it.first }))

    /**
     * What's read again as it changes, from the app's start: the calendar (its [calendarChanges]), the
     * calendars picked, Anki's settings, and the Teams widget's list.
     */
    fun start(calendarChanges: Flow<Unit>) {
        // A change in the calendar: read again, and the plan made again, so a slot you've moved or
        // deleted in the calendar written to (which isn't read) is seen (NF-07). The app's own write
        // brings one replan more, which writes nothing.
        scope.launch {
            calendarChanges.debounce(CALENDAR_QUIET_MS).collect {
                refreshCalendar()
                replan()
            }
        }
        // The calendar written to, or those not read, changed (NF-07, DX-13): the calendar's read again.
        scope.launch {
            settings.state.map { it.calendarWriteId to it.calendarsExcluded }.distinctUntilChanged().drop(1).collect { refreshCalendar() }
        }
        // Anki's deadline or textbook changed (saved, or a waiting change fallen due): its tasks are
        // made with them, so it's read again now.
        scope.launch {
            settings.state.map { it.ankiDeadlineMin to it.ankiTextbook }.distinctUntilChanged().drop(1).collect { ports.syncNow(setOf(Source.Anki)) }
        }
        runCatching { ports.watchTeams { teamsChanged.tryEmit(Unit) } }.onFailure { Log.w(Log.TAG, "Can't watch the Teams widget", it) }
        scope.launch {
            teamsChanged.debounce(TEAMS_QUIET_MS).collect { syncer.sync(setOf(Source.Teams, Source.Anki)) }
        }
    }

    companion object {
        /** [requestTeamsSync]'s answer when the widget is syncing already: as good as one started. */
        const val TEAMS_BUSY = "The Teams widget is already syncing"

        /** A read with nothing to say of it in his name. */
        private val NOTHING_SAID: Pair<Collection<String>, List<CalendarEvent>> = emptyList<String>() to emptyList()

        /** The widget sends a change for each step of a sync; read once they've stopped. */
        private const val TEAMS_QUIET_MS = 5_000L

        private const val CALENDAR_QUIET_MS = 5_000L
    }
}
