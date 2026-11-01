package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.ReplayClock
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.BackupService
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.net.Http
import com.thomaswcode.decrastination.net.UrlConnectionHttp
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.sync.Protocol
import com.thomaswcode.decrastination.sync.SeedAnswer
import com.thomaswcode.decrastination.sync.SeedBatch
import com.thomaswcode.decrastination.sync.ServerVersion
import com.thomaswcode.decrastination.sync.protocol.Commands
import com.thomaswcode.decrastination.views.DayView
import com.thomaswcode.decrastination.views.DayViews
import com.thomaswcode.decrastination.views.WorkView
import com.thomaswcode.decrastination.views.WorkViews
import java.io.File
import java.io.IOException
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

    /**
     * What a loosening's wait is counted by (W-23). The phone counts by its uptime, as its date is the owner's to set; this clock
     * isn't, so its time is its uptime, one count across a restart, and a mark of the phone's is another clock's.
     */
    override fun uptime() = Uptime(BOOT, now())

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

        /** No phone's count of its starts, nor the one for a count unread ([Uptime.UNKNOWN_BOOT]): a mark of the phone's is never taken for this clock's. */
        const val BOOT = -2
    }
}

/**
 * The server's state and what it computes (docs/plan_web/architecture.md §The server), made here as `AppGraph` makes the
 * phone's. The same stores, on rows; the same plan, from them. In mirror mode (stage 1) the phone is the only writer: a
 * whole store comes in, and nothing here changes one.
 */
class ServerGraph(
    val db: Database,
    dataDir: File,
    cipher: SecretCipher,
    private val version: String,
    /** Claude's key, from the box's `.env` (W-12): none, and the rules do what they can. */
    private val claudeKey: String? = null,
    /** Where a minute's heartbeat is sent (healthchecks.io), if anywhere. */
    private val heartbeatUrl: String? = null,
    private val http: Http = UrlConnectionHttp(),
    /** Where the nightly file goes (W-20), if it's set up. */
    nightly: BackupService.Folder? = null,
) {
    /** The clock the phone's zone is told to. */
    val serverClock = ServerClock(db)

    /** What the owners read: a command's own moment while one's applied. */
    val clock: WallClock = ReplayClock(serverClock)
    val backend = SqliteBackend(db).apply { writable = (db.meta("mode") ?: MIRROR) != MIRROR }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The credentials, sealed under `MASTER_KEY` (W-19), in the database as well as beside it, so a restore carries them: a
     * file on a rebuilt box comes back from there before it's read.
     */
    private val secrets: SecretStore = File(dataDir, SECRETS).let { file ->
        if (!file.exists()) db.credentials(SECRETS)?.let(file::writeBytes)
        SecretStore(file, cipher, saved = { db.setCredentials(SECRETS, it, serverClock.now()) })
    }

    val stores = Stores(backend, secrets)
    val calendarRead = JsonStore(backend, CalendarRead.NAME, CalendarRead.serializer(), ::CalendarRead)
    val devices = Devices(db)

    private val gateway = ModelGateway(stores.settings, { claudeKey }, stores.model, stores.tasks, clock, NoAlerts, scope) {}

    val planning: Planning = Planning(
        stores.tasks, stores.settings, stores.session, stores.learning, stores.model, stores.log, stores.instructions, stores.school, clock, scope, gateway,
        calendarTime = { calendarTime() },
        calendarChanges = emptyFlow<Unit>(),
        sweep = { owners.slots.sweep() },
        // As the phone's, less what's its own: its alarms, its calendar, its widget.
        hooks = listOf(
            { _ -> owners.focus.forgetPlan() },
            { _ -> owners.focus.settleTimer() },
        ),
        profile = { owners.conversation.profile(it) },
        draftAhead = { made, now -> owners.looks.draftAhead(made, now) },
        onFailure = { Log.w(Log.TAG, "Replanning failed: ${it::class.simpleName}") },
        notes = { task: TaskItem -> Notes.forTask(task, stores.felix.value.notes, stores.settings.value.classMap).map { it.text } },
    )

    init {
        backend.written = { _revisions.tryEmit(it) }
    }

    /** The owners: what they do is what a command asks, a job, and what [start] has watch the stores. */
    val owners: ServerOwners = ServerOwners(stores, clock, scope, gateway, planning, calendarTime = { calendarTime() })

    private val commands = Commands(owners, clock)

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
        serverClock.setZone(batch.zone)
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

    /** Commands applied as they arrive, one at a time. */
    val applier = CommandApplier(db, backend, commands, owners, clock, reload = { reloadAll() }, committed = { _revisions.tryEmit(it) })

    /** The Backup-format file, as the phone writes it, so the phone's Import can read it. */
    private val backups: BackupService? = nightly?.let { folder ->
        BackupService(
            stores, clock, scope, folder, File(dataDir, "reset-copies"), version,
            changeSettings = { change -> owners.protection.changeSettings(change) },
            applying = owners.instructions.applying,
            layInstructions = { owners.instructions.layInstructions() },
            layTaskOverrides = { owners.instructions.layTaskOverrides() },
            schedule = {},
            reread = {},
            cancelFelix = {},
            eventAnswers = { calendarRead.value.answers },
        )
    }

    /** Each minute, what's due, once the server applies commands (docs/spec/server.md §Jobs). */
    val jobs = ServerJobs(
        db, clock, running = { mode == COMMANDS },
        listOf(
            ServerJob.every("slots", MINUTE_MS) {
                owners.slots.sweep()
                owners.protection.applyDueChanges()
            },
            ServerJob.every("looks", MINUTE_MS) {
                owners.looks.check()
                owners.looks.sweepReminders()
            },
            ServerJob.every("sources", SOURCES_EVERY_MS) { owners.syncer.sync(setOf(Source.PowerPlanner, Source.Gmail)) },
            ServerJob("morning", due = { last, now -> morningDue(last, now) }) {
                owners.conversation.forgetExpired()
                owners.ownTasks.rollWeeks()
                owners.briefing.run()
                owners.conversation.pruneFelix()
            },
            ServerJob("check-in", due = { last, now -> sundayDue(last, now, stores.settings.value.checkInMin) }) { owners.conversation.remindCheckIn() },
            ServerJob("review", due = { last, now -> sundayDue(last, now, stores.settings.value.checkInMin + Daily.REVIEW_AFTER_MIN) }) { owners.learning.review(ifDue = true) },
            ServerJob("nightly", due = { last, now -> nightDue(last, now) }, retryMs = HOUR_MS) { nightly() },
            ServerJob.every("heartbeat", MINUTE_MS) { heartbeatUrl?.let { http.get(it, emptyMap()) } },
        ),
    )

    /** Tonight's file, or a failure, so it's tried again rather than the night going without. */
    internal suspend fun nightly() {
        val written = backups?.backupNow() ?: return
        check(written == BackupService.Nightly.Written) { "The nightly file wasn't written: $written" }
    }

    /** What runs by itself: the jobs, and, once the server applies commands, what watches the stores and the plan made again. */
    fun start(scope: CoroutineScope) {
        if (mode == COMMANDS) {
            owners.start()
            planning.watcher.start()
        }
        jobs.start(scope)
    }

    /** Where a job at a set time that's never run counts from: today's start, as what it did before then is gone by, and the morning says today's. */
    private fun todayBegan(now: Long): Long = Instant.ofEpochMilli(now).atZone(clock.zone()).toLocalDate().atStartOfDay(clock.zone()).toInstant().toEpochMilli()

    /** Whether the morning's work is due: its next time since it last ran. */
    internal fun morningDue(last: Long?, now: Long): Boolean = Daily.nextBriefing(last ?: todayBegan(now), clock.zone(), stores.settings.value, stores.school.value) <= now

    /** Whether Sunday's work at [minute] is due: its next time since it last ran. */
    internal fun sundayDue(last: Long?, now: Long, minute: Int): Boolean = Daily.nextSunday(last ?: todayBegan(now), clock.zone(), minute) <= now

    /** Whether tonight's backup has gone: once a day, after [NIGHTLY_HOUR]. */
    private fun nightDue(last: Long?, now: Long): Boolean {
        val zone = clock.zone()
        val tonight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atTime(NIGHTLY_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        return now >= tonight && (last == null || last < tonight)
    }

    /** What waits for Claude asked for, after a command's landed: on its own time, as a call can take a minute. */
    fun answerSoon() {
        scope.launch {
            try {
                owners.answerWhatWaits()
            } catch (failed: IOException) {
                Log.w(Log.TAG, "Asking Claude failed: ${failed::class.simpleName}")
            }
        }
    }

    /** The credentials held, by name. Never their values. */
    fun credentials(): Set<String> = stores.secrets.present.value.map { it.name }.toSet()

    /** Credentials as the website's given them: those it may set, a blank one taken away. */
    suspend fun setCredentials(given: Map<String, String>) {
        val changed = CREDENTIALS.filter { it.name in given }.associateWith<Secret, String?> { given[it.name] }
        val login = changed.keys.any { it == Secret.PowerPlannerUsername || it == Secret.PowerPlannerPassword }
        stores.secrets.put(changed + if (login) Secret.POWER_PLANNER_SESSION.associateWith { null } else emptyMap())
    }

    /** Every store read again from what's committed. */
    private suspend fun reloadAll() {
        byName.values.forEach { it.reload() }
        stores.chatArchive.forget()
    }

    /** Every store above revision [since], whole, with the revision they're as of: both read together, so a cursor can't pass a commit it hasn't seen. */
    fun state(since: Long): StateAnswer = db.read { c ->
        val texts = c.prepareStatement("SELECT name, version, revision, json FROM stores WHERE revision > ? ORDER BY name").use { q ->
            q.setLong(1, since)
            q.executeQuery().use { r ->
                buildList { while (r.next()) add(StoreRow(r.getString(1), r.getInt(2), r.getLong(3), r.getString(4))) }
            }
        }
        val revision = c.prepareStatement("SELECT value FROM meta WHERE key = 'revision'").use { q -> q.executeQuery().use { r -> if (r.next()) r.getString(1).toLong() else 0L } }
        StateAnswer(revision, texts)
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

    companion object {
        /** The mode where commands apply: `meta.mode`, one way, from mirror mode. */
        const val COMMANDS = "commands"
        private const val SECRETS = "secrets.bin"
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
        private const val SOURCES_EVERY_MS = 15 * MINUTE_MS
        private const val NIGHTLY_HOUR = 3

        /** What the website can set: the sources' sign-ins, entered there (W-19). Claude's key is the box's, and the parent code's the phone's. */
        private val CREDENTIALS = setOf(Secret.PowerPlannerUsername, Secret.PowerPlannerPassword, Secret.GmailAddress, Secret.GmailAppPassword)
        private const val MIRROR = "mirror"
        private const val TIDY_EVERY_MS = 24 * 3_600_000L
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
