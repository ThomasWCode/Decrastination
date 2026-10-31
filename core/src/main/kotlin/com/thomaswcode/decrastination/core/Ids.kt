package com.thomaswcode.decrastination.core

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A command being applied (docs/spec/sync.md): its moment, and ids drawn from its own, so that the phone and the server,
 * each applying it through the same owners, read the same time and make the same ids. Held by the thread; `Commands` in
 * `:engine` carries it along with the coroutine.
 */
class Replay(val at: Long, private val seed: String) {
    private val drawn = AtomicInteger()

    fun nextId(): String = UUID.nameUUIDFromBytes("$seed#${drawn.getAndIncrement()}".toByteArray()).toString()

    companion object {
        val current = ThreadLocal<Replay?>()
    }
}

/** Ids for what's made: a command's own while one's applied ([Replay]), or random. */
object Ids {
    fun next(): String = Replay.current.get()?.nextId() ?: UUID.randomUUID().toString()
}

/** [inner], reading a command's own moment while one's applied ([Replay]), so what it records is when it was done, not when it arrived. */
class ReplayClock(private val inner: WallClock) : WallClock {
    override fun now(): Long = Replay.current.get()?.at ?: inner.now()

    override fun zone() = inner.zone()

    override fun uptime() = inner.uptime()
}
