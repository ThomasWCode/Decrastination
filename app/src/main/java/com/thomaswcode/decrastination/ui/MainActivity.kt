package com.thomaswcode.decrastination.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.sync.SyncWorker

/** The app's screens: the plan, the tasks every source lists, and the setup checklist. */
class MainActivity : ComponentActivity() {

    /** A tab asked for by an intent that reached this activity already open (it's singleTop). */
    private val askedTab = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        setContent { AppTheme { Main(graph) } }
    }

    override fun onResume() {
        super.onResume()
        // Back from notification settings, say: alerts that couldn't be shown before can be now.
        AppGraph.get(this).reconcileAlerts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) askedTab.value = intent.getIntExtra(EXTRA_TAB, 0)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Main(graph: AppGraph) {
        var tab by rememberSaveable { mutableIntStateOf(intent.getIntExtra(EXTRA_TAB, 0)) }
        askedTab.value?.let { asked ->
            LaunchedEffect(asked) {
                tab = asked
                askedTab.value = null
            }
        }
        val syncs by remember { WorkManager.getInstance(this).getWorkInfosForUniqueWorkFlow(SyncWorker.NOW) }
            .collectAsStateWithLifecycle(emptyList())
        val syncing = syncs.any { !it.state.isFinished }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Decrastination") },
                    actions = {
                        IconButton(enabled = !syncing, onClick = { SyncWorker.syncNow(this@MainActivity) }) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Sync now")
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                PrimaryTabRow(selectedTabIndex = tab) {
                    TABS.forEachIndexed { i, name -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) }) }
                }
                when (tab) {
                    0 -> TodayScreen(graph, this@MainActivity)
                    1 -> TasksScreen(graph)
                    2 -> StatsScreen(graph)
                    else -> SetupScreen(graph, this@MainActivity)
                }
            }
        }
    }

    companion object {
        /** The tab to open on: [TAB_SETUP] from a protection alert, [TAB_TASKS] from a dropped plan's. */
        const val EXTRA_TAB = "tab"
        const val TAB_TASKS = 1
        const val TAB_SETUP = 3
        private val TABS = listOf("Plan", "Tasks", "Stats", "Setup")
    }
}
