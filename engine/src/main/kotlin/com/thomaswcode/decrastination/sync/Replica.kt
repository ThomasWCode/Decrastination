package com.thomaswcode.decrastination.sync

import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.ChatArchiveStore
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.data.SyncState
import com.thomaswcode.decrastination.net.Http
import com.thomaswcode.decrastination.ports.Log
import java.io.IOException
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.Commands
import com.thomaswcode.decrastination.sync.protocol.Outcome
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
    /** Where a pull goes through: no command applied half-way, and those waiting applied again on top. */
    private val commands: Commands? = null,
) {
    private val sync: JsonStore<SyncState> = stores.sync

    /** One at a time: a screen's, a job's. */
    private val sending = Mutex()

    /** The archive's change count as last in step, to save reading its months where none has changed. */
    @Volatile
    private var archiveSent = NOT_SENT

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
        sync.update { it.copy(deviceId = token.id, sent = emptyMap(), lastError = null, refused = emptyList()) }
        return null
    }

    /** This phone paired no more: its token let go. Nothing else changes, and pairing again sends everything. */
    suspend fun forget() {
        stores.secrets.put(Secret.DeviceToken, null)
        sync.update { SyncState(server = it.server) }
        archiveSent = NOT_SENT
    }

    /** The server's address, for a test on a PC. */
    suspend fun server(url: String) {
        sync.update { it.copy(server = url.trimEnd('/')) }
    }

    /** What the server doesn't hold, sent. Whether it took it: a failure's kept for Setup, and tried again next time. */
    suspend fun upload(): Boolean = sending.withLock {
        if (sync.value.mode == SyncState.COMMANDS) return@withLock exchange()
        // Again where a send leaves something behind: the server holds less than it should (it's been restored, say), or a store
        // changed while it went, which work already running can't be asked for a second time.
        var sent = send()
        var rounds = 1
        while (rounds < ROUNDS && (sent == Sent.Behind || (sent == Sent.InStep && unsent()))) {
            sent = send()
            rounds++
        }
        sent != Sent.Failed
    }

    private enum class Sent { InStep, Behind, Failed }

    /** Whether the server says it now applies commands: this phone then keeps in step that way, at its next upload. */
    private suspend fun switched(headers: Map<String, String>): Boolean {
        val mode = try {
            val answer = http.get("${sync.value.server}/api/version", headers)
            if (answer.ok) JSON.decodeFromString(ServerVersion.serializer(), answer.body).mode else null
        } catch (failed: IOException) {
            Log.w(Log.TAG, "Asking the server its mode failed: ${failed::class.simpleName}")
            null
        }
        if (mode != SyncState.COMMANDS) return false
        sync.update { it.copy(mode = SyncState.COMMANDS, sent = emptyMap(), lastError = null) }
        return true
    }

    /**
     * The commands waiting sent, in order, and the stores changed since the last pull taken in their place (docs/spec/sync.md).
     * What's been answered leaves the outbox; a command the server dropped is said. A pull that follows no answer isn't made, and
     * one that finds a command made meanwhile isn't taken: stores are replaced only with nothing waiting ([Commands.replacing]).
     * A store newer than this build can read stops the pull whole: the stores change together, and half of them new is worse than
     * all of them old.
     */
    private suspend fun exchange(): Boolean {
        val token = stores.secrets[Secret.DeviceToken] ?: return false
        val headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token", Protocol.HEADER to Protocol.VERSION.toString())
        val server = sync.value.server
        val waiting = stores.outbox.value.commands.map { it.command }
        try {
            if (waiting.isNotEmpty()) {
                val answer = http.post("$server/api/commands", headers, JSON.encodeToString(CommandBatch.serializer(), CommandBatch(waiting)))
                if (!answer.ok) return failed(problem(answer.code))
                val outcomes = JSON.decodeFromString(ListSerializer(Outcome.serializer()), answer.body)
                val heard = outcomes.map { it.id }.toSet()
                stores.outbox.update { state -> state.copy(commands = state.commands.filterNot { it.command.id in heard }) }
                val dropped = outcomes.filter { it.state == Outcome.DROPPED }.map { it.why ?: "It couldn't be applied" }
                if (dropped.isNotEmpty()) sync.update { it.copy(dropped = (it.dropped + dropped).takeLast(DROPPED_KEPT)) }
            }
            val answer = http.get("$server/api/state?since=${sync.value.revision}", headers)
            if (!answer.ok) return failed(problem(answer.code))
            val pulled = JSON.decodeFromString(PulledState.serializer(), answer.body)
            val here = stores.mirrored.associateBy { it.name }
            val taken = pulled.stores.filter { it.name in here || ChatArchiveStore.isMonth(it.name) }
            val newer = taken.filter { (here[it.name]?.version ?: stores.chatArchive.version) < it.version }.map { it.name }
            if (newer.isNotEmpty()) {
                sync.update { it.copy(refused = newer, lastError = "The server is ahead of this app: install the latest") }
                return false
            }
            val adopt: suspend () -> Unit = {
                for (store in taken) {
                    val text = store.json.toString()
                    if (ChatArchiveStore.isMonth(store.name)) stores.chatArchive.adopt(store.name, store.version, text) else here.getValue(store.name).adopt(store.version, text)
                }
            }
            // A command made since this began: its turn first, and this pull's asked for again after it.
            if (commands == null) adopt() else if (!commands.replacing(adopt)) return true
            sync.update { it.copy(revision = pulled.revision, lastSyncAt = clock.now(), lastError = null, refused = emptyList()) }
            return true
        } catch (failed: IOException) {
            Log.w(Log.TAG, "Keeping in step with the server failed: ${failed::class.simpleName}")
            return failed("The server can't be reached")
        }
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
        sync.update { it.copy(sent = seed.holds, lastSyncAt = clock.now(), lastError = null, serverVersion = seed.server.ifEmpty { null }, refused = seed.refused) }
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

        /** The most sends one upload makes: the first, and two to catch what it left. */
        private const val ROUNDS = 3

        private val EMPTY_MONTH = JSON.encodeToString(ChatArchive.serializer(), ChatArchive())
    }
}
