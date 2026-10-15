package com.thomaswcode.decrastination.data

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * One piece of the app's state, kept in a JSON file and exposed as a [StateFlow], the way the
 * Teams widget keeps its own (its `AssignmentStore`).
 *
 * Everything that reads or changes it (the sync, the focus service, the widget, the screens)
 * runs in the app's one process and shares one instance. [update] serialises changes; each is
 * written to a temp file that is then renamed over the real one, so a crash mid-write can't leave
 * a half-written file. A file that can't be read whole (IMP-P1-003) keeps what it can: each of its
 * fields that reads on its own (a field an app update can no longer read loses only itself), the
 * rest from [unreadable]; the file as it was is set aside as `<name>.unreadable-<when>`, the last
 * few of those kept, and [problem] says so. One bad write never stops the app.
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
    /** Tidies a state as loaded: drops what has expired, fixes what a crash left half done. */
    private val onLoad: (T) -> T = { it },
    /** What a file that can't be read starts from, under what can be kept of it: [default], unless it's safer otherwise. */
    private val unreadable: () -> T = default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** Why the file couldn't be read whole at start, and where it was set aside; null if it could. */
    var problem: Unreadable? = null
        private set

    /**
     * What was kept of a file that didn't read whole is still to replace it ([keepRecovered]). Before
     * the state below, as loading it sets this.
     */
    private var unsaved = false

    private val _state = MutableStateFlow(load())
    val state: StateFlow<T> = _state.asStateFlow()

    val value: T get() = _state.value

    /** Applies [transform] and saves the result, if it changed anything. Returns the new state. */
    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        val next = transform(_state.value)
        if (next != _state.value) {
            withContext(Dispatchers.IO) { write(next) }
            _state.value = next
            unsaved = false
        }
        next
    }

    /**
     * Writes what was kept of a file that didn't read whole over it, now that its [problem] is on
     * record: read again at the next start, it would be found unreadable again, and said again,
     * however often you'd seen it. Till then it's left as it was, so a start cut short first finds
     * it, and says it, again.
     */
    suspend fun keepRecovered() {
        mutex.withLock {
            if (unsaved && withContext(Dispatchers.IO) { runCatching { write(_state.value) } }.isSuccess) unsaved = false
        }
    }

    private fun load(): T {
        if (!file.exists()) return default()
        val text = runCatching { file.readText() }.getOrNull()
        val loaded = text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
        if (loaded != null) return onLoad(loaded)
        // Set aside under its own name, so a second time can't overwrite the first; not again where
        // it's there already as it is (a start cut short before it was said).
        val aside = alreadyAside(file) ?: setAside(file, clock())
        val salvaged = text?.let(::salvage)
        problem = Unreadable(file.name, aside?.name, kept = salvaged != null, at = clock())
        // What was kept replaces it once it's safe beside it, and said ([keepRecovered]). Only where it
        // was read and couldn't be decoded, which would happen again: not where reading it failed
        // (that can pass, and the file is then still good), nor where the copy failed (then the bad
        // file is all there is of it).
        unsaved = text != null && aside != null
        return onLoad(salvaged ?: unreadable())
    }

    /**
     * What can be kept of [text], JSON that doesn't read whole: each top-level field that reads
     * over [unreadable]'s, in turn. Null when it isn't a JSON object, or keeps nothing.
     */
    private fun salvage(text: String): T? {
        val found = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val base = json.encodeToJsonElement(serializer, unreadable()).jsonObject
        var kept = base
        for ((field, value) in found) {
            val tried = JsonObject(kept + (field to value))
            if (runCatching { json.decodeFromJsonElement(serializer, tried) }.isSuccess) kept = tried
        }
        return if (kept == base) null else json.decodeFromJsonElement(serializer, kept)
    }

    private fun write(value: T) {
        val dir = requireNotNull(file.absoluteFile.parentFile) { "A store needs a parent directory" }
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        // Windows (the JVM tests on a PC) can refuse the replace for a moment while something else
        // has the file open (a virus scanner reading what was just written): a few tries more.
        var tries = 0
        while (true) {
            try {
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                return
            } catch (refused: AccessDeniedException) {
                if (++tries >= MOVE_TRIES) throw refused
                Thread.sleep(MOVE_WAIT_MS * tries)
            }
        }
    }

    /** A file that couldn't be read whole: its name, its copy's (null if even that failed), and whether some of it was kept. */
    @Serializable
    data class Unreadable(val file: String, val copy: String?, val kept: Boolean, val at: Long) {
        /** What it means, in words, for Setup. */
        val says: String
            get() {
                val what = when (file) {
                    "secrets.bin" -> return "The saved passwords and keys couldn't be decrypted (Android's keystore lost their key): enter them again here; " +
                        "without your dad's authenticator, loosening waits the full delay till protection is armed again with a new one" + copied
                    "settings.json" -> "Settings"
                    "tasks.json" -> "The task list"
                    "runtime.json" -> "The app's running state (free time, today's slots, calendar answers)"
                    "log.json" -> "The activity log"
                    "instructions.json" -> "Your instructions"
                    else -> file
                }
                val how = if (kept) "couldn't be read whole: what could be was kept, the rest is back to its defaults" else "couldn't be read: it started afresh"
                // Settings unreadable stay armed while the device admin is on (only arming turns it on).
                val armed = if (file == "settings.json") ". Protection stays armed if it was" else ""
                return "$what $how$armed$copied"
            }

        private val copied: String get() = copy?.let { " (the file as it was is kept as $it)" } ?: ""
    }

    companion object {
        private const val MOVE_TRIES = 5
        private const val MOVE_WAIT_MS = 20L

        /** Copies set aside of each file: the newest so many. */
        const val COPIES_KEPT = 3

        /** A copy set aside of [file] that holds just what it holds now, if there's one: it's been kept, and said, already. */
        fun alreadyAside(file: File): File? {
            val dir = file.absoluteFile.parentFile ?: return null
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
            return dir.listFiles { f -> f.name.startsWith("${file.name}.unreadable-") }.orEmpty()
                .firstOrNull { copy -> runCatching { copy.readBytes().contentEquals(bytes) }.getOrDefault(false) }
        }

        /**
         * [file] copied to `<name>.unreadable-<when>` beside it, keeping the newest [COPIES_KEPT] of
         * them. Returns the copy, or null if it couldn't be made.
         */
        fun setAside(file: File, now: Long): File? {
            val dir = file.absoluteFile.parentFile ?: return null
            val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), java.time.ZoneOffset.UTC))
            var copy = File(dir, "${file.name}.unreadable-$stamp")
            var n = 1
            while (copy.exists()) copy = File(dir, "${file.name}.unreadable-$stamp-${n++}")
            if (runCatching { file.copyTo(copy) }.isFailure) return null
            // This one always kept, and the newest of the others: by name, which a clock set back
            // would put this one behind.
            dir.listFiles { f -> f.name.startsWith("${file.name}.unreadable-") && f.name != copy.name }.orEmpty()
                .sortedByDescending { it.name }.drop(COPIES_KEPT - 1).forEach { it.delete() }
            return copy
        }

        /** Unknown keys are ignored and defaults written, so a newer or older file still loads. */
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }
}
