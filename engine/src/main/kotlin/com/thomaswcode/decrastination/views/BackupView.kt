package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Reset
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.sync.protocol.ResetData
import com.thomaswcode.decrastination.ui.BackupPage
import com.thomaswcode.decrastination.ui.Format
import com.thomaswcode.decrastination.ui.SetupPage
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** Backup and Reset as the website draws them (docs/spec/website.md). While the phone writes, only a backup's to be had. */
@Serializable
data class BackupView(
    val backUp: String,
    val restore: String,
    /** What's to know of a backup as things stand. */
    val fit: String? = null,
    /** Whether one can be had: more than Import reads couldn't be restored. */
    val canDownload: Boolean,
    val lastNightly: String? = null,
    /** A reset's copies, kept here, newest first. */
    val copies: List<String>,
    val resets: List<ResetChoice>,
    /** What's said of a file too big to take. */
    val tooBig: String,
    val canChange: Boolean,
)

/** A reset's copy to restore, by its name. */
@Serializable
data class CopyAsk(val name: String)

/** What can be reset, and the button that asks first. */
@Serializable
data class ResetChoice(val title: String, val takes: String, val button: ChatButton)

object BackupViews {
    /** The page, by what a backup of it came to now: [leftOut] of the chat's earliest messages, [tooBig] and [refused]. */
    fun of(leftOut: Int, tooBig: Boolean, refused: String?, lastNightly: Long?, copies: List<String>, applied: List<Instruction>, now: Long, writable: Boolean) = BackupView(
        backUp = BackupPage.BACK_UP,
        restore = BackupPage.RESTORE,
        fit = BackupPage.fit(leftOut, tooBig, refused),
        canDownload = !tooBig,
        lastNightly = lastNightly?.let { "Last nightly backup: ${Format.ago(it, now)}" },
        copies = if (writable) copies else emptyList(),
        resets = if (writable) Reset.entries.map { choice(it, applied) } else emptyList(),
        tooBig = BackupPage.TOO_BIG,
        canChange = writable,
    )

    private fun choice(what: Reset, applied: List<Instruction>) = ResetChoice(
        what.title,
        what.takes.replaceFirstChar(Char::uppercase),
        ChatButton("Reset", ResetData(what), confirm = BackupPage.resetTitle(what), detail = BackupPage.resetAsks(what, applied), yes = "Reset", no = "Keep it"),
    )
}

/** A row of Setup: what it's of, whether it's done (none: not a thing to tick), and what's said of it. */
@Serializable
data class SetupLine(val title: String, val done: Boolean? = null, val detail: String)

/** Setup as the website draws it (UI-18): what the server couldn't read at its start, the sources and Claude. */
@Serializable
data class SetupView(val saved: List<SetupLine>, val sources: List<SetupLine>, val claude: SetupLine)

object SetupViews {
    /** The sign-ins were sealed under another key than this box's: a phone's words for that would name its keystore. */
    private const val SECRETS_LOST = "The sign-ins held here couldn't be decrypted, as MASTER_KEY isn't the key they were sealed with: put that back, or enter them again under Settings, Sources."

    /**
     * As things stand at [now]. While the phone writes, the sign-ins and the key in use are its own, which aren't here to see, so
     * each goes by how its source reads ([writable] false). Once this server reads them, by [held] and [keyHeld]. Teams and AnkiDroid are the phone's either way.
     */
    fun of(tasks: TaskState, unreadable: List<JsonStore.Unreadable>, holds: (String) -> Boolean, settings: Settings, usage: AiUsage, held: Set<String>, keyHeld: Boolean, now: Long, zone: ZoneId, writable: Boolean) = SetupView(
        saved = unreadable.map { found -> SetupLine("Found unreadable ${Format.at(found.at, now, zone)}", false, if (found.file == SECRETS) SECRETS_LOST else found.says(holds)) },
        sources = listOf(
            SetupPage.teams(tasks.status(Source.Teams)),
            SetupPage.powerPlanner(tasks.status(Source.PowerPlanner), !writable || Secret.PowerPlannerPassword.name in held),
            SetupPage.anki(tasks.status(Source.Anki), allowed = true),
            SetupPage.gmail(tasks.status(Source.Gmail), !writable || Secret.GmailAppPassword.name in held, settings.gmailFinishes, givenUp = emptyList()),
        ).map(::line),
        claude = line(SetupPage.claude(!writable || keyHeld, settings, usage, BOX_KEY.takeIf { writable })),
    )

    /** Once the server asks Claude, its key is the box's (W-12), not one entered on a page. */
    private const val BOX_KEY = "Put a new key in the box's .env and start the server again"

    private fun line(line: SetupPage.Line) = SetupLine(line.title, line.done, line.detail)

    private const val SECRETS = "secrets.bin"
}
