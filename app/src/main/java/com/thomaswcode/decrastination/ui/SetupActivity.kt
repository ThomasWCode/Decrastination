package com.thomaswcode.decrastination.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.thomaswcode.decrastination.AppGraph

/** The setup checklist on its own page, from the main screen's menu or an alert about it. */
class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        setContent {
            AppTheme {
                Scaffold(topBar = { BackBar("Setup", this) }) { padding ->
                    Box(Modifier.padding(padding)) { SetupScreen(graph, this@SetupActivity) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from notification settings: alerts that couldn't be shown before can be now.
        AppGraph.get(this).reconcileAlerts()
    }
}
