package com.thomaswcode.decrastination.ports

/**
 * The log for code that runs on any platform: Android's on the phone, the server's own there ([sink], set
 * as each starts); nothing till it's set, as in a test. Its calls are the ones `android.util.Log` has.
 */
object Log {
    /** The tag every line goes under: `adb logcat -s Decrastination` on the phone. */
    const val TAG = "Decrastination"

    /** How much a line matters. */
    enum class Level { Debug, Info, Warn, Error }

    /** Where the lines go. */
    fun interface Sink {
        fun write(level: Level, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    var sink: Sink? = null

    fun d(tag: String, message: String, error: Throwable? = null) = sink?.write(Level.Debug, tag, message, error)

    fun i(tag: String, message: String, error: Throwable? = null) = sink?.write(Level.Info, tag, message, error)

    fun w(tag: String, message: String, error: Throwable? = null) = sink?.write(Level.Warn, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = sink?.write(Level.Error, tag, message, error)
}
