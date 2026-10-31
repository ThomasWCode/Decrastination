package com.thomaswcode.decrastination

import android.util.Log
import com.thomaswcode.decrastination.ports.Log as EngineLog

/** The engine's log ([EngineLog]) as Android's. */
object AndroidLog : EngineLog.Sink {
    override fun write(level: EngineLog.Level, tag: String, message: String, error: Throwable?) {
        when (level) {
            EngineLog.Level.Debug -> Log.d(tag, message, error)
            EngineLog.Level.Info -> Log.i(tag, message, error)
            EngineLog.Level.Warn -> Log.w(tag, message, error)
            EngineLog.Level.Error -> Log.e(tag, message, error)
        }
    }
}
