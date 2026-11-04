package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.GmailWrite
import com.thomaswcode.decrastination.core.GmailWrites
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.CallRecord
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.data.SyncState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.enrich.KeyProblem
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** What Setup says of each part (UI-18), and when it needs you: the website's as well as the phone's. */
object SetupPage {
    /** A row: what it's of, whether it's done (null: not a thing to tick), and what's said of it. */
    data class Line(val title: String, val done: Boolean?, val detail: String)

    /** How hard Claude thinks, as Settings says it. */
    const val EFFORT_NOTE = "By job, not set here: high for what the plan is made from, medium for what someone's waiting for (AC-02)."

    private val SourceStatus.works: Boolean get() = lastSuccessAt != null && error == null

    private fun sourceDetail(status: SourceStatus, otherwise: String): String =
        status.error?.let { "Last read failed: $it" } ?: if (status.lastSuccessAt != null) "Reading it works." else otherwise

    fun teams(status: SourceStatus) = Line(
        "Teams Assignments widget",
        status.works,
        sourceDetail(status, "Needs the widget, 0.3.1 or later, signed with the same key."),
    )

    fun powerPlanner(status: SourceStatus, saved: Boolean) = Line(
        "Power Planner login",
        saved && status.works,
        sourceDetail(status, if (saved) "Saved." else "Not saved."),
    )

    fun anki(status: SourceStatus, allowed: Boolean) = Line(
        "AnkiDroid access",
        allowed && status.works,
        if (allowed) sourceDetail(status, "Allowed.") else "AnkiDroid asks you to allow it once.",
    )

    fun gmail(status: SourceStatus, saved: Boolean, finishes: Boolean, givenUp: List<GmailWrite>) = Line(
        "Gmail app password",
        saved && status.works,
        // The star is the decision (DX-03): said where the account is set up.
        sourceDetail(status, if (saved) "Saved." else "Not saved. Nothing is ever sent, deleted or marked read.") +
            " The app reads your starred emails only, each with its whole conversation. Star an email in Gmail to make it a task; unstar it when it's done, or finish its work here" +
            (if (finishes) ", which unstars and archives it in Gmail (Settings → Sources)." else ".") +
            givenUp.joinToString("") { " " + GmailWrites.failure(it) + " Its sheet's It's sorted clears this." },
    )

    /** A new key, for a server that asks Claude: it's the box's (W-12), and no page takes one. */
    private const val BOX_KEY = "Put a new key in the box's .env and start the server again"

    /** Claude, from whether a key's held, as the month's [usage] stands. A key [onBox] is in use as it's there, whatever the phone's is. */
    fun claude(keyHeld: Boolean, settings: Settings, usage: AiUsage, onBox: Boolean = false): Line {
        val keyProblem = usage.keyProblem
        val lastError = usage.lastError
        return Line(
            "Claude",
            settings.aiEnabled && (onBox || settings.aiKeyActive) && keyHeld && usage.lastError == null && usage.keyProblem == null,
            when {
                !keyHeld -> "No API key: the rules do what they can, and nothing is sent to Claude."
                !onBox && !settings.aiKeyActive -> "Key saved, not in use yet: enter it again to use it."
                !settings.aiEnabled -> "Key saved; switched off in Settings, so nothing is sent."
                // The key or its account can't be used: said with what puts it right.
                keyProblem != null -> "${keyProblem.says}. ${if (onBox && keyProblem == KeyProblem.Rejected) BOX_KEY else keyProblem.fix}: the rules stand in, and it's tried again an hour after each failed try."
                // Its calls failing (a bad key, no connection): said, so it can be put right.
                lastError != null -> "On, but its last call failed (${lastError.take(80)}): the rules stand in, and it's tried again after an hour."
                else -> "On: $%.2f this month".format(Locale.UK, usage.spentUsd) + (settings.aiMonthlyCapUsd?.let { " of $$it" } ?: ", no cap: it stops when the account's credit runs out") + "."
            },
        )
    }

    /** What Setup would show in red, as a mark that opens it: a source failing, what else is passed in; and, the first run, no source read yet. */
    fun attention(tasks: TaskState, gmailGivenUp: Boolean, keyProblem: Boolean, watchdog: Boolean, unreadable: Boolean, adrift: Boolean = false): Boolean =
        Source.SYNCED.any { tasks.status(it).error != null } || gmailGivenUp || keyProblem || watchdog || unreadable || adrift ||
            Source.SYNCED.all { tasks.status(it).lastSuccessAt == null && tasks.status(it).error == null }

    /** How long a change waits to reach the server before the Day says so: a fault, a release's bug, a command nothing can apply, the server gone. */
    const val HELD_MS = 3 * 60 * 60_000L

    /** How long with no exchange before the Day says so. */
    const val ADRIFT_MS = 7 * 24 * 60 * 60_000L

    /**
     * The Day's line on keeping in step, once the phone sends commands: the oldest change waiting, from [oldestWaiting], [HELD_MS]
     * or more, as it holds up what's after it and every pull (Setup lets it go); else a week with no exchange, what's changed on the
     * website not here. Null while mirroring, unpaired, or in step.
     */
    fun syncLine(sync: SyncState, oldestWaiting: Long?, paired: Boolean, now: Long, zone: ZoneId): String? {
        if (!paired || sync.mode != SyncState.COMMANDS) return null
        if (oldestWaiting != null && now - oldestWaiting >= HELD_MS) {
            return "A change made here ${Format.at(oldestWaiting, now, zone)} hasn't reached the server, and what's after it waits too: Setup says why."
        }
        val last = sync.lastSyncAt ?: return null
        return if (now - last >= ADRIFT_MS) "Not in step with the server for ${(now - last) / DAY_MS} days: what's changed on the website isn't here. Setup says why." else null
    }

    private const val DAY_MS = 24 * 60 * 60_000L

    /** A call to Claude on one line. */
    fun callLine(call: CallRecord, zone: ZoneId): String =
        listOfNotNull(Instant.ofEpochMilli(call.at).atZone(zone).toLocalTime().withSecond(0).withNano(0).toString(), call.job, call.title, call.outcome).joinToString(" · ") + " · $%.3f".format(Locale.UK, call.costUsd)
}
