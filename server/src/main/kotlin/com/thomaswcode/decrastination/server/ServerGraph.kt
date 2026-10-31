package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.Profile
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.sync.Protocol
import com.thomaswcode.decrastination.sync.SeedAnswer
import com.thomaswcode.decrastination.sync.SeedBatch
import com.thomaswcode.decrastination.sync.ServerVersion
import com.thomaswcode.decrastination.views.DayView
import com.thomaswcode.decrastination.views.DayViews
import com.thomaswcode.decrastination.views.WorkView
import com.thomaswcode.decrastination.views.WorkViews
import java.io.File
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The server's clock: its own time, in the zone the phone last said, as a day is the owner's wherever they are. */
class ServerClock(private val db: Database) : WallClock {
    @Volatile
    private var zone: ZoneId = db.meta(ZONE)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("Europe/London")

    override fun now(): Long = System.currentTimeMillis()

    override fun zone(): ZoneId = zone

    /** The phone's zone, as it says it. A zone that isn't one is left. */
    fun setZone(id: String) {
        val told = runCatching { ZoneId.of(id) }.getOrNull() ?: return
        if (told != zone) {
            zone = told
            db.setMeta(ZONE, id)
        }
    }

    private companion object {
        const val ZONE = "zone"
    }
}

/**
 * The server's state and what it computes (docs/plan_web/architecture.md §The server), made here as `AppGraph` makes the
 * phone's. The same stores, on rows; the same plan, from them. In mirror mode (stage 1) the phone is the only writer: a
 * whole store comes in, and nothing here changes one.
 */
class ServerGraph(val db: Database, dataDir: File, cipher: SecretCipher, private val version: String) {
    val clock = ServerClock(db)
    val backend = SqliteBackend(db).apply { writable = (db.meta("mode") ?: MIRROR) != MIRROR }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val stores = Stores(backend, SecretStore(File(dataDir, "secrets.bin"), cipher))
    val calendarRead = JsonStore(backend, CalendarRead.NAME, CalendarRead.serializer(), ::CalendarRead)
    val devices = Devices(db)

    private val gateway = ModelGateway(stores.settings, { null }, stores.model, stores.tasks, clock, NoAlerts, scope) {}

    val planning = Planning(
        stores.tasks, stores.settings, stores.session, stores.learning, stores.model, stores.log, stores.instructions, stores.school, clock, scope, gateway,
        calendarTime = { calendarTime() },
        calendarChanges = emptyFlow<Unit>(),
        sweep = {},
        hooks = emptyList(),
        profile = { Profile.Block.NONE },
        draftAhead = { _, _ -> },
        onFailure = {},
        notes = { task: TaskItem -> Notes.forTask(task, stores.felix.value.notes, stores.settings.value.classMap).map { it.text } },
    )

    private val _revisions = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    /** Each revision as it's committed, for the website's event stream. */
    val revisions: SharedFlow<Long> = _revisions.asSharedFlow()

    /** Held while a batch is written and the stores read again: one at a time. */
    private val writing = Mutex()

    /** The stores by name, for the seeds. */
    private val byName: Map<String, JsonStore<*>> = stores.mirrored.associateBy { it.name } + (CalendarRead.NAME to calendarRead)

    /** The newest version each store can be: a store from a newer build is refused, as this one can't read it. */
    private val versions: Map<String, Int> = byName.mapValues { it.value.version } + (stores.chatArchive.name to stores.chatArchive.version)

    /** The months of the archive read once they're asked for. */
    private val archiveReads = ConcurrentHashMap<String, Long>()

    init {
        // A store's every write is kept for a while: at each start, and daily while it runs.
        scope.launch {
            while (true) {
                try {
                    tidy()
                } catch (failed: SQLException) {
                    Log.w(Log.TAG, "The history couldn't be tidied: ${failed::class.simpleName}")
                }
                delay(TIDY_EVERY_MS)
            }
        }
    }

    /** Whether the server is taking the phone's stores whole, or commands (from stage 2). */
    val mode: String get() = db.meta("mode") ?: MIRROR

    fun calendarTime(): EventJudge.Time = calendarRead.value.time(stores.instructions.value.applied, clock.now(), clock.zone())

    fun version() = ServerVersion(version, Protocol.VERSION, mode, versions)

    /**
     * [batch], a phone's stores as they are, kept whole: all of them, each with its history, as one revision. One of a
     * version newer than this build reads isn't kept, and is said. The server's copies are read again, for what it computes.
     */
    suspend fun seed(batch: SeedBatch, device: String): SeedAnswer = writing.withLock {
        clock.setZone(batch.zone)
        val refused = mutableListOf<String>()
        val written = mutableListOf<String>()
        backend.cause = "seed:$device"
        db.transaction {
            for (store in batch.stores) {
                val known = if (Archives.isMonth(store.name)) stores.chatArchive.version else versions[store.name]
                if (known == null || store.version > known) {
                    refused += store.name
                    continue
                }
                if (backend.read(store.name) == store.text) continue
                backend.keep(store.name, store.version, store.text)
                written += store.name
            }
        }
        written.forEach { byName[it]?.reload() }
        if (written.any(Archives::isMonth)) stores.chatArchive.forget()
        val revision = db.currentRevision()
        if (written.isNotEmpty()) _revisions.tryEmit(revision)
        SeedAnswer(revision, refused, version, backend.hashes())
    }

    /** Every store above revision [since], whole, with the revision they're as of: both read together, so a cursor can't pass a commit it hasn't seen. */
    fun state(since: Long): StateAnswer = db.transactionless { c ->
        val texts = c.prepareStatement("SELECT name, version, revision, json FROM stores WHERE revision > ? ORDER BY name").use { q ->
            q.setLong(1, since)
            q.executeQuery().use { r ->
                buildList { while (r.next()) add(StoreRow(r.getString(1), r.getInt(2), r.getLong(3), r.getString(4))) }
            }
        }
        StateAnswer(db.currentRevision(), texts)
    }

    /** The history let go of as its rule says: older than [SqliteBackend.HISTORY_MS], but each store's last few. */
    fun tidy() = backend.trimHistory(clock.now() - SqliteBackend.HISTORY_MS)

    /** [date] as the Day shows it, today's if none's given. */
    fun day(date: LocalDate?): DayView {
        val now = clock.now()
        val zone = clock.zone()
        val plan = planning.plan()
        val day = date ?: Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val settings = stores.settings.value
        val tasks = stores.tasks.value
        val applied = stores.instructions.value.applied
        val answers = calendarRead.value.answers + InstructionRules.eventAnswers(applied)
        return DayViews.of(
            plan, day, calendarRead.value.events, stores.log.value.slots,
            SchoolDays.windows(day, settings, tasks.timetable, stores.school.value), now, zone,
            busy = InstructionRules.busyBlocks(applied, day, day),
            judge = { EventJudge.judge(it, answers) },
            micro = stores.model.value.microPlans,
            asOf = devices.lastSeen(),
        )
    }

    fun work(): WorkView = WorkViews.of(stores.tasks.value, planning.plan(), stores.settings.value, clock.now(), clock.zone(), devices.lastSeen())

    private companion object {
        const val MIRROR = "mirror"
        const val TIDY_EVERY_MS = 24 * 3_600_000L
    }

    private object NoAlerts : ModelGateway.Alerts {
        override fun keyProblem(problem: KeyProblem): Boolean = false

        override fun keyFixed() = Unit

        override fun dropped(task: TaskItem, why: String): Boolean = false

        override fun withdrawExcept(standing: Set<String>) = Unit
    }
}

/** A month's store of the chat's archive, by its name. */
object Archives {
    private val MONTH = Regex("""chat-archive-\+?\d{4,}-\d{2}\.json""")

    fun isMonth(name: String): Boolean = MONTH.matches(name)
}

/** A store's row. */
data class StoreRow(val name: String, val version: Int, val revision: Long, val text: String)

/** The stores above a revision, and the newest revision there is. */
data class StateAnswer(val revision: Long, val stores: List<StoreRow>)
