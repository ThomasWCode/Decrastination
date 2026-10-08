package com.thomaswcode.decrastination.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One piece of the app's state, kept in a JSON file and exposed as a [StateFlow], the way the
 * Teams widget keeps its own (its `AssignmentStore`).
 *
 * Everything that reads or changes it (the sync, the focus service, the widget, the screens)
 * runs in the app's one process and shares one instance. [update] serialises changes; each is
 * written to a temp file that is then renamed over the real one, so a crash mid-write can't leave
 * a half-written file. A file that can't be read is set aside as `<name>.unreadable` and the
 * state starts from [default], so one bad write never stops the app.
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
    /** Tidies a state as loaded: drops what has expired, fixes what a crash left half done. */
    private val onLoad: (T) -> T = { it },
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<T> = _state.asStateFlow()

    val value: T get() = _state.value

    /** Applies [transform] and saves the result, if it changed anything. Returns the new state. */
    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        val next = transform(_state.value)
        if (next != _state.value) {
            withContext(Dispatchers.IO) { write(next) }
            _state.value = next
        }
        next
    }

    private fun load(): T {
        if (!file.exists()) return default()
        val loaded = runCatching { json.decodeFromString(serializer, file.readText()) }
        loaded.exceptionOrNull()?.let {
            runCatching { file.copyTo(File(file.path + ".unreadable"), overwrite = true) }
        }
        return onLoad(loaded.getOrNull() ?: default())
    }

    private fun write(value: T) {
        val dir = requireNotNull(file.absoluteFile.parentFile) { "A store needs a parent directory" }
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** Unknown keys are ignored and defaults written, so a newer or older file still loads. */
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }
}
