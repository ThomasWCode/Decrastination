package com.thomaswcode.decrastination.data

import java.io.File
import java.time.Instant

/**
 * The app's crashes (AR-06): each written down as it happens, under `crashes/` in the app's files,
 * the last [KEEP] kept, the last shown in Settings → Claude to copy.
 */
object Crashes {
    /** The most kept. */
    const val KEEP = 3

    private const val DIR = "crashes"

    /**
     * [error] on [thread] written down at [at]: when, which thread, the app's [version] and the
     * stack; those beyond [KEEP] let go, the oldest first. Never throws: it runs as the app goes down.
     */
    fun record(filesDir: File, thread: Thread, error: Throwable, version: String, at: Long = System.currentTimeMillis()) {
        runCatching {
            val dir = File(filesDir, DIR).apply { mkdirs() }
            File(dir, "$at.txt").writeText(text(at, thread.name, error, version))
            files(filesDir).drop(KEEP).forEach { it.delete() }
        }
    }

    /** A crash as it's written: its time, the thread, the version, then the stack. */
    fun text(at: Long, thread: String, error: Throwable, version: String): String =
        "${Instant.ofEpochMilli(at)} on $thread, version $version\n\n${error.stackTraceToString()}"

    /** The last crash written down, or null if none is. */
    fun last(filesDir: File): String? = files(filesDir).firstOrNull()?.let { runCatching { it.readText() }.getOrNull() }

    /** The crashes written down, newest first. */
    private fun files(filesDir: File): List<File> =
        File(filesDir, DIR).listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedByDescending { it.name.removeSuffix(".txt").toLongOrNull() ?: 0L }
}
