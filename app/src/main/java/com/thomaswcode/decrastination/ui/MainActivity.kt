package com.thomaswcode.decrastination.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.BuildConfig
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.sync.SyncWorker

/**
 * The app's screens over the chat, its home (D-121): the plan, the tasks every source lists, and the
 * stats, opened from the chat's Plan button. Setup, settings, protection and the week's check-in are
 * out of the way in the menu, which shows a dot when something there needs you. The launcher's entry
 * still, as the home-screen icon names it: a launcher start hands over to the chat ([Home.start]).
 */
class MainActivity : ComponentActivity() {

    /** A tab asked for by an intent that reached this activity already open (it's singleTop). */
    private val askedTab = mutableStateOf<Int?>(null)

    /** A task to open in Tasks ([EXTRA_TASK]), till Tasks has opened it. */
    private val askedTask = mutableStateOf<String?>(null)

    /** Back at the task's root opens the chat in Day's place (Q116); over the chat, it closes Day as usual. */
    private val backToChat = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            startActivity(AskActivity.intent(this@MainActivity))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The launcher's start hands over to the chat before anything's drawn, so Day never shows first (D-121).
        val start = Home.start(
            launcher = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER),
            root = isTaskRoot,
            restored = savedInstanceState != null,
            asked = intent.hasExtra(EXTRA_TAB) || intent.hasExtra(EXTRA_TASK),
        )
        if (start != Home.Start.Stay) {
            if (start == Home.Start.Chat) startActivity(AskActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(this, backToChat)
        val graph = AppGraph.get(this)
        if (savedInstanceState == null) {
            openSetupIfAsked(intent)
            askedTask.value = intent.getStringExtra(EXTRA_TASK)
        }
        setContent { AppTheme { Main(graph) } }
    }

    /** An alert about setup opens it over the plan, so Back comes to the plan. */
    private fun openSetupIfAsked(intent: Intent) {
        if (intent.getIntExtra(EXTRA_TAB, 0) != OPEN_SETUP) return
        intent.removeExtra(EXTRA_TAB)
        startActivity(Intent(this, SetupActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        backToChat.isEnabled = Home.backOpensChat(isTaskRoot)
        // Back from notification settings, say: alerts that couldn't be shown before can be now.
        AppGraph.get(this).gateway.reconcileAlerts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSetupIfAsked(intent)
        if (intent.hasExtra(EXTRA_TAB)) askedTab.value = intent.getIntExtra(EXTRA_TAB, 0)
        intent.getStringExtra(EXTRA_TASK)?.let { askedTask.value = it }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Main(graph: AppGraph) {
        var tab by rememberSaveable { mutableIntStateOf(intent.getIntExtra(EXTRA_TAB, 0).coerceIn(TABS.indices)) }
        askedTab.value?.let { asked ->
            LaunchedEffect(asked) {
                tab = asked
                askedTab.value = null
            }
        }
        val syncs by remember { WorkManager.getInstance(this).getWorkInfosForUniqueWorkFlow(SyncWorker.NOW) }
            .collectAsStateWithLifecycle(emptyList())
        val syncing = syncs.any { !it.state.isFinished }
        var menu by remember { mutableStateOf(false) }
        // The very first email finished here waits for your OK: asked over whichever tab is open.
        GmailOkSheet(graph)
        Scaffold(
            // Felix on every screen (UI-16): Work has him above its New task.
            floatingActionButton = { if (tab != 1) FelixFab(this@MainActivity) },
            topBar = {
                TopAppBar(
                    title = { Text("Decrastination", fontWeight = FontWeight.Bold) },
                    actions = {
                        SetupMark(graph, this@MainActivity)
                        IconButton(enabled = !syncing, onClick = { SyncWorker.syncNow(this@MainActivity) }) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Sync now")
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Menu")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                MENU.forEach { (label, screen) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            menu = false
                                            startActivity(Intent(this@MainActivity, screen))
                                        },
                                    )
                                }
                            }
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
                    0 -> DayScreen(graph, this@MainActivity)
                    1 -> WorkScreen(graph, this@MainActivity, askedTask.value) { askedTask.value = null }
                    else -> StatsScreen(graph)
                }
            }
        }
    }

    companion object {
        /**
         * The tab to open on: [TAB_TASKS] from a dropped plan's alert; or [OPEN_SETUP], Setup over the plan, from a
         * protection or key alert. Asked for, a launcher start stays here rather than going to the chat ([Home.start]).
         */
        const val EXTRA_TAB = "tab"

        /** A task to open in Tasks, by id: one of your own's Open. */
        const val EXTRA_TASK = "task"
        const val TAB_TASKS = 1
        const val OPEN_SETUP = 3
        /** Day is when, Work is what (UI-17), over the chat, the app's home. */
        private val TABS = listOf("Day", "Work", "Stats")

        /**
         * The menu (UI-18), on Day's bar and under the chat's own items: the week, the reviews (LR-01), and
         * Settings, which has every page Setup and Protection had. Setup itself is the attention mark's.
         */
        internal val MENU = listOfNotNull(
            "This week" to CheckInActivity::class.java,
            "Reviews" to ReviewsActivity::class.java,
            "Settings" to SettingsActivity::class.java,
            // The debug page (AR-03), on a debug build.
            ("Debug" to DebugActivity::class.java).takeIf { BuildConfig.DEBUG },
        )
    }
}
