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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        // Once more where the answer says the server holds less than it should: it's been restored, say.
        when (send()) {
            Sent.Failed -> false
            Sent.InStep -> true
            Sent.Behind -> send() != Sent.Failed
        }
    }

    private enum class Sent { InStep, Behind, Failed }

    private suspend fun send(): Sent {
        val token = stores.secrets[Secret.DeviceToken] ?: return Sent.Failed
        val archived = stores.chatArchive.changes.value
        val months = if (archived != archiveSent) months() else emptyList()
        val texts = stores.mirrored.map { StoreText(it.name, it.version, it.text()) } +
            StoreText(CalendarRead.NAME, CALENDAR_VERSION, JSON.encodeToString(CalendarRead.serializer(), calendar())) + months
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

        private val EMPTY_MONTH = JSON.encodeToString(ChatArchive.serializer(), ChatArchive())
    }
}
