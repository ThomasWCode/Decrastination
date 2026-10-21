package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.AnkiRules
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.anki.AnkiProvider
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import com.thomaswcode.decrastination.sources.teams.TeamsProvider
import com.thomaswcode.decrastination.sources.teams.TeamsRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens a task where it lives (the block screen's and the widget's **Open**). Call it from a
 * screen in use: it starts other apps' activities.
 *
 * - Teams: the widget opens the assignment itself (`call("open", key)`), as a tap on its row does.
 * - Anki: the deck is selected, then studied (a homework deck) or shown in the list (the quota).
 * - Gmail: the app, with the subject to look for: it can't be opened on one conversation.
 * - Power Planner: the app; it has no link to one item (docs/spec/sources.md).
 */
object TaskOpener {
    const val TEAMS = "com.microsoft.teams"
    const val GMAIL = "com.google.android.gm"
    const val POWER_PLANNER = "com.barebonesdev.powerplanner"

    /**
     * Opens [task]; returns something to tell you (what went wrong, or what to look for), or null.
     * Felix sees it opened (LR-03), once it is: not when there was nothing to open it in.
     */
    suspend fun open(context: Context, task: TaskItem): String? = opened(context, task).second

    /** [task] opened where it lives, as [open], and whether it was: Felix's open (G3) says which. */
    suspend fun opened(context: Context, task: TaskItem): Pair<Boolean, String?> {
        val (opened, say) = openIn(context, task)
        if (opened) AppGraph.get(context).looks.noteActivity(listOf(task.id))
        return opened to say
    }

    /** [task] opened where it lives: whether it was, and what to tell you. */
    private suspend fun openIn(context: Context, task: TaskItem): Pair<Boolean, String?> = when (task.source) {
        Source.Teams -> openTeams(context, task)
        Source.Anki -> {
            val deckId = task.extra[AnkiRules.EXTRA_DECK_ID]?.toLongOrNull()
            // A homework deck opens on studying it; the quota spans every deck, so the deck list.
            val study = task.sourceId.startsWith(AnkiRules.DECK_PREFIX)
            when (withContext(Dispatchers.IO) { AnkiProvider.open(context, deckId, study) }) {
                AnkiProvider.Opened.Studying -> true to null
                // Couldn't select the deck (the permission revoked?): say which to choose.
                AnkiProvider.Opened.DeckList -> true to if (study) task.extra[AnkiRules.EXTRA_DECK_NAME]?.let { "In AnkiDroid: choose $it" } else null
                AnkiProvider.Opened.NotInstalled -> false to "AnkiDroid isn't installed"
            }
        }
        Source.Gmail -> openGmail(context, task)
        Source.PowerPlanner -> if (launch(context, POWER_PLANNER)) true to null else false to "Power Planner isn't installed"
        // Yours: nothing else holds it, so it opens in Tasks.
        Source.Own -> {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TASKS)
                .putExtra(MainActivity.EXTRA_TASK, task.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true to null
        }
    }

    private suspend fun openTeams(context: Context, task: TaskItem): Pair<Boolean, String?> {
        val result = runCatching {
            // Feedback to read opens its assignment (NF-06).
            withContext(Dispatchers.IO) { TeamsProvider.call(context.contentResolver, TeamsProvider.METHOD_OPEN, task.extra[TeamsRows.EXTRA_FEEDBACK_ON] ?: task.sourceId) }
        }.getOrNull()
        if (result?.getBoolean(TeamsProvider.RESULT_STARTED) == true) return true to null
        val reason = when (result?.getString(TeamsProvider.RESULT_REASON)) {
            "service_off" -> "the Teams widget's sync service is off"
            "busy" -> "the Teams widget is busy syncing"
            "unknown_key" -> "the Teams widget doesn't know this assignment any more"
            else -> "the Teams widget didn't answer"
        }
        return if (launch(context, TEAMS)) true to "Opened Teams: $reason, so find it under Assignments" else false to "Couldn't open it: $reason"
    }

    /**
     * Gmail's app opens its inbox for any link, a conversation's included (tried on the phone,
     * 9 Oct), and has no other way in from outside: so it's opened, and told what to look for.
     */
    private fun openGmail(context: Context, task: TaskItem): Pair<Boolean, String> {
        if (!launch(context, GMAIL)) return false to "Gmail isn't installed"
        val from = task.extra[GmailThreads.EXTRA_FROM]?.let { ", from $it" }.orEmpty()
        return true to "In Gmail: \"${task.title}\"$from"
    }

    fun launch(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
