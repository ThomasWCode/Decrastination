package com.thomaswcode.decrastination.ui

import android.app.Activity
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.About

/** Felix on every screen (UI-16): his chat, about [about] (the task a screen is about, if it's one), from a small button. */
@Composable
fun FelixFab(activity: Activity, about: About = About(), modifier: Modifier = Modifier) {
    val settings by AppGraph.get(activity).settings.state.collectAsStateWithLifecycle()
    SmallFloatingActionButton(onClick = { activity.startActivity(AskActivity.intent(activity, about)) }, modifier = modifier) {
        Icon(painterResource(R.drawable.ic_chat), contentDescription = settings.felixName)
    }
}
