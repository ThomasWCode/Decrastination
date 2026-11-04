package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.GmailWrites
import kotlinx.coroutines.delay

/** The bar's red mark while Setup needs you, opening it (UI-18). */
@Composable
fun SetupMark(graph: AppGraph, activity: Activity) {
    val tasks by graph.tasks.state.collectAsStateWithLifecycle()
    val runtime by graph.runtime.state.collectAsStateWithLifecycle()
    val model by graph.stores.model.state.collectAsStateWithLifecycle()
    val device by graph.stores.device.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val gmail by graph.gmailFinishes.state.collectAsStateWithLifecycle()
    val sync by graph.replica.state.collectAsStateWithLifecycle()
    val outbox by graph.stores.outbox.state.collectAsStateWithLifecycle()
    // With the clock as the Day's line is, so a change held too long, or a week adrift, shows with no store changing.
    val now by produceState(graph.clock.now()) {
        while (true) {
            delay(60_000)
            value = graph.clock.now()
        }
    }
    // What Setup would show in red, as a mark that opens it (UI-18): a source failing, Claude's key,
    // protection, a file unreadable, a write to Gmail given up, the server out of step for long (the Day's line); and, the first
    // run, no source read yet.
    val attention = SetupPage.attention(
        tasks,
        gmailGivenUp = GmailWrites.givenUp(gmail.writes).isNotEmpty(),
        keyProblem = model.aiUsage.keyProblem != null && settings.aiEnabled && settings.aiKeyActive,
        watchdog = device.watchdog.problems.isNotEmpty(),
        unreadable = runtime.unreadable.isNotEmpty(),
        adrift = SetupPage.syncLine(sync, outbox.commands.firstOrNull()?.at, graph.replica.paired, now, graph.clock.zone()) != null,
    )
    if (attention) {
        IconButton(onClick = { activity.startActivity(Intent(activity, SetupActivity::class.java)) }) {
            BadgedBox(badge = { Badge() }) { Icon(painterResource(R.drawable.ic_error), contentDescription = "Setup needs you") }
        }
    }
}
