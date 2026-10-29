package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.GmailWrites
import com.thomaswcode.decrastination.core.Source

/** The bar's red mark while Setup needs you, opening it (UI-18). */
@Composable
fun SetupMark(graph: AppGraph, activity: Activity) {
    val tasks by graph.tasks.state.collectAsStateWithLifecycle()
    val runtime by graph.runtime.state.collectAsStateWithLifecycle()
    val model by graph.stores.model.state.collectAsStateWithLifecycle()
    val protection by graph.stores.protection.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val gmail by graph.gmailFinishes.state.collectAsStateWithLifecycle()
    // What Setup would show in red, as a mark that opens it (UI-18): a source failing, Claude's key,
    // protection, a file unreadable, a write to Gmail given up; and, the first run, no source read yet.
    val attention = Source.SYNCED.any { tasks.status(it).error != null } || GmailWrites.givenUp(gmail.writes).isNotEmpty() ||
        (model.aiUsage.keyProblem != null && settings.aiEnabled && settings.aiKeyActive) ||
        protection.protection.problems.isNotEmpty() || runtime.unreadable.isNotEmpty() ||
        Source.SYNCED.all { tasks.status(it).lastSuccessAt == null && tasks.status(it).error == null }
    if (attention) {
        IconButton(onClick = { activity.startActivity(Intent(activity, SetupActivity::class.java)) }) {
            BadgedBox(badge = { Badge() }) { Icon(painterResource(R.drawable.ic_error), contentDescription = "Setup needs you") }
        }
    }
}
