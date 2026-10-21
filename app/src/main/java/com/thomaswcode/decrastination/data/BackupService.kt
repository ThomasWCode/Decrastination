package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.ChatActs
import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatHistory
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.FelixState
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Memories
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.learn.Calibrator
import com.thomaswcode.decrastination.protect.SettingsChanges
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Backups and resets (docs/spec/storage-and-backups.md, AR-07, LR-08): what the app keeps that the
 * sources can't give back, written as a backup ([exportBackup], the nightly [backupNow]) and read
 * back ([importBackup]), and a part of it reset, a copy kept first ([reset]). It writes the stores
 * through [Stores.replace], the one way round their owners; what isn't its own (the settings' wait,
 * your changes laid on the tasks, the alarms, the calendar) it asks for.
 */
class BackupService(
    private val stores: Stores,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    /** Where backups go ([AndroidBackupFolder] on the phone). */
    private val folder: Folder,
    /** Where a reset's copy goes when [folder] can't take it: the app's own files. */
    private val copies: File,
    /** The app's version, which a backup names. */
    private val versionName: String,
    /** A settings change, as Settings makes one: once armed, one that loosens blocking waits. */
    private val changeSettings: suspend ((Settings) -> Settings) -> Unit,
    /** Held from checking an instruction to applying it ([Instructions]'s lock). */
    private val applying: Mutex,
    /** The instructions in use laid over everything they change, the plan made again. */
    private val layInstructions: suspend () -> Unit,
    /** Each task's values made what the instructions in use say. */
    private val layTaskOverrides: suspend () -> Unit,
    /** The reminders' alarms set again at their times, as a save in Settings sets them. */
    private val schedule: () -> Unit,
    /** After a restore: the calendar read again with its answers, and today's record made again. */
    private val reread: suspend () -> Unit,
    /** Felix's notification taken away: nothing's left of the chat to answer it. */
    private val cancelFelix: () -> Unit,
    /** What a backup holds at most, as Import reads it ([Backups.MAX_BYTES]; smaller in a test). */
    private val backupCap: Int = Backups.MAX_BYTES,
) {
    /** Where backups go: Documents/Decrastination, through MediaStore on the phone ([BackupFolder]); a map in a test. */
    interface Folder {
        /** Whether it can be written here (Android 10 and later). */
        val available: Boolean

        /** [text] as the file [name], in place of one of that name. */
        fun write(name: String, text: String): Boolean

        /** The backups there, by name. */
        fun names(): List<String>

        fun delete(name: String): Boolean
    }

    /**
     * A backup of what the sources can't give back, as text ([Backups]): the chat's earlier messages
     * in it while it fits what Import reads, the oldest left out first ([Backups.fitted]).
     */
    fun exportBackup(): String = encoded().first

    /** [exportBackup], and how many of the chat's earliest messages it leaves out for room: said in Settings → Backup. */
    fun encoded(): Pair<String, Int> = backupOf().let { Backups.fitted(it, backupCap) }

    /** All the app keeps, as a backup holds it, the chat's earlier messages whole. */
    private fun backupOf(): Backup =
        Backup(
            exportedAt = clock.now(),
            versionName = versionName,
            // As asked for: a change still waiting its delay is in it, and restored goes through
            // the same wait (or, restored here, keeps waiting) rather than being lost.
            settings = SettingsChanges.requested(stores.settings.value, stores.protection.value.pending),
            log = stores.log.value,
            calibration = stores.learning.value.calibration,
            eventAnswers = stores.calendar.value.eventAnswers,
            aiUsage = stores.model.value.aiUsage,
            instructions = stores.instructions.value.applied,
            ownTasks = stores.tasks.value.tasks.filter { it.source == Source.Own },
            school = stores.school.value,
            memory = stores.memory.value,
            slots = stores.session.value.slots,
            chat = stores.chat.value,
            archive = stores.chatArchive.value,
            felixStore = stores.felix.value,
            versions = stores.shapes.mapValues { it.value.version },
        )

    /**
     * The nightly backup (AR-07): all the app keeps, as `decrastination-<date>.json` in
     * Documents/Decrastination, replacing the day's if it's run again; those past the newest
     * fortnight let go; its time kept for Settings. False below Android 10, or where it can't be written.
     */
    suspend fun backupNow(): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        if (!folder.available) return@withContext false
        val now = clock.now()
        val name = BackupFolder.nightlyName(Instant.ofEpochMilli(now).atZone(clock.zone()).toLocalDate().toString())
        if (!folder.write(name, exportBackup())) return@withContext false
        BackupFolder.toPrune(folder.names()).forEach { folder.delete(it) }
        stores.replace(stores.runtime) { it.copy(lastBackupAt = now) }
        true
    }

    /**
     * Restores the backup [text] and says what happened. Nothing is restored from one that holds
     * what the app never writes ([Backups.problem]). The settings go through [changeSettings], so
     * once armed one that loosens blocking waits; the instructions as applying each here would go
     * ([restoreInstructions]); the log, the calibration and the calendar answers armed or not. They
     * were kept back while armed, as an edited file could teach the planner to plan less; but the
     * plan is yours to change at once now (12 Oct), and free time is earned in a ledger no backup holds.
     * A backup of an older shape is brought to this build's first ([Backups.decode]).
     */
    suspend fun importBackup(text: String): String {
        val backup = Backups.decode(text, stores.shapes) ?: return "That isn't a Decrastination backup (or it's from a newer version)."
        Backups.problem(backup)?.let { return "Nothing was restored: $it." }
        changeSettings { Backups.importedSettings(it, backup) }
        val (restored, unclear) = restoreInstructions(backup.instructions)
        // Your own tasks not here already: no source can give them back. The instructions' say is laid
        // over them afresh, from those in use here.
        val ownAdded = Backups.restoredOwnTasks(backup.ownTasks, stores.tasks.value.tasks).map { it.id }.toSet()
        stores.replace(stores.tasks) { state -> state.copy(tasks = state.tasks + Backups.restoredOwnTasks(backup.ownTasks, state.tasks)) }
        if (ownAdded.isNotEmpty()) layTaskOverrides()
        // The backup's days off and half-days beside those here (PL-16).
        stores.replace(stores.school) { it.withAll(backup.school) }
        // The chat where this phone's is empty (a reset's copy brings it back, LR-08), with its earlier
        // messages: not a message of yours still waiting for its answer, which would be sent, words
        // from then, as it opens. Felix's state with it, or where this phone has none of its own. Into a
        // chat that isn't empty, his own messages it hasn't, with your replies to them, by id, as the
        // log's records of them were merged before 4.0.0: none of his is lost. Those read there are read
        // here, as far as that goes without passing one of his unread here, so his notification doesn't
        // bring them back as new.
        val backedChat = backup.chat.let { c -> c.unanswered?.let { w -> c.copy(messages = c.messages.filterNot { it.id == w.id }) } ?: c }
        var chatBack = 0
        var hisBack = 0
        stores.replace(stores.chat) { s ->
            // This phone's state of his, where it has one, wins: a chat cleared here keeps it.
            val felix = if (s.felix == FelixState()) backedChat.felix else s.felix
            if (s.messages.isEmpty() && backedChat.messages.isNotEmpty()) {
                backedChat.copy(felix = felix).also { chatBack = it.messages.size }
            } else {
                val here = s.messages.mapTo(HashSet()) { it.id }
                val his = ChatHistory.his(backedChat.messages).filter { it.id !in here }
                val ids = his.mapTo(HashSet()) { it.id }
                val replies = backedChat.messages.filter { it.from == Speaker.You && it.replyTo in ids && it.id !in here }
                hisBack = his.size
                val unreadHere = s.messages.filter { it.from != Speaker.You && it.at > s.readAt }.minOfOrNull { it.at }
                val readAt = maxOf(s.readAt, minOf(backedChat.readAt, (unreadHere ?: Long.MAX_VALUE) - 1))
                s.copy(felix = felix, messages = (s.messages + his + replies).sortedBy { it.at }, readAt = readAt)
            }
        }
        if (chatBack > 0) stores.replace(stores.chatArchive) { a -> if (a.messages.isEmpty()) backup.archive else a }
        // What it remembered (AI-21), what's run out on either side let go of first (10a), so one here can't take the
        // place of the backup's that holds: by the chat as it's now restored, whose acts' Undos bring deleted tasks back.
        val now = clock.now()
        val tasks = stores.tasks.value
        val held = ChatActs.held(stores.chat.value.messages)
        fun live(memory: MemoryState) = Memories.expired(memory, tasks.tasks.associateBy { it.id }, now, held) { source -> tasks.status(source).lastSuccessAt }
        stores.replace(stores.memory) { Backups.mergedMemory(live(it), live(backup.memory)) }
        // Felix's notes and reminders it hasn't, by id (4.1.0): the chat's acts on them can be undone, and a reminder
        // still to come comes, late if its time has gone by.
        val felixBefore = stores.felix.value
        var felixLeft = 0
        val felixBack = stores.replace(stores.felix) { Backups.mergedFelix(it, backup.felixStore).also { (_, left) -> felixLeft = left }.first }
            .let { it.notes.size + it.reminders.size - felixBefore.notes.size - felixBefore.reminders.size }
        val own = ownAdded.size
        val told = buildList {
            if (own > 0) add("$own task${if (own == 1) "" else "s"} of your own")
            if (chatBack > 0) add("the chat ($chatBack message${if (chatBack == 1) "" else "s"})")
            if (hisBack > 0) add("$hisBack message${if (hisBack == 1) "" else "s"} of Felix's into the chat")
            if (felixBack > 0) add("$felixBack of Felix's notes and reminders")
            if (felixLeft > 0) add("$felixLeft of the backup's notes and reminders left out, past what the app keeps")
            if (restored > 0) add("$restored instruction${if (restored == 1) "" else "s"} restored")
            if (unclear > 0) add("$unclear left unapplied under Your changes in Felix's chat, as ${if (unclear == 1) "it" else "they"} would have tasks wait for each other in a circle")
        }.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " ", postfix = ".") ?: ""
        // Today's slots, restored the same day (AR-07).
        val today = Instant.ofEpochMilli(clock.now()).atZone(clock.zone()).toLocalDate().toString()
        stores.replace(stores.session) { s ->
            s.copy(slots = if (backup.slots.date == today && s.slots.on(LocalDate.parse(today)).let { it.previous.isEmpty() && it.missed.isEmpty() }) backup.slots else s.slots)
        }
        // This month's spend on Claude, armed or not: it can only rise, so the cap isn't given again.
        val month = AiUsage.monthOf(clock.now(), clock.zone())
        stores.replace(stores.model) { it.copy(aiUsage = Backups.mergeUsage(it.aiUsage, backup.aiUsage, month)) }
        // The reminders' alarms at the restored times, as a save in Settings sets them.
        schedule()
        val waiting = stores.protection.value.pending.size
        val waits = if (waiting > 0) " $waiting change${if (waiting == 1) " that loosens blocking waits" else "s that loosen blocking wait"} ${stores.settings.value.loosenDelayHours} hours." else ""
        stores.replace(stores.log) { Backups.mergeLog(it, backup.log, clock.now()) }
        // Your answers on this phone win over the backup's.
        // One from before 1.8.0 under its rule now, as at a start, from the log as merged.
        val calibration = Calibrator.upgraded(backup.calibration, stores.log.value.completions) ?: backup.calibration
        stores.replace(stores.learning) { it.copy(calibration = calibration) }
        stores.replace(stores.calendar) { it.copy(eventAnswers = backup.eventAnswers + it.eventAnswers) }
        // Read with the restored answers, and today's record made again, as an answer given here does.
        scope.launch { reread() }
        return "Restored the settings, ${backup.log.completions.size} completions and ${backup.log.slots.size} slots, what the app had learned, and your calendar answers.$waits$told"
    }

    /**
     * A backup's instructions in use ([backed]) taken in, in the order they were applied, each as
     * applying it here would go: one already here is left as it is; one that would have tasks wait
     * for each other in a circle with those here comes back unclear, saying so. The rest are
     * applied, due dates too (yours to change, 12 Oct). Says how many were applied, and how many
     * are unclear.
     */
    private suspend fun restoreInstructions(backed: List<Instruction>): Pair<Int, Int> = applying.withLock {
        val added = Backups.restoredInstructions(backed, stores.instructions.value.instructions)
        stores.replace(stores.instructions) { state -> state.copy(instructions = state.instructions + added.filter { a -> state.instructions.none { it.id == a.id } }) }
        val applied = added.count { it.state == InstructionStatus.Applied }
        if (applied > 0) layInstructions()
        applied to added.count { it.state == InstructionStatus.Unclear }
    }

    /** Raised at each reset of the record (LR-08), so a review already out when it came doesn't put back what it took. */
    val resets = AtomicInteger()

    /**
     * [what] reset (LR-08), with no parent code, a copy of all the app keeps saved first, as
     * `reset-<date>-<time>.json` in Documents/Decrastination (or the app's own files where that
     * can't be written). Where the chat goes and the copy has no room for its earliest messages
     * ([Backups.fitted]), they're kept whole beside it as `chat-archive-<date>-<time>.json`: none is
     * lost. What happened, in words.
     */
    suspend fun reset(what: Reset): String = withContext(NonCancellable) {
        val now = clock.now()
        // To the second: two resets the same day each keep their copy.
        val stamp = Instant.ofEpochMilli(now).atZone(clock.zone()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.UK))
        val name = "reset-$stamp.json"
        // The chat's archive read whole for it: off the main thread a reset's asked from.
        val whole = withContext(Dispatchers.IO) { backupOf() }
        val (text, leftOut) = Backups.fitted(whole, backupCap)
        val all = what == Reset.Everything
        // Nothing goes till its copy is somewhere it can be restored from.
        val saved = keep(name, text) ?: return@withContext "Nothing was reset: a copy of it couldn't be saved first."
        val archived = if ((all || what == Reset.Chat) && leftOut > 0) {
            val archive = "chat-archive-$stamp.json"
            keep(archive, JsonStore.json.encodeToString(ChatArchive.serializer(), whole.archive))
                ?.let { " The chat's earliest $leftOut messages, past what a backup holds, are whole in ${it.where}." }
                ?: return@withContext "Nothing was reset: the chat's earliest messages couldn't be saved first."
        } else {
            ""
        }
        if (all || what == Reset.Stats) {
            resets.incrementAndGet()
            stores.replace(stores.log, ActivityLog())
            stores.replace(stores.learning) { it.copy(calibration = Calibration()) }
        }
        if (what == Reset.Calibration) stores.replace(stores.learning) { it.copy(calibration = Calibration(learnFrom = now)) }
        if (all || what == Reset.Memories) {
            stores.replace(stores.memory, MemoryState())
            // Its copies with it (Q42): what's cleared isn't kept in one.
            stores.memory.letGoOfCopies()
            changeSettings { it.copy(profileLines = emptyList()) }
        }
        if (all || what == Reset.Chat) {
            // The chat, its earlier messages, and his state with it: what he's waiting to say, the check-in's answers so far.
            stores.replace(stores.chat, ChatState())
            stores.replace(stores.chatArchive, ChatArchive())
            // His notes and reminders (Q12: A), which the chat set.
            stores.replace(stores.felix, FelixStore())
            // The window's and his copies with them, as the archive's go with its months (Q42).
            stores.chat.letGoOfCopies()
            stores.felix.letGoOfCopies()
            // His notification too, a check-in's question among them: nothing's left to answer it.
            cancelFelix()
        }
        if (all || what == Reset.Instructions) {
            stores.replace(stores.instructions, InstructionState())
            layInstructions()
        }
        "${what.title}: reset. A copy of everything from before is in ${saved.where}${if (saved.own) ", which Settings → Backup can restore" else ""}.$archived"
    }

    /** Where [keep] put a file, in words: in Documents, or in the app's own files ([own]), which the file picker can't reach. */
    private data class Kept(val where: String, val own: Boolean)

    /**
     * [text] saved as [name] in Documents/Decrastination, or, where that can't be written, in the app's own
     * files, from which Settings → Backup restores a reset's copy itself ([resetCopies]). Null where it
     * couldn't be saved.
     */
    private suspend fun keep(name: String, text: String): Kept? = runCatching {
        if (folder.available && withContext(Dispatchers.IO) { folder.write(name, text) }) {
            Kept("Documents/Decrastination/$name", own = false)
        } else {
            withContext(Dispatchers.IO) { File(copies.apply { mkdirs() }, name).writeText(text) }
            Kept("the app's own files ($name)", own = true)
        }
    }.getOrNull()

    /** A reset's copies kept in the app's own files (LR-08), where Documents couldn't be written then: their names, newest first. */
    fun resetCopies(): List<String> =
        copies.listFiles { f -> f.isFile && f.name.startsWith("reset-") && f.name.endsWith(".json") }.orEmpty().map { it.name }.sortedDescending()

    /** [name], one of [resetCopies], restored as Import restores a backup. What happened, in words. */
    suspend fun restoreResetCopy(name: String): String {
        if (name !in resetCopies()) return "That copy isn't there."
        val bytes = withContext(Dispatchers.IO) { File(copies, name).inputStream().use { Backups.read(it) } } ?: return "That copy is too big to be a backup."
        return importBackup(bytes.decodeToString())
    }
}
