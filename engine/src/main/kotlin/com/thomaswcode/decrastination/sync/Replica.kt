package com.thomaswcode.decrastination.sync

import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.TaskPhoto
import com.thomaswcode.decrastination.block.Photos
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.ChatArchiveStore
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.data.SyncState
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.enrich.PhotoTasks
import com.thomaswcode.decrastination.net.Http
import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.sync.protocol.AttachPhoto
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.ui.Format
import com.thomaswcode.decrastination.views.Asked
import com.thomaswcode.decrastination.views.PhotoCheckAsk
import com.thomaswcode.decrastination.views.PhotoChecked
import com.thomaswcode.decrastination.views.PhotoRead
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.Commands
import com.thomaswcode.decrastination.sync.protocol.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The phone and the server (docs/spec/sync.md). This phone pairs with a code the website shows, and sends what the server
 * keeps a copy of: each store whole, those changed since it was last accepted. The phone is the only writer in mirror
 * mode, so nothing here changes a store but [sync]'s own; what it can't send now it sends next time, and being offline
 * changes nothing else on the phone.
 */
class Replica(
    private val stores: Stores,
    private val http: Http,
    private val clock: WallClock,
    /** This build, for the server's record. */
    private val build: String,
    /** The calendar as last read, which only this phone can read. */
    private val calendar: () -> CalendarRead,
    /** Where a pull goes through: no command applied half-way, and none waiting, as what a command made is already in the stores. */
    private val commands: Commands? = null,
    /** Where a photo's reading goes: its answer waits on Claude's call on the server, so this waits [PHOTO_WAIT_MS] for it. */
    private val photoHttp: Http = http,
    /** The chat's messages a pull brought, none of them here before: Felix's, said on the server, heard here as his notification. */
    private val heard: (List<ChatMessage>) -> Unit = {},
) {
    private val sync: JsonStore<SyncState> = stores.sync

    /** One at a time: a screen's, a job's. */
    private val sending = Mutex()

    /** The archive's change count as last in step, to save reading its months where none has changed. */
    @Volatile
    private var archiveSent = NOT_SENT

    /** Each pairing's count in this process, so an id sent under one is never taken as sent to the next. */
    @Volatile
    private var pairings = 0

    /**
     * The id Firebase reaches this phone by, as this process last had the server take it, and under which pairing: sent once a
     * process, so a server that's lost it hears it again, and once a pairing. A send under a pairing since let go of, its answer
     * heard after the new one's made, is then no answer for the new one, without any lock that [pair] would wait on.
     */
    @Volatile
    private var pushSent: Pair<Int, String>? = null

    val state: StateFlow<SyncState> get() = sync.state

    /** Whether this phone is paired. */
    val paired: Boolean get() = stores.secrets.has(Secret.DeviceToken)

    /** Pairs with [code], the website's, this phone called [name]. What went wrong, or null. */
    suspend fun pair(code: String, name: String): String? {
        val body = JSON.encodeToString(DeviceClaim.serializer(), DeviceClaim(code.trim(), name))
        val answer = try {
            http.post("${sync.value.server}/api/devices/claim", mapOf("Content-Type" to "application/json"), body)
        } catch (failed: IOException) {
            Log.w(Log.TAG, "Pairing failed: ${failed::class.simpleName}")
            return "The server can't be reached"
        }
        if (!answer.ok) return if (answer.code == 403) "That code isn't right, or it's run out" else "The server said no (${answer.code})"
        val token = JSON.decodeFromString(DeviceToken.serializer(), answer.body)
        stores.secrets.put(Secret.DeviceToken, token.token)
        sync.update { it.copy(deviceId = token.id, sent = emptyMap(), lastError = null, refused = emptyList(), parentCodeSent = null) }
        pairings++
        return null
    }

    /**
     * This phone paired no more: its token let go. Nothing else changes, and pairing again sends everything, the id Firebase
     * reaches it by among it, which is this phone's and stays.
     */
    suspend fun forget() {
        stores.secrets.put(Secret.DeviceToken, null)
        sync.update { SyncState(server = it.server, pushId = it.pushId) }
        archiveSent = NOT_SENT
    }

    /** Firebase has registered this phone as [fid] (W-18): kept, and sent with the next upload that goes through. */
    suspend fun registered(fid: String) {
        if (sync.value.pushId != fid) sync.update { it.copy(pushId = fid) }
    }

    /**
     * The server's word that it's changed, at [revision]: an exchange, unless what it said is here already and nothing's waiting
     * to go, as a phone that made the change pulled it in the same exchange. While mirroring, an upload as any other.
     */
    suspend fun told(revision: Long): Boolean {
        val state = sync.value
        if (state.mode == SyncState.COMMANDS && revision <= state.revision && stores.outbox.value.commands.isEmpty()) return true
        return upload()
    }

    /** The server asked to tell this phone through Firebase now, whatever its mode: what came of it, null if it isn't paired. */
    suspend fun testPush(): PushTried? {
        val token = stores.secrets[Secret.DeviceToken] ?: return null
        val headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token", Protocol.HEADER to Protocol.VERSION.toString())
        return try {
            val answer = http.post("${sync.value.server}/api/devices/push/test", headers, "")
            if (answer.ok) JSON.decodeFromString(PushTried.serializer(), answer.body) else PushTried(false, problem(answer.code))
        } catch (failed: IOException) {
            Log.w(Log.TAG, "Asking to be told failed: ${failed::class.simpleName}")
            PushTried(false, "The server can't be reached")
        }
    }

    /** The next pull to be of everything: what's here may hold what the server's run of a command won't change. */
    suspend fun pullEverything() {
        sync.update { it.copy(revision = 0) }
    }

    /** What the server didn't apply, read and put away. */
    suspend fun clearDropped() {
        sync.update { it.copy(dropped = emptyList()) }
    }

    /** The server's address, for a test on a PC. */
    suspend fun server(url: String) {
        sync.update { it.copy(server = url.trimEnd('/')) }
    }

    /**
     * What the server doesn't hold, sent. Whether it took it: a failure's kept for Setup, and tried again next time. Once it's gone
     * through, where Firebase reaches this phone, if the server's yet to take it ([sendPushId]).
     */
    suspend fun upload(): Boolean = sending.withLock {
        if (sync.value.mode == SyncState.COMMANDS) {
            // A new parent code ahead of the commands, so one made with it isn't checked against the one before; after them too, where
            // the server's mode couldn't be told first.
            sendParentCode(ahead = true)
            return@withLock exchange().also {
                if (it) {
                    sendPushId()
                    sendParentCode()
                }
            }
        }
        // Again where a send leaves something behind: the server holds less than it should (it's been restored, say), or a store
        // changed while it went, which work already running can't be asked for a second time.
        var sent = send()
        var rounds = 1
        while (rounds < ROUNDS && sync.value.mode != SyncState.COMMANDS && (sent == Sent.Behind || (sent == Sent.InStep && unsent()))) {
            sent = send()
            rounds++
        }
        (sent != Sent.Failed).also { if (it) sendPushId() }
    }

    /**
     * The id Firebase reaches this phone by, to the server: once a process, so a server restored without it hears it again, and
     * at once when Firebase gives a new one. One that doesn't go is tried at the next upload, and says nothing on Setup: the phone
     * still hears at its own sync.
     */
    private suspend fun sendPushId() {
        val fid = sync.value.pushId ?: return
        val pairing = pairings
        if (pushSent == pairing to fid) return
        val token = stores.secrets[Secret.DeviceToken] ?: return
        val headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token", Protocol.HEADER to Protocol.VERSION.toString())
        val answer = try {
            http.post("${sync.value.server}/api/devices/push", headers, JSON.encodeToString(PushId.serializer(), PushId(fid)))
        } catch (failed: IOException) {
            Log.w(Log.TAG, "Sending where Firebase reaches this phone failed: ${failed::class.simpleName}")
            return
        }
        if (answer.ok) pushSent = pairing to fid
    }

    /**
     * The parent code's secret, to the server, once the two keep in step by commands, as it checks a code typed on the website as the
     * phone does (W-23): where the one here differs from what the server last took, by its hash, so a new one, or none, goes before
     * the next exchange's commands ([ahead], the server's mode asked first) or after it, and one that didn't go is tried at the next.
     * Never while mirroring, so nothing leaves the phone early. Said nowhere when it fails: the phone checks its own codes all the same.
     */
    private suspend fun sendParentCode(ahead: Boolean = false) {
        if (sync.value.mode != SyncState.COMMANDS) return
        val secret = stores.secrets[Secret.TotpSecret]
        val held = secret?.let(Protocol::hash) ?: SyncState.NO_CODE
        if (sync.value.parentCodeSent == held) return
        val token = stores.secrets[Secret.DeviceToken] ?: return
        val headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token", Protocol.HEADER to Protocol.VERSION.toString())
        // Before an exchange has said the server still applies commands: one gone back to mirroring is told nothing.
        if (ahead && serverMode(headers) != SyncState.COMMANDS) return
        val answer = try {
            http.post("${sync.value.server}/api/parent-code", headers, JSON.encodeToString(ParentCode.serializer(), ParentCode(secret)))
        } catch (failed: IOException) {
            Log.w(Log.TAG, "Sending the parent code failed: ${failed::class.simpleName}")
            return
        }
        if (answer.ok) sync.update { it.copy(parentCodeSent = held) }
    }

    /** Whether this process has been through an exchange: what it found waiting at its start has been looked at. */
    private var started = false

    private enum class Sent { InStep, Behind, Failed }

    /** The server's mode, as it says it; null if it won't. */
    private fun serverMode(headers: Map<String, String>): String? = try {
        val answer = http.get("${sync.value.server}/api/version", headers)
        if (answer.ok) JSON.decodeFromString(ServerVersion.serializer(), answer.body).mode else null
    } catch (failed: IOException) {
        Log.w(Log.TAG, "Asking the server its mode failed: ${failed::class.simpleName}")
        null
    }

    /** Whether the server says it now applies commands: this phone then keeps in step that way, at its next upload. */
    private suspend fun switched(headers: Map<String, String>): Boolean {
        if (serverMode(headers) != SyncState.COMMANDS) return false
        sync.update { it.copy(mode = SyncState.COMMANDS, sent = emptyMap(), lastError = null) }
        return true
    }

    /**
     * The server's taking seeds again, as a restore's steps leave it (docs/spec/server.md): what's here is the newest there is, so
     * it's all sent, from the next upload, what's waiting in the outbox with it, as that's in the stores already.
     */
    private suspend fun backToMirroring(): Boolean {
        stores.outbox.update { it.copy(commands = emptyList()) }
        // The let-gos acted on are another history's: a restored server's revisions start again below them. And a restored server
        // may not hold the parent code it last took.
        sync.update { it.copy(mode = SyncState.MIRROR, revision = 0, sent = emptyMap(), lastError = null, letGoSeen = 0, parentCodeSent = null) }
        archiveSent = NOT_SENT
        return true
    }

    /**
     * The commands waiting sent, in order, and the stores changed since the last pull taken in their place (docs/spec/sync.md).
     * What's been answered leaves the outbox; a command the server dropped is said, and what it did here goes with the pull of
     * everything that follows. A pull that follows no answer isn't made, and one that finds a command made meanwhile isn't taken:
     * stores are replaced only with nothing waiting ([Commands.replacing]).
     * A store newer than this build can read stops the pull whole: the stores change together, and half of them new is worse than
     * all of them old. So does a server behind what this phone last took: it's been restored, and what's here is all that's left of
     * what it's lost.
     */
    private suspend fun exchange(): Boolean {
        val token = stores.secrets[Secret.DeviceToken] ?: return false
        val headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token", Protocol.HEADER to Protocol.VERSION.toString(), Protocol.ZONE_HEADER to clock.zone().id)
        val server = sync.value.server
        // Marked as they're read, so none is merged into on its way ([Commands.sending]).
        val waiting = commands?.sending() ?: stores.outbox.value.commands
        // A stopped process may have left a command half made, or not made at all. What the server makes of it comes in the pull,
        // but not what's left of it in a store the server doesn't change: so the first pull after such a start is of everything.
        if (!started) {
            started = true
            if (waiting.isNotEmpty()) sync.update { it.copy(revision = 0) }
        }
        try {
            if (waiting.isNotEmpty()) {
                // A task's photo on the box ahead of the command that keeps it, so the server's reading of the task has it (D-148): one
                // that doesn't go holds the commands back till the next try.
                for (photo in waiting.mapNotNull { (it.action as? AttachPhoto)?.photo }) {
                    if (!sendTaskPhoto(server, headers, photo)) return failed("A task's photo couldn't be sent")
                }
                val answer = http.post("$server/api/commands", headers, JSON.encodeToString(CommandBatch.serializer(), CommandBatch(waiting)))
                if (!answer.ok) return if (answer.code == 409 && serverMode(headers) == SyncState.MIRROR) backToMirroring() else failed(problem(answer.code))
                val outcomes = JSON.decodeFromString(ListSerializer(Outcome.serializer()), answer.body)
                // What a dropped command did here is in stores the server hasn't changed, so a pull of what's changed wouldn't take it
                // out: the next is of everything. A refused one's isn't a change lost, your screen having said what came of it, but it may
                // have gone otherwise here. Before it leaves the outbox, so a process stopped between the two hears it again.
                val dropped = outcomes.filter { it.state == Outcome.DROPPED }.map { outcome -> droppedLine(waiting.firstOrNull { it.id == outcome.id }, outcome.why ?: "It couldn't be applied") }
                if (dropped.isNotEmpty() || outcomes.any { it.state == Outcome.REFUSED }) sync.update { it.copy(dropped = (it.dropped + dropped).takeLast(DROPPED_KEPT), revision = 0) }
                val heard = outcomes.map { it.id }.toSet()
                stores.outbox.update { state -> state.copy(commands = state.commands.filterNot { it.id in heard }) }
            }
            val pulled = pull(server, headers, sync.value.revision) ?: return false
            if (pulled.mode == SyncState.MIRROR) return backToMirroring()
            if (pulled.revision < sync.value.revision) return failed(GONE_BACK)
            val here = stores.mirrored.associateBy { it.name }
            val taken = pulled.stores.filter { it.name in here || ChatArchiveStore.isMonth(it.name) }
            val newer = taken.filter { (here[it.name]?.version ?: stores.chatArchive.version) < it.version }.map { it.name }
            if (newer.isNotEmpty()) {
                sync.update { it.copy(refused = newer, lastError = "The server is ahead of this app: install the latest") }
                return false
            }
            // The chat's messages new to it, read in the same turn as it's taken, so none made here meanwhile is counted.
            var fresh: List<ChatMessage>? = null
            val adopt: suspend () -> Unit = {
                val before = stores.chat.value.messages.mapTo(HashSet()) { it.id }
                for (store in taken) {
                    val text = store.json.toString()
                    if (ChatArchiveStore.isMonth(store.name)) stores.chatArchive.adopt(store.name, store.version, text) else here.getValue(store.name).adopt(store.version, text)
                }
                if (taken.any { it.name == stores.chat.name }) fresh = stores.chat.value.messages.filter { it.id !in before }
                // What a clear or a reset made elsewhere took isn't kept here in a copy either (D-94): those not yet acted on, as a
                // pull of everything says them all, and a copy made here since one is what a migration that fails is put right from.
                stores.letGoOfCopiesOf(pulled.letGo.filterValues { it > sync.value.letGoSeen }.keys)
            }
            // A command made since this began: its turn first, and this pull's asked for again after it.
            if (commands == null) adopt() else if (!commands.replacing(adopt)) return true
            fresh?.let { runCatching { heard(it) }.onFailure { e -> Log.w(Log.TAG, "Hearing the chat failed: ${e::class.simpleName}") } }
            val seen = pulled.letGo.values.maxOrNull() ?: 0
            sync.update { it.copy(revision = pulled.revision, lastSyncAt = clock.now(), lastError = null, refused = emptyList(), letGoSeen = maxOf(it.letGoSeen, seen)) }
            return true
        } catch (failed: IOException) {
            Log.w(Log.TAG, "Keeping in step with the server failed: ${failed::class.simpleName}")
            return failed("The server can't be reached")
        }
    }

    /**
     * [photo]'s file to the server, by its hash, as a task's photo is kept there (D-148): whether it went, or needn't, its file here gone
     * or another photo since (one task's are kept under one name here), as the command then keeps a photo that's no more.
     */
    private fun sendTaskPhoto(server: String, headers: Map<String, String>, photo: TaskPhoto): Boolean {
        val jpeg = runCatching { File(photo.path).readBytes() }.getOrNull()?.takeIf { Photos.hashOf(it) == photo.hash } ?: return true
        return http.postBytes("$server/api/task-photos/${photo.hash}", headers + ("Content-Type" to "image/jpeg"), jpeg).ok
    }

    /** What's said of a command the server dropped: which it was and when, as a reason alone can't say what's to be done again. */
    private fun droppedLine(command: Command?, why: String): String = listOfNotNull(command?.let(::named), why).joinToString(": ")

    /** Which [command] is and when it was made, in words: "Spend free time, today 14:05". */
    fun named(command: Command): String {
        val name = command.action::class.simpleName.orEmpty().replace(WORD, " $0").trim().lowercase().replaceFirstChar(Char::uppercase)
        return "$name, ${Format.at(command.at, clock.now(), clock.zone())}"
    }

    /**
     * [id], the oldest change waiting, let go at your word: Setup's way out of a queue a change holds up that a release can't mend
     * soon. With no exchange under way, it's said among those the server didn't apply, and leaves the outbox, and the next pull is
     * of everything, so what it did here goes, as for one the server dropped. Whether it was still waiting.
     */
    suspend fun letGo(id: String): Boolean = sending.withLock {
        val command = stores.outbox.value.commands.firstOrNull { it.id == id } ?: return@withLock false
        // Said first, as a dropped one is: a process stopped between the two hears it again.
        sync.update { it.copy(dropped = (it.dropped + droppedLine(command, "Let go at your word")).takeLast(DROPPED_KEPT), revision = 0) }
        stores.outbox.update { state -> state.copy(commands = state.commands.filterNot { it.id == id }) }
        true
    }

    /**
     * [text], a backup, imported on the server, once the two keep in step by commands: an Import here alone would go at the next
     * pull. What's waiting goes first, so the backup's taken in after the changes made before it, and the next pull is of
     * everything, as the import changed stores the server hasn't said since. What the server said of it, or why nothing was. Off
     * the thread it's asked from, a screen's being the main one, which may make no call over the network.
     */
    suspend fun importOnServer(text: String): Asked = withContext(Dispatchers.IO) {
        sending.withLock {
            val token = stores.secrets[Secret.DeviceToken] ?: return@withLock Asked(false, "Nothing was restored: this phone isn't paired.")
            if (stores.outbox.value.commands.isNotEmpty() && (!exchange() || stores.outbox.value.commands.isNotEmpty())) {
                return@withLock Asked(false, "Nothing was restored: what's changed here hasn't reached the server yet (Setup says why).")
            }
            val headers = mapOf("Content-Type" to "application/octet-stream", "Authorization" to "Bearer $token", Protocol.HEADER to Protocol.VERSION.toString())
            val answer = try {
                http.post("${sync.value.server}/api/backup/import", headers, text)
            } catch (failed: IOException) {
                Log.w(Log.TAG, "Importing on the server failed: ${failed::class.simpleName}")
                return@withLock Asked(false, "Nothing was restored: the server can't be reached.")
            }
            if (!answer.ok) return@withLock Asked(false, "Nothing was restored: the server said no (${answer.code}).")
            sync.update { it.copy(revision = 0) }
            JSON.decodeFromString(Asked.serializer(), answer.body)
        }
    }

    /**
     * Your words waiting for Felix asked of him again, by the server, once the two keep in step by commands: Claude's the server's
     * to ask then (D-142). What's waiting goes first, your words perhaps among it; his answer comes as the server's changes do.
     * Whether the server heard. Off the thread it's asked from, as [importOnServer] is.
     */
    suspend fun askAgainOnServer(): Boolean = withContext(Dispatchers.IO) {
        sending.withLock {
            val token = stores.secrets[Secret.DeviceToken] ?: return@withLock false
            if (stores.outbox.value.commands.isNotEmpty() && !exchange()) return@withLock false
            val headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token", Protocol.HEADER to Protocol.VERSION.toString())
            try {
                http.post("${sync.value.server}/api/chat/try-again", headers, "").ok
            } catch (failed: IOException) {
                Log.w(Log.TAG, "Asking the server to try again failed: ${failed::class.simpleName}")
                false
            }
        }
    }

    /**
     * [jpeg], a photo of homework, read into tasks by the server, once the two keep in step by commands: Claude's the server's to ask
     * then (D-142). What it read, or why not. Off the thread it's asked from, as [importOnServer] is.
     */
    suspend fun readPhotoOnServer(jpeg: ByteArray): Pair<PhotoTasks.Reading?, String?> = withContext(Dispatchers.IO) {
        val token = stores.secrets[Secret.DeviceToken] ?: return@withContext null to "The photo couldn't be read (this phone isn't paired)."
        val headers = mapOf("Content-Type" to "image/jpeg", "Authorization" to "Bearer $token", Protocol.HEADER to Protocol.VERSION.toString())
        val answer = try {
            photoHttp.postBytes("${sync.value.server}/api/photos/tasks", headers, jpeg)
        } catch (failed: IOException) {
            Log.w(Log.TAG, "Reading a photo on the server failed: ${failed::class.simpleName}")
            return@withContext null to "The photo couldn't be read (the server can't be reached)."
        }
        // In the server's words where it gave them, as one refusal can mean more than one thing: it's mirroring, or reading another.
        if (!answer.ok) return@withContext null to "The photo couldn't be read (${said(answer.body)?.replaceFirstChar { it.lowercase() } ?: "the server said no, ${answer.code}"})."
        val read = JSON.decodeFromString(PhotoRead.serializer(), answer.body)
        // As the server's reader answered: why it read nothing, or what it read, perhaps no task at all, which the sheet says.
        read.why?.let { return@withContext null to it }
        PhotoTasks.Reading(read.tasks.map { it.task }, read.leftOut, read.question) to null
    }

    /**
     * [jpegs], the pages of [taskId]'s piece [key], checked by the server, once the two keep in step by commands, as Claude's the
     * server's to ask then (D-148): what came of it, in its words, or why it didn't go through. On [photoHttp], as a check waits on
     * Claude's call as a photo's reading does; off the thread it's asked from, as [importOnServer] is.
     */
    suspend fun checkPhotoOnServer(taskId: String, key: String, step: String?, takenAt: Long, jpegs: List<ByteArray>): String = withContext(Dispatchers.IO) {
        val token = stores.secrets[Secret.DeviceToken] ?: return@withContext "The check didn't go through (this phone isn't paired)."
        // What's waiting first, as the piece may be new, or changed, in it, so the server plans it as this phone does; not held through
        // the check, which can take minutes.
        val flushed = sending.withLock { stores.outbox.value.commands.isEmpty() || exchange() }
        if (!flushed) return@withContext "The check didn't go through (what's changed here hasn't reached the server yet)."
        val headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token", Protocol.HEADER to Protocol.VERSION.toString())
        val ask = PhotoCheckAsk(taskId, key, takenAt, jpegs.map { Base64.getEncoder().encodeToString(it) }, step)
        val answer = try {
            photoHttp.post("${sync.value.server}/api/photos/check", headers, JSON.encodeToString(PhotoCheckAsk.serializer(), ask))
        } catch (failed: IOException) {
            Log.w(Log.TAG, "Checking a photo on the server failed: ${failed::class.simpleName}")
            return@withContext "The check didn't go through (the server can't be reached)."
        }
        if (!answer.ok) return@withContext "The check didn't go through (${said(answer.body)?.replaceFirstChar { it.lowercase() } ?: "the server said no, ${answer.code}"})."
        JSON.decodeFromString(PhotoChecked.serializer(), answer.body).said
    }

    /**
     * Task [taskId] read again by Claude on the server, once the two keep in step by commands, as Claude's the server's to ask then
     * (D-142): begun there, its reading coming as it's pulled. What was said of it. Off the thread it's asked from, as [importOnServer] is.
     */
    suspend fun readAgainOnServer(taskId: String): String = withContext(Dispatchers.IO) {
        sending.withLock {
            val token = stores.secrets[Secret.DeviceToken] ?: return@withLock "The server couldn't be asked: this phone isn't paired."
            // What's waiting first, as the task may be new, or changed, in it: the server reads it as the sheet shows it.
            if (stores.outbox.value.commands.isNotEmpty() && !exchange()) return@withLock "The server couldn't be asked: what's changed here hasn't reached it yet (Setup says why)."
            val headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token", Protocol.HEADER to Protocol.VERSION.toString())
            val answer = try {
                // As a path's segment: a space is %20 there, not a form's +.
                http.post("${sync.value.server}/api/tasks/${URLEncoder.encode(taskId, Charsets.UTF_8).replace("+", "%20")}/read-again", headers, "")
            } catch (failed: IOException) {
                Log.w(Log.TAG, "Asking the server to read a task again failed: ${failed::class.simpleName}")
                return@withLock "The server couldn't be reached: try again later."
            }
            if (!answer.ok) return@withLock "The server couldn't be asked: ${said(answer.body)?.replaceFirstChar { it.lowercase() } ?: "it said no (${answer.code})"}."
            JSON.decodeFromString(Asked.serializer(), answer.body).said.orEmpty()
        }
    }

    /** The error a refusal's body names, or null where it names none. */
    private fun said(body: String): String? = runCatching { JSON.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()

    /** Every store changed on the server since [revision], or null, said, if it wouldn't answer. */
    private suspend fun pull(server: String, headers: Map<String, String>, revision: Long): PulledState? {
        val answer = http.get("$server/api/state?since=$revision", headers)
        if (!answer.ok) {
            failed(problem(answer.code))
            return null
        }
        return JSON.decodeFromString(PulledState.serializer(), answer.body)
    }

    private suspend fun failed(why: String): Boolean {
        sync.update { it.copy(lastError = why) }
        return false
    }

    /** Each store as it stands, the archive's [months] among them. */
    private fun texts(months: List<StoreText>): List<StoreText> = stores.mirrored.map { StoreText(it.name, it.version, it.text()) } +
        StoreText(CalendarRead.NAME, CALENDAR_VERSION, JSON.encodeToString(CalendarRead.serializer(), calendar())) + months

    /** Whether anything here isn't on the server, but what it's refused: that goes at the next send, not round again now. */
    private fun unsent(): Boolean {
        val state = sync.value
        val monthsWaiting = stores.chatArchive.changes.value != archiveSent && state.refused.none { ChatArchiveStore.isMonth(it) }
        return monthsWaiting || texts(emptyList()).any { it.name !in state.refused && state.sent[it.name] != Protocol.hash(it.text) }
    }

    private suspend fun send(): Sent {
        val token = stores.secrets[Secret.DeviceToken] ?: return Sent.Failed
        val archived = stores.chatArchive.changes.value
        val months = if (archived != archiveSent) months() else emptyList()
        val texts = texts(months)
        val hashes = texts.associate { it.name to Protocol.hash(it.text) }
        val holds = sync.value.sent
        val batch = SeedBatch(clock.zone().id, build, texts.filter { holds[it.name] != hashes[it.name] })
        val headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token", Protocol.HEADER to Protocol.VERSION.toString())
        val answer = try {
            http.post("${sync.value.server}/api/stores/seed", headers, JSON.encodeToString(SeedBatch.serializer(), batch))
        } catch (failed: IOException) {
            Log.w(Log.TAG, "Sending to the server failed: ${failed::class.simpleName}")
            sync.update { it.copy(lastError = "The server can't be reached") }
            return Sent.Failed
        }
        if (!answer.ok) {
            // The server's gone over to commands: this phone goes over with it, from what it's sent.
            if (answer.code == 409 && switched(headers)) return Sent.InStep
            sync.update { it.copy(lastError = problem(answer.code)) }
            return Sent.Failed
        }
        val seed = JSON.decodeFromString(SeedAnswer.serializer(), answer.body)
        sync.update { it.copy(sent = seed.holds, lastSyncAt = clock.now(), lastError = null, serverVersion = seed.server.ifEmpty { null }, refused = seed.refused, seedRevision = seed.revision) }
        val refused = seed.refused.toSet()
        val inStep = seed.holds - refused == (holds + hashes) - refused
        // The months aren't read while the archive stands still: they're read again where one's still to be taken, or the server's lost one.
        archiveSent = if (inStep && refused.none { ChatArchiveStore.isMonth(it) }) archived else NOT_SENT
        return if (inStep) Sent.InStep else Sent.Behind
    }

    /** The archive's months as they stand, one the server holds and this phone no longer does sent empty: a chat cleared here is cleared there. */
    private fun months(): List<StoreText> {
        val here = stores.chatArchive.texts()
        val gone = sync.value.sent.keys.filter { ChatArchiveStore.isMonth(it) } - here.map { it.name }.toSet()
        return here + gone.map { StoreText(it, ChatArchive().version, EMPTY_MONTH) }
    }

    private fun problem(code: Int): String = when (code) {
        401 -> "The server doesn't know this phone: pair it again"
        409 -> "The server needs a newer app: install the latest"
        else -> "The server said no ($code)"
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** [CalendarRead]'s version: it isn't a store of this phone's. */
        private val CALENDAR_VERSION = CalendarRead().version

        private const val NOT_SENT = -1L

        /** The commands said to have been dropped, kept for Setup. */
        private const val DROPPED_KEPT = 20

        /** The start of each word of an action's name. */
        private val WORD = Regex("[A-Z]")

        private const val GONE_BACK = "The server has gone back to an earlier copy: nothing's been taken from it. See its restore steps."

        /** The most sends one upload makes: the first, and two to catch what it left. */
        private const val ROUNDS = 3

        private val EMPTY_MONTH = JSON.encodeToString(ChatArchive.serializer(), ChatArchive())

        /** How long a photo's reading, or its check, is waited for: as long as Claude's call on the server may take, and a minute over. */
        val PHOTO_WAIT_MS: Int = maxOf(ModelEffort.timeout(ModelEffort.Job.PhotoToTask), ModelEffort.timeout(ModelEffort.Job.PhotoCheck)).plusMinutes(1).toMillis().toInt()
    }
}
