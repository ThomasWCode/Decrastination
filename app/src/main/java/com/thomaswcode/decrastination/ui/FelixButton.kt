package com.thomaswcode.decrastination.ui

import android.app.Activity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
 * from a button of Material's standard size, 56 dp (your call, 16 Oct: the small one was 40).
 */
@Composable
fun FelixFab(activity: Activity, about: About = About(), modifier: Modifier = Modifier) {
    val settings by AppGraph.get(activity).settings.state.collectAsStateWithLifecycle()
    FloatingActionButton(onClick = { activity.startActivity(AskActivity.intent(activity, about)) }, modifier = modifier) {
        Icon(painterResource(R.drawable.ic_chat), contentDescription = settings.felixName, modifier = Modifier.size(28.dp))
    }
}

/** How much room [FelixFab] takes at a screen's foot, with its margin: what a list leaves clear under its last row. */
val FELIX_ROOM = 88.dp
