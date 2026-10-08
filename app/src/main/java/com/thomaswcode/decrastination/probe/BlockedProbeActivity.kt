package com.thomaswcode.decrastination.probe

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What a blocked app is replaced with. In Phase 0 it only says so; Phase 3's version shows the
 * next task. Back and the button both go to the home screen, never back to the blocked app.
 */
class BlockedProbeActivity : ComponentActivity() {

    private var latencyLogged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { goHome() }
        setContent {
            ProbeTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Not now", style = MaterialTheme.typography.displaySmall)
                        Text(
                            "Decrastination blocked ${intent.getStringExtra(EXTRA_PACKAGE) ?: "this app"}. " +
                                "(Phase 0 probe: YouTube is always blocked while the focus service is on.)",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = ::goHome) { Text("Go to the home screen") }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        latencyLogged = false
    }

    override fun onResume() {
        super.onResume()
        val eventTime = intent.getLongExtra(EXTRA_EVENT_TIME, 0L)
        if (!latencyLogged && eventTime > 0) {
            latencyLogged = true
            ProbeLog.add("Block screen showing ${SystemClock.uptimeMillis() - eventTime} ms after the blocked app came to the front")
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "blocked_package"

        /** The blocking event's time, on the uptime clock, to measure how long the cover took. */
        const val EXTRA_EVENT_TIME = "event_time"
    }
}
