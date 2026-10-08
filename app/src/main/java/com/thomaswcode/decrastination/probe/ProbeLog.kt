package com.thomaswcode.decrastination.probe

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** What the probes found: to logcat (`adb logcat -s Decrastination`) and to the probe screen. */
object ProbeLog {
    const val TAG = "Decrastination"
    private const val KEPT = 300
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(line: String) {
        Log.i(TAG, line)
        _lines.update { (it + "${LocalTime.now().format(time)}  $line").takeLast(KEPT) }
    }
}
