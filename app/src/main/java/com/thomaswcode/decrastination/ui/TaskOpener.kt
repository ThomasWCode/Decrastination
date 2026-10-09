package com.thomaswcode.decrastination.ui

import android.content.Context
import android.content.Intent
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.anki.AnkiProvider
import com.thomaswcode.decrastination.sources.anki.AnkiRules
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import com.thomaswcode.decrastination.sources.teams.TeamsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens a task where it lives (the block screen's and the widget's **Open**). Call it from a
 * screen in use: it starts other apps' activities.
 *
 * - Teams: the widget opens the assignment itself (`call("open", key)`), as a tap on its row does.
 * - Anki: the deck is selected, then studied (a homework deck) or shown in the list (the quota).
 * - Gmail: the app, with the subject to look for: it can't be opened on one conversation.
 * - Power Planner: the app; it has no link to one item (docs/data-sources.md §2).
 */
object TaskOpener {
    const val TEAMS = "com.microsoft.teams"
    const val GMAIL = "com.google.android.gm"
    const val POWER_PLANNER = "com.barebonesdev.powerplanner"

    /** Opens [task]; returns something to tell you (what went wrong, or what to look for), or null. */
    suspend fun open(context: Context, task: TaskItem): String? = when (task.source) {
        Source.Teams -> openTeams(context, task)
        Source.Anki -> {
            val deckId = task.extra[AnkiRules.EXTRA_DECK_ID]?.toLongOrNull()
            // A homework deck opens on studying it; the quota spans every deck, so the deck list.
            val study = task.sourceId.startsWith(AnkiRules.DECK_PREFIX)
            when (withContext(Dispatchers.IO) { AnkiProvider.open(context, deckId, study) }) {
                AnkiProvider.Opened.Studying -> null
                // Couldn't select the deck (the permission revoked?): say which to choose.
                AnkiProvider.Opened.DeckList -> if (study) task.extra[AnkiRules.EXTRA_DECK_NAME]?.let { "In AnkiDroid: choose $it" } else null
                AnkiProvider.Opened.NotInstalled -> "AnkiDroid isn't installed"
            }
        }
        Source.Gmail -> openGmail(context, task)
        Source.PowerPlanner -> if (launch(context, POWER_PLANNER)) null else "Power Planner isn't installed"
    }

    private suspend fun openTeams(context: Context, task: TaskItem): String? {
        val result = runCatching {
            withContext(Dispatchers.IO) { TeamsProvider.call(context.contentResolver, TeamsProvider.METHOD_OPEN, task.sourceId) }
        }.getOrNull()
        if (result?.getBoolean(TeamsProvider.RESULT_STARTED) == true) return null
        val reason = when (result?.getString(TeamsProvider.RESULT_REASON)) {
            "service_off" -> "the Teams widget's sync service is off"
            "busy" -> "the Teams widget is busy syncing"
            "unknown_key" -> "the Teams widget doesn't know this assignment any more"
            else -> "the Teams widget didn't answer"
        }
        return if (launch(context, TEAMS)) "Opened Teams: $reason, so find it under Assignments" else "Couldn't open it: $reason"
    }

    /**
     * Gmail's app opens its inbox for any link, a conversation's included (tried on the phone,
     * 9 Oct), and has no other way in from outside: so it's opened, and told what to look for.
     */
    private fun openGmail(context: Context, task: TaskItem): String {
        if (!launch(context, GMAIL)) return "Gmail isn't installed"
        val from = task.extra[GmailThreads.EXTRA_FROM]?.let { ", from $it" }.orEmpty()
        return "In Gmail: \"${task.title}\"$from"
    }

    fun launch(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
