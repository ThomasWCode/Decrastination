package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.ChatActs
import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatHistory
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.FelixState
import com.thomaswcode.decrastination.core.GmailWrites
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
    /** Where backups go. */
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
    /** Your answers about events, from where they're kept here. */
    private val eventAnswers: () -> Map<String, String> = { stores.calendar.value.eventAnswers },
) {
    /** Where backups go: Documents/Decrastination, through MediaStore on the phone; a map in a test. */
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

    /**
     * Whether [text], a backup ([exportBackup]), is more than Import reads even with the chat's earlier messages
     * left out: one that couldn't be restored, so it isn't kept as a backup.
     */
    fun tooBig(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size > backupCap

    /**
     * Why [text], a backup ([exportBackup]), couldn't be restored, in words: more than Import reads ([tooBig]), or
     * what Import refuses ([Backups.problem]), checked as Import checks. Null where it could be.
     */
    fun unrestorable(text: String): String? = when {
        tooBig(text) -> "it's more than Import reads (8 MB)"
        else -> Backups.decode(text, stores.shapes).let { decoded -> if (decoded == null) "it couldn't be read back" else Backups.problem(decoded) }
    }

    /** All the app keeps, as a backup holds it, the chat's earlier messages whole. */
    private fun backupOf(every: Boolean = false): Backup =
        Backup(
            exportedAt = clock.now(),
            versionName = versionName,
            // As asked for: a change still waiting its delay is in it, and restored goes through
            // the same wait (or, restored here, keeps waiting) rather than being lost.
            settings = SettingsChanges.requested(stores.settings.value, stores.protection.value.pending),
            log = stores.log.value,
            calibration = stores.learning.value.calibration,
            eventAnswers = eventAnswers(),
            aiUsage = stores.model.value.aiUsage,
            // Those in use; for a reset's copy ([every]) all of them, one still being read or left unclear too,
            // as the reset takes them all.
            instructions = stores.instructions.value.let { if (every) it.instructions else it.applied },
            ownTasks = stores.tasks.value.tasks.filter { it.source == Source.Own },
            school = stores.school.value,
            memory = stores.memory.value,
            slots = stores.session.value.slots,
            chat = stores.chat.value,
            archive = stores.chatArchive.value,
            felixStore = stores.felix.value,
            gmailWrites = GmailWrites.receipts(stores.gmailWrites.value.writes),
            weeksDeleted = stores.tasks.value.weeksDeleted,
            notSame = stores.tasks.value.notSame,
            versions = stores.shapes.mapValues { it.value.version },
        )

    /** What became of a nightly backup ([backupNow]). */
    enum class Nightly {
        Written,

        /** More than Import reads even without the chat's earlier messages: not written, as it couldn't be restored. */
        TooBig,

        /** What Import refuses ([Backups.problem]): not written, as it couldn't be restored either. */
        Refused,

        /** Below Android 10, or where it can't be written. */
        NotWritten,
    }

    /**
     * The nightly backup (AR-07): all the app keeps, as `decrastination-<date>.json` in
     * Documents/Decrastination, replacing the day's if it's run again; those past the newest
     * fortnight let go; its time kept for Settings.
     */
    suspend fun backupNow(): Nightly = withContext(NonCancellable + Dispatchers.IO) {
        if (!folder.available) return@withContext Nightly.NotWritten
        val now = clock.now()
        val name = BackupNames.nightlyName(Instant.ofEpochMilli(now).atZone(clock.zone()).toLocalDate().toString())
        val text = exportBackup()
        // More than Import reads even without the chat's earlier messages, it couldn't be restored: not written,
        // and the older ones, which can be, not let go of for it (Settings → Backup says so).
        if (tooBig(text)) return@withContext Nightly.TooBig
        // Nor one Import would refuse: the good ones it would prune are the ones that can be restored.
        if (unrestorable(text) != null) return@withContext Nightly.Refused
        if (!folder.write(name, text)) return@withContext Nightly.NotWritten
        BackupNames.toPrune(folder.names()).forEach { folder.delete(it) }
        stores.replace(stores.runtime) { it.copy(lastBackupAt = now) }
        Nightly.Written
    }

    /**
     * Restores the backup [text] and says what happened. Nothing is restored from one that holds
     * what the app never writes ([Backups.problem]). The settings go through [changeSettings], so
     * once armed one that loosens blocking waits; the instructions as applying each here would go
     * ([restoreInstructions]); the log, the calibration and the calendar answers armed or not. They
     * were kept back while armed, as an edited file could teach the planner to plan less; but the
     * plan is yours to change at once now (D-46), and free time is earned in a ledger no backup holds.
     * A backup of an older shape is brought to this build's first ([Backups.decode]).
     */
    suspend fun importBackup(text: String): String {
        val backup = Backups.decode(text, stores.shapes) ?: return "That isn't a Decrastination backup (or it's from a newer version)."
        Backups.problem(backup)?.let { return "Nothing was restored: $it." }
        changeSettings { Backups.importedSettings(it, backup) }
        // Before the rules, so a week deleted isn't made again from them as they come back (D-124).
        stores.replace(stores.tasks) { it.copy(weeksDeleted = Backups.mergedWeeks(it.weeksDeleted, backup.weeksDeleted), notSame = Backups.mergedNotSame(it.notSame, backup.notSame)) }
        val (restored, unclear, asTheyWere) = restoreInstructions(backup.instructions)
        // Your own tasks not here already: no source can give them back. The instructions' say is laid
        // over them afresh, from those in use here.
        val ownAdded = Backups.restoredOwnTasks(backup.ownTasks, stores.tasks.value.tasks).map { it.id }.toSet()
        stores.replace(stores.tasks) { state ->
            val back = Backups.restoredOwnTasks(backup.ownTasks, state.tasks)
            val ids = back.mapTo(HashSet()) { it.id }
            state.copy(tasks = state.tasks.filterNot { it.id in ids } + back)
        }
        if (ownAdded.isNotEmpty()) layTaskOverrides()
        // The backup's days off and half-days beside those here (PL-16).
        stores.replace(stores.school) { it.withAll(backup.school) }
        // The chat where this phone's is empty (a reset's copy brings it back, LR-08), with its earlier
        // messages: not a message of yours still waiting for its answer, which would be sent, words from then, as
        // it opens; nor one that waited with it, or for its Try again, either of which would go with your next
        // words, as his turn takes yours that come one after another together. Those are left out, and said:
        // the backup file still holds them. Felix's state with it, or where this phone has none of its own. Into a
        // chat that isn't empty, his own messages it hasn't, with your replies to them, by id, as the
        // log's records of them were merged before 4.0.0: none of his is lost. Those read there are read
        // here, as far as that goes without passing one of his unread here, so his notification doesn't
        // bring them back as new.
        val unanswered = backup.chat.messages.filter { it.from == Speaker.You && it.answeredAt == null }.mapTo(HashSet()) { it.id }
        val backedChat = backup.chat.let { c -> c.copy(messages = c.messages.filterNot { it.id in unanswered }) }
        var chatBack = 0
        var hisBack = 0
        var yoursLeft = 0
        stores.replace(stores.chat) { s ->
            // This phone's state of his, where it has one, wins: a chat cleared here keeps it.
            val felix = if (s.felix == FelixState()) backedChat.felix else s.felix
            if (s.messages.isEmpty()) yoursLeft = unanswered.size
            if (s.messages.isEmpty() && backedChat.messages.isNotEmpty()) {
                backedChat.copy(felix = felix).also { chatBack = it.messages.size }
            } else {
                val here = s.messages.mapTo(HashSet()) { it.id }
                val his = ChatHistory.his(backedChat.messages).filter { it.id !in here }
                val ids = his.mapTo(HashSet()) { it.id }
                val replies = backedChat.messages.filter { it.from == Speaker.You && it.replyTo in ids && it.id !in here }
                if (s.messages.isNotEmpty()) yoursLeft = backup.chat.messages.count { it.id in unanswered && it.replyTo in ids && it.id !in here }
                hisBack = his.size
                val unreadHere = s.messages.filter { it.from != Speaker.You && it.at > s.readAt }.minOfOrNull { it.at }
                val readAt = maxOf(s.readAt, minOf(backedChat.readAt, (unreadHere ?: Long.MAX_VALUE) - 1))
                s.copy(felix = felix, messages = (s.messages + his + replies).sortedBy { it.at }, readAt = readAt)
            }
        }
        if (chatBack > 0) stores.replace(stores.chatArchive) { a -> if (a.messages.isEmpty()) backup.archive else a }
        // What it remembered (AI-21), what's run out on either side let go of first, so one here can't take the
        // place of the backup's that holds: by the chat as it's now restored, whose acts' Undos bring deleted tasks back.
        val now = clock.now()
        val tasks = stores.tasks.value
        val held = ChatActs.held(stores.chat.value.messages)
        fun live(memory: MemoryState) = Memories.expired(memory, tasks.tasks.associateBy { it.id }, now, held) { source -> tasks.status(source).lastSuccessAt }
        stores.replace(stores.memory) { Backups.mergedMemory(live(it), live(backup.memory)) }
        // Felix's notes and reminders it hasn't, by id: the chat's acts on them can be undone, and a reminder
        // still to come comes, late if its time has gone by.
        val felixBefore = stores.felix.value
        var felixLeft = 0
        val felixBack = stores.replace(stores.felix) { Backups.mergedFelix(it, backup.felixStore).also { (_, left) -> felixLeft = left }.first }
            .let { it.notes.size + it.reminders.size - felixBefore.notes.size - felixBefore.reminders.size }
        // The receipts of writes to Gmail it hasn't, by id: an act of the chat restored can undo its finish.
        stores.replace(stores.gmailWrites) { it.copy(writes = Backups.mergedWrites(it.writes, backup.gmailWrites)) }
        val own = ownAdded.size
        val told = buildList {
            if (own > 0) add("$own task${if (own == 1) "" else "s"} of your own")
            if (chatBack > 0) add("the chat ($chatBack message${if (chatBack == 1) "" else "s"})")
            if (hisBack > 0) add("$hisBack message${if (hisBack == 1) "" else "s"} of Felix's into the chat")
            if (yoursLeft > 0) add("$yoursLeft of yours that waited for an answer left out, as ${if (yoursLeft == 1) "it" else "they"}'d be sent with words from then (the backup file still holds them)")
            if (felixBack > 0) add("$felixBack of Felix's notes and reminders")
            if (felixLeft > 0) add("$felixLeft of the backup's notes and reminders left out, past what the app keeps")
            if (restored > 0) add("$restored instruction${if (restored == 1) "" else "s"} restored")
            if (unclear > 0) add("$unclear left unapplied under Your changes in Felix's chat, as ${if (unclear == 1) "it" else "they"} would have tasks wait for each other in a circle")
            if (asTheyWere > 0) add("$asTheyWere not in use back as ${if (asTheyWere == 1) "it was" else "they were"} under Your changes in Felix's chat")
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
     * A backup's instructions ([backed]) taken in: those in use in the order they were applied, each as
     * applying it here would go: one already here is left as it is; one that would have tasks wait
     * for each other in a circle with those here comes back unclear, saying so. The rest are
     * applied, due dates too (yours to change, D-46). Those not in use (a reset's copy holds them)
     * come back as they were. Says how many were applied, how many came back unclear for a circle,
     * and how many came back as they were.
     */
    private suspend fun restoreInstructions(backed: List<Instruction>): Triple<Int, Int, Int> = applying.withLock {
        val added = Backups.restoredInstructions(backed, stores.instructions.value.instructions)
        stores.replace(stores.instructions) { state -> state.copy(instructions = state.instructions + added.filter { a -> state.instructions.none { it.id == a.id } }) }
        val inUse = backed.filter { it.state == InstructionStatus.Applied }.mapTo(HashSet()) { it.id }
        val applied = added.count { it.state == InstructionStatus.Applied }
        if (applied > 0) layInstructions()
        Triple(applied, added.count { it.state == InstructionStatus.Unclear && it.id in inUse }, added.count { it.id !in inUse })
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
        // Made, fitted and checked off the main thread a reset's asked from: the chat's archive read whole for it, and the
        // copy (up to 8 MB) encoded, then decoded as Import reads it. Every instruction in it, in use or not, so none a
        // reset of them takes is lost.
        val (whole, fitted, refused) = withContext(Dispatchers.IO) {
            val whole = backupOf(every = true)
            val fitted = Backups.fitted(whole, backupCap)
            val refused = when {
                // Over the cap even without the chat's earlier messages, it couldn't be read back: nothing's reset.
                tooBig(fitted.first) -> "Nothing was reset: a copy of it would be too big to restore."
                // Nor where Import would refuse it (a part it can't read, something the app never writes): checked as Import checks.
                else -> unrestorable(fitted.first)?.let { "Nothing was reset: a copy of it couldn't be restored ($it)." }
            }
            Triple(whole, fitted, refused)
        }
        refused?.let { return@withContext it }
        val (text, leftOut) = fitted
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
        // What the copy holds is what's reset; what came while it was made and saved (an answer of Felix's landing, a
        // completion, a note) is in no copy, so it's kept, not erased with the rest: the copy is the reset's undo.
        if (all || what == Reset.Stats) {
            resets.incrementAndGet()
            stores.replace(stores.log) { it.since(whole.log) }
            stores.replace(stores.learning) { it.copy(calibration = Calibration()) }
        }
        if (what == Reset.Calibration) stores.replace(stores.learning) { it.copy(calibration = Calibration(learnFrom = now)) }
        if (all || what == Reset.Memories) {
            stores.replace(stores.memory) { m -> MemoryState(facts = m.facts - whole.memory.facts.toSet(), rules = m.rules - whole.memory.rules.toSet()) }
            changeSettings { it.copy(profileLines = emptyList()) }
        }
        if (all || what == Reset.Chat) {
            // The chat, its earlier messages, and his state with it: what he's waiting to say, the check-in's answers so far.
            val copied = (whole.chat.messages + whole.archive.messages).mapTo(HashSet()) { it.id }
            stores.replace(stores.chat) { c -> ChatState(messages = c.messages.filterNot { it.id in copied }) }
            // Read and written under the archive's own lock; cleared whole, its months' copies with it, where nothing's left.
            val archiveSince = stores.replace(stores.chatArchive) { a -> a.copy(messages = a.messages.filterNot { it.id in copied }) }
            if (archiveSince.messages.isEmpty()) stores.replace(stores.chatArchive, ChatArchive())
            // His notes and reminders (D-100), which the chat set.
            val notes = whole.felixStore.notes.mapTo(HashSet()) { it.id }
            val reminders = whole.felixStore.reminders.mapTo(HashSet()) { it.id }
            stores.replace(stores.felix) { f -> FelixStore(notes = f.notes.filterNot { it.id in notes }, reminders = f.reminders.filterNot { it.id in reminders }) }
            // His notification too, a check-in's question among them: nothing's left to answer it.
            cancelFelix()
        }
        if (all || what == Reset.Instructions) {
            val copied = whole.instructions.mapTo(HashSet()) { it.id }
            stores.replace(stores.instructions) { i -> InstructionState(instructions = i.instructions.filterNot { it.id in copied }) }
            layInstructions()
        }
        // The copies kept of every store it cleared with them (D-94): what's reset isn't kept in one.
        stores.letGoOfCopies(what)
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
