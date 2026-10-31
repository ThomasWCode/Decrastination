package com.thomaswcode.decrastination.ui

import android.app.Activity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.About

/**
 * Felix on every screen (UI-16): his chat, about [about] (the task a screen is about, if it's one),
 * from a button of Material's standard size, 56 dp (the small one was 40), wearing
 * how many of his messages are unread (docs/spec/felix.md §Who he is). From the block screen ([back]),
 * a chat with a Back arrow in place of the Plan button.
 */
@Composable
fun FelixFab(activity: Activity, about: About = About(), modifier: Modifier = Modifier, back: Boolean = false) {
    val graph = AppGraph.get(activity)
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val unread by graph.conversation.unread.collectAsStateWithLifecycle(0)
    FloatingActionButton(onClick = { activity.startActivity(AskActivity.intent(activity, about, back = back)) }, modifier = modifier) {
        BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
            Icon(painterResource(R.drawable.ic_chat), contentDescription = if (unread > 0) "${settings.felixName}, $unread unread" else settings.felixName, modifier = Modifier.size(28.dp))
        }
    }
}

/**
 * *Ask Felix about it* on a sheet (UI-16): his chat, about [about], nothing sent, wearing how many of his
 * messages are unread, as his button does elsewhere.
 */
@Composable
fun AskFelixButton(activity: Activity, about: About) {
    val graph = AppGraph.get(activity)
    val unread by graph.conversation.unread.collectAsStateWithLifecycle(0)
    OutlinedButton(onClick = { activity.startActivity(AskActivity.intent(activity, about)) }) {
        Text("Ask ${graph.settings.value.felixName} about it")
        if (unread > 0) Badge(Modifier.padding(start = 8.dp)) { Text("$unread") }
    }
}

/** How much room [FelixFab] takes at a screen's foot, with its margin: what a list leaves clear under its last row. */
val FELIX_ROOM = 88.dp
