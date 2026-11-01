package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.Ids
import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.ReplayClock
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.BackupNames
import com.thomaswcode.decrastination.data.BackupService
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.PhotoTasks
import com.thomaswcode.decrastination.felix.Outbox
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.learn.Export
import com.thomaswcode.decrastination.net.Http
import com.thomaswcode.decrastination.net.UrlConnectionHttp
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.sync.Protocol
import com.thomaswcode.decrastination.sync.SeedAnswer
import com.thomaswcode.decrastination.sync.SeedBatch
import com.thomaswcode.decrastination.sync.ServerVersion
import com.thomaswcode.decrastination.sync.protocol.Action
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.Commands
import com.thomaswcode.decrastination.sync.protocol.EndCheckInFlow
import com.thomaswcode.decrastination.sync.protocol.Outcome
import com.thomaswcode.decrastination.sync.protocol.RecordCheckIn
import com.thomaswcode.decrastination.ui.ChatRows
import com.thomaswcode.decrastination.ui.Moves
import com.thomaswcode.decrastination.ui.PieceSays
import com.thomaswcode.decrastination.ui.SetupPage
import com.thomaswcode.decrastination.ui.StatsPage
import com.thomaswcode.decrastination.ui.TaskSheetModel
import com.thomaswcode.decrastination.views.Asked
import com.thomaswcode.decrastination.views.BackupView
import com.thomaswcode.decrastination.views.BackupViews
import com.thomaswcode.decrastination.views.ChatView
import com.thomaswcode.decrastination.views.ChatViews
import com.thomaswcode.decrastination.views.CheckInAsk
import com.thomaswcode.decrastination.views.DayDrop
import com.thomaswcode.decrastination.views.DayView
import com.thomaswcode.decrastination.views.DayViews
import com.thomaswcode.decrastination.views.NoticesView
import com.thomaswcode.decrastination.views.OwnTaskEdit
import com.thomaswcode.decrastination.views.PhotoRead
import com.thomaswcode.decrastination.views.ReviewsView
import com.thomaswcode.decrastination.views.SettingsView
import com.thomaswcode.decrastination.views.SettingsViews
import com.thomaswcode.decrastination.views.SetupView
import com.thomaswcode.decrastination.views.SetupViews
import com.thomaswcode.decrastination.views.StatsView
import com.thomaswcode.decrastination.views.StatsViews
import com.thomaswcode.decrastination.views.TaskAsk
import com.thomaswcode.decrastination.views.TaskAsks
import com.thomaswcode.decrastination.views.TaskView
import com.thomaswcode.decrastination.views.TaskViews
import com.thomaswcode.decrastination.views.WeekView
import com.thomaswcode.decrastination.views.WorkView
import com.thomaswcode.decrastination.views.WorkViews
import java.io.File
import java.io.IOException
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext

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
    /** Where Claude's calls go instead of Anthropic: a test's stand-in, and only a test's. */
    claudeEndpoint: String? = null,
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

    /** Whether the server is taking the phone's stores whole, or commands: read once, at a start, so everything that hangs on it goes together. */
    var mode: String = db.meta("mode") ?: MIRROR
        private set

    val backend = SqliteBackend(db).apply { writable = mode != MIRROR }
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

    private val gateway = ModelGateway(stores.settings, { claudeKey }, stores.model, stores.tasks, clock, NoAlerts, scope) {}.apply { endpoint = claudeEndpoint }

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
    val owners: ServerOwners = ServerOwners(
        stores, clock, scope, gateway, planning,
        calendarTime = { calendarTime() },
        photosFolder = File(dataDir, "photos"),
        nightly = nightly,
        resetCopies = File(dataDir, "reset-copies"),
        version = version,
        eventAnswers = { calendarRead.value.answers },
        answersBack = { backed -> calendarRead.update { it.copy(answers = backed + it.answers) } },
        settled = { read -> db.betweenCommands(read) },
    )

    /** Whether a nightly file has somewhere to go. */
    private val hasNightly = nightly != null

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

    /** What moves a piece, its rules the phone's: it decides, and [ask] applies. */
    internal val moves = Moves(stores.tasks, owners.instructions, commands, planning, clock, scope)

    /** What's said of a piece as it's done. */
    private val pieceSays = PieceSays(owners.focus, stores.log, stores.settings, stores.learning, planning, clock)

    /** Commands applied as they arrive, one at a time. */
    val applier = CommandApplier(db, backend, commands, owners, clock, reload = { reloadAll() }, committed = { _revisions.tryEmit(it) })

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
                morning({ owners.conversation.forgetExpired() }, { owners.ownTasks.rollWeeks() }, { owners.briefing.run() }, { owners.conversation.pruneFelix() })
            },
            ServerJob("check-in", due = { last, now -> sundayDue(last, now, stores.settings.value.checkInMin) }) { owners.conversation.remindCheckIn() },
            ServerJob("review", due = { last, now -> sundayDue(last, now, stores.settings.value.checkInMin + Daily.REVIEW_AFTER_MIN) }) { owners.learning.review(ifDue = true) },
            ServerJob("nightly", due = { last, now -> nightDue(last, now) }, retryMs = HOUR_MS) { nightly() },
            ServerJob.every("heartbeat", MINUTE_MS) { heartbeatUrl?.let { http.get(it, emptyMap()) } },
        ),
    )

    /** Tonight's file, or a failure, so it's tried again rather than the night going without. */
    internal suspend fun nightly() {
        if (!hasNightly) return
        val written = owners.backups.backupNow()
        check(written == BackupService.Nightly.Written) { "The nightly file wasn't written: $written" }
    }

    /** The mode set at once, for a test: what a restart does. */
    internal fun modeForATest(set: String) {
        db.setMeta("mode", set)
        mode = set
        backend.writable = set != MIRROR
    }

    /** Commands from now on, for the browser tests' server, which starts in mirror mode to be seeded. */
    fun commandsForATest() = modeForATest(COMMANDS)

    /** What runs by itself: the jobs, and, once the server applies commands, what watches the stores and the plan made again. */
    fun start(scope: CoroutineScope) {
        if (mode == COMMANDS) {
            owners.start()
            planning.watcher.start()
        }
        jobs.start(scope)
    }

    /** What the phone's morning alarm does, as it does it: only the [briefing] can fail the job, as a retry would say it again. */
    internal suspend fun morning(forget: suspend () -> Unit, roll: suspend () -> Unit, briefing: suspend () -> Unit, prune: suspend () -> Unit) {
        aside("Forgetting what's run out", forget)
        aside("Rolling the weeks", roll)
        briefing()
        aside("Pruning Felix's notes", prune)
    }

    private suspend fun aside(what: String, step: suspend () -> Unit) {
        try {
            step()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            Log.w(Log.TAG, "$what failed: ${failed::class.simpleName}")
        }
    }

    /** The last moment before today, so a time at today's very start is still to come. */
    private fun beforeToday(now: Long): Long = Instant.ofEpochMilli(now).atZone(clock.zone()).toLocalDate().atStartOfDay(clock.zone()).toInstant().toEpochMilli() - 1

    /**
     * Whether the morning's work is due: today's, once its time comes. A run today stands for today, even with its time moved
     * later since (as `Daily.from` has it on the phone), and a day gone by isn't caught up on: `Briefing` says today's whenever it
     * runs, so running it early would say it twice.
     */
    internal fun morningDue(last: Long?, now: Long): Boolean {
        val zone = clock.zone()
        val ranToday = last != null && Instant.ofEpochMilli(last).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return !ranToday && Daily.nextBriefing(beforeToday(now), zone, stores.settings.value, stores.school.value) <= now
    }

    /** Whether Sunday's work at [minute] is due: its next time since it last ran, or, never run, since today began, so a first run says nothing of a Sunday gone by. */
    internal fun sundayDue(last: Long?, now: Long, minute: Int): Boolean = Daily.nextSunday(last ?: beforeToday(now), clock.zone(), minute) <= now

    /** Whether tonight's backup has gone: once a day, after [NIGHTLY_HOUR]. */
    private fun nightDue(last: Long?, now: Long): Boolean {
        val zone = clock.zone()
        val tonight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atTime(NIGHTLY_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        return now >= tonight && (last == null || last < tonight)
    }

    /** Your message that waits asked for again, at your Try again: on its own time, as a call can take a minute. */
    fun tryAgain() {
        scope.launch {
            try {
                owners.conversation.answerChat(retry = true)
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
    fun tidy() {
        val before = clock.now() - SqliteBackend.HISTORY_MS
        backend.trimHistory(before)
        backend.trimCommands(before)
    }

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
            timer = stores.session.value.timer,
            writable = mode == COMMANDS,
        )
    }

    fun work(): WorkView = WorkViews.of(stores.tasks.value, planning.plan(), stores.settings.value, clock.now(), clock.zone(), devices.lastSeen(), writable = mode == COMMANDS, readsPhotos = readsPhotos)

    /** Whether a photo can be read into tasks here: tasks can be saved, Claude's on, and its key's on the box. */
    private val readsPhotos: Boolean get() = mode == COMMANDS && stores.settings.value.aiEnabled && claudeKey != null

    /** Whether a photo is being read: one at a time, as on the phone, as each is a paid call. */
    private val readingPhoto = AtomicBoolean()

    /**
     * [jpeg], a photo of homework, read into tasks to check (NF-01), each under an id of its own: never in a command, as a call can
     * take a minute. Null while another's being read.
     */
    suspend fun readPhoto(jpeg: ByteArray): PhotoRead? = readPhoto(jpeg) { owners.photos.readPhotoTasks(it) }

    internal suspend fun readPhoto(jpeg: ByteArray, read: suspend (ByteArray) -> Pair<PhotoTasks.Reading?, String?>): PhotoRead? {
        if (!readingPhoto.compareAndSet(false, true)) return null
        try {
            val (reading, why) = read(jpeg)
            return PhotoRead(reading?.tasks.orEmpty().map { OwnTaskEdit(Ids.next(), it) }, reading?.leftOut.orEmpty(), reading?.question, why)
        } finally {
            readingPhoto.set(false)
        }
    }

    /** Settings as the website draws them. */
    fun settings(): SettingsView {
        val now = clock.now()
        val zone = clock.zone()
        val usage = stores.model.value.aiUsage.forMonth(AiUsage.monthOf(now, zone))
        return SettingsViews.of(
            owners.protection.asked, owners.protection.pending, stores.tasks.value, stores.school.value, stores.memory.value, usage,
            credentials(), stores.secrets.has(Secret.TotpSecret), now, zone, writable = mode == COMMANDS, calls = stores.model.value.calls,
        )
    }

    /** The parent code's secret, as the phone sends it or takes it away (W-19): held here so a code typed on the website can be checked. */
    suspend fun setParentCode(secret: String?) = stores.secrets.put(Secret.TotpSecret, secret?.takeIf { it.isNotBlank() })

    /**
     * The chat as the website draws it: [earlier] of the archive's newest over the window, or as far back as message [reach], a reply's
     * quote followed. Claude's on by this box's key where it's asked here, and by the phone's while it's asked there.
     */
    fun chat(earlier: Int, reach: String?): ChatView {
        val now = clock.now()
        val chat = stores.chat.value
        val archive = stores.chatArchive
        val writable = mode == COMMANDS
        val settings = stores.settings.value
        val back = reach?.let(archive::back) ?: 0
        // However far a page asks: it's added to a page's length below.
        val shown = minOf(maxOf(earlier, back), Int.MAX_VALUE - ChatRows.PAGE)
        val read = if (shown > 0) archive.recent(shown + ChatRows.PAGE) else emptyList()
        val known = (chat.messages + read).mapTo(HashSet()) { it.id }
        val quotes = (read.takeLast(shown) + chat.messages).mapNotNull { message -> message.replyTo?.takeIf { it !in known } }.distinct().mapNotNull(archive::find)
        return ChatViews.of(
            chat, read, shown, older = shown == 0 && archive.recent(1).isNotEmpty(), quotes, stores.tasks.value, settings, stores.memory.value, stores.felix.value, stores.instructions.value,
            owners.conversation.keptAt(now), owners.conversation.answeringSince.value, claudeOn,
            owners.focus.today(now), now, clock.zone(), writable,
        )
    }

    /** Whether Claude's on, by this box's key where it's asked here, and by the phone's while it's asked there. */
    private val claudeOn: Boolean
        get() = stores.settings.value.let { it.aiEnabled && (if (mode == COMMANDS) claudeKey != null else it.aiKeyActive) }

    /** Stats for the [period] named, for the website. */
    fun stats(period: String?): StatsView {
        val now = clock.now()
        val zone = clock.zone()
        val settings = stores.settings.value
        val credit = stores.session.value.credit.on(owners.focus.today(now))
        val usage = stores.model.value.aiUsage.forMonth(AiUsage.monthOf(now, zone))
        val today = StatsPage.Today(
            (credit.earnedMs / MINUTE_MS).toInt(),
            (credit.spentMs / MINUTE_MS).toInt(),
            (owners.focus.creditLeftMs(now) / MINUTE_MS).toInt(),
            usage.spentUsd,
            usage.calls,
            settings.aiMonthlyCapUsd,
            claudeOff = !claudeOn && usage.calls == 0,
        )
        return StatsViews.of(stores.log.value, stores.learning.value.calibration, stores.school.value, settings, today, period, now, zone, mode == COMMANDS)
    }

    /** The week's form and the latest review, for the website. */
    fun week(): WeekView = StatsViews.week(stores.log.value, stores.settings.value, clock.now(), clock.zone(), mode == COMMANDS)

    /** The reviews, for the website. */
    fun reviews(): ReviewsView = StatsViews.reviews(stores.log.value, stores.settings.value, stores.memory.value, clock.zone(), mode == COMMANDS)

    /** The week's [ask]ed check-in, as the phone's form keeps it; and, kept, Felix's questions ended and the review set off, on its own time. */
    suspend fun checkIn(ask: CheckInAsk): Asked {
        val now = clock.now()
        val checkIn = CheckIn(ask.week, now, ask.feel, ask.avoided.trim(), ask.inTheWay.trim(), ask.change.trim(), ask.energy.trim())
        val kept = website(RecordCheckIn(checkIn, now))
        if (kept.state != Outcome.APPLIED) return Asked(false, kept.why)
        website(EndCheckInFlow)
        owners.reviewSoon()
        return Asked(true)
    }

    /** The record as CSV files in a zip, as the phone's Export gives it. */
    fun record(): ByteArray = Export.zipped(Export.csv(stores.log.value, stores.learning.value.calibration, clock.zone(), stores.chatArchive.value.messages + stores.chat.value.messages))

    /** The Backup page, by what a backup of things as they stand comes to: on a thread of its own, as the chat's archive is read whole for it. */
    suspend fun backupView(): BackupView = withContext(Dispatchers.IO) {
        val backups = owners.backups
        val (leftOut, tooBig, refused) = runCatching {
            backups.encoded().let { (text, leftOut) -> Triple(leftOut, backups.tooBig(text), backups.unrestorable(text)) }
        }.getOrDefault(Triple(0, false, null))
        BackupViews.of(leftOut, tooBig, refused, stores.runtime.value.lastBackupAt, backups.resetCopies(), stores.instructions.value.applied, clock.now(), mode == COMMANDS)
    }

    /** A backup to download, no command half done in it. None if it's more than Import reads, as it couldn't be restored. */
    suspend fun backup(): String? = db.betweenCommands { withContext(Dispatchers.IO) { owners.backups.exportBackup() } }.takeUnless(owners.backups::tooBig)

    /** What a backup's called today. */
    fun backupName(): String = BackupNames.nightlyName(Instant.ofEpochMilli(clock.now()).atZone(clock.zone()).toLocalDate().toString())

    /** The backup [text] taken in as the phone's Import takes it, but whole: all of it or none, one revision, no command between. What came of it, in words. */
    suspend fun importBackup(text: String): String = applier.alone("import", NOT_RESTORED) { owners.backups.importBackup(text) }

    /** The reset's copy [name] taken in the same way. */
    suspend fun restoreCopy(name: String): String = applier.alone("import", NOT_RESTORED) { owners.backups.restoreResetCopy(name) }

    /** Setup for the website. */
    fun setup(): SetupView {
        val now = clock.now()
        val zone = clock.zone()
        val usage = stores.model.value.aiUsage.forMonth(AiUsage.monthOf(now, zone))
        return SetupViews.of(stores.tasks.value, stores.problems, stores::holds, stores.settings.value, usage, credentials(), claudeKey != null, now, zone, mode == COMMANDS)
    }

    /** What's said wherever you are on the website. */
    fun notices(): NoticesView {
        val settings = stores.settings.value
        val keyProblem = stores.model.value.aiUsage.keyProblem != null && settings.aiEnabled && settings.aiKeyActive
        return NoticesView(Outbox.unreadOf(stores.chat.value).size, SetupPage.attention(stores.tasks.value, gmailGivenUp = false, keyProblem = keyProblem, watchdog = false, unreadable = stores.problems.isNotEmpty()))
    }

    /** Task [id]'s sheet, if there's such a task. */
    fun task(id: String): TaskView? {
        val state = stores.tasks.value
        val task = state.tasks.firstOrNull { it.id == id } ?: return null
        return TaskViews.of(task, state.tasks.associateBy { it.id }, planning.plan(), stores.settings.value, stores.instructions.value.applied, stores.felix.value.notes, clock.now(), clock.zone(), writable = mode == COMMANDS, timer = stores.session.value.timer)
    }

    /** [ask] of task [id], made the commands the phone's sheet would make, and applied as the website's. */
    suspend fun ask(id: String, ask: TaskAsk): Asked {
        val task = stores.tasks.value.tasks.firstOrNull { it.id == id } ?: return Asked(false, GONE)
        val plan = planning.plan()
        val piece = TaskSheetModel.next(plan, id).takeIf { task.isOpen }
        if (ask.kind == TaskAsk.DONE) {
            // The one the page showed, whichever of its task's that is (Do now's can be ahead of the first in its list, a step in any
            // order laid sooner), or none: done elsewhere since, another's not what was meant.
            val shown = plan.chunksOf(id).firstOrNull { it.key == ask.key } ?: return Asked(false, Moves.CHANGED)
            var kept = false
            val said = pieceSays.kept(shown, ask.at ?: plan.now) { action -> (website(action).state == Outcome.APPLIED).also { kept = it } }
            return Asked(kept, said)
        }
        return made(TaskAsks.decide(task, piece, ask, moves, clock.zone()))
    }

    /** [drop] on the Day, by the phone's rules: what came of it, a move saying where it's taken back. */
    suspend fun drop(drop: DayDrop): Asked {
        val day = runCatching { LocalDate.parse(drop.date) }.getOrNull() ?: return Asked(false, "That needs a date")
        val plan = planning.plan()
        val slot = plan.bucket(day)?.slots?.firstOrNull { it.key == drop.key } ?: return Asked(false, Moves.CHANGED)
        val onto = drop.onto?.let { date -> runCatching { LocalDate.parse(date) }.getOrNull() ?: return Asked(false, "That needs a date") }
        val decided = moves.dropped(plan, slot, day, onto, drop.minute, ((slot.end - slot.start) / MINUTE_MS).toInt(), clock.zone()) ?: return Asked(false)
        val moved = made(decided)
        return if (moved.done) moved.copy(said = "${decided.said}: undone in the chat's Your changes") else moved
    }

    /** What's been [decided], applied as the website's: a step at a time, stopping at the first that doesn't go through. */
    private suspend fun made(decided: Moves.Decided): Asked {
        decided.why?.let { return Asked(false, it) }
        for (action in decided.actions) {
            val outcome = website(action)
            if (outcome.state != Outcome.APPLIED) return Asked(false, outcome.why)
        }
        return Asked(true, decided.said.ifEmpty { null })
    }

    /** [action] as one of the website's commands, made here. */
    private suspend fun website(action: Action): Outcome = applier.apply(CommandBatch(listOf(Command(Ids.next(), clock.now(), action))), Client.WEBSITE).single()

    companion object {
        /** The mode where commands apply: `meta.mode`, one way, from mirror mode. */
        const val COMMANDS = "commands"
        private const val SECRETS = "secrets.bin"
        private const val GONE = "That task had gone"
        private const val NOT_RESTORED = "Nothing was restored: it couldn't be applied."
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
