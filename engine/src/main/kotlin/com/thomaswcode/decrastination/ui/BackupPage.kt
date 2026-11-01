package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.data.Reset

/** What Backup and Reset say: the website's as well as the phone's. */
object BackupPage {
    const val BACK_UP = "The settings, the log, what the app has learned, your calendar answers and your instructions in use, to a file you choose. No passwords or keys."
    const val RESTORE = "From a backup. Once protection is armed, a setting that loosens blocking waits, as one changed in Settings does."
    const val TOO_BIG = "That file is too big to be a backup."

    /** What's to know of a backup as things stand: [leftOut] of the chat's earliest messages, and whether Import could read one. */
    fun fit(leftOut: Int, tooBig: Boolean, refused: String?): String? = when {
        tooBig -> "All the app keeps is more than Import reads (8 MB), even without the chat's earlier messages, so the nightly backup isn't written: one couldn't be restored. The last two weeks' are kept as they are."
        refused != null -> "Import would refuse a backup of what the app keeps now ($refused), so the nightly backup isn't written: one couldn't be restored. The last two weeks' are kept as they are."
        leftOut > 0 -> "The chat's $leftOut earliest messages are left out of the backup, as with them it would be more than Import reads (8 MB). They're still in the chat."
        else -> null
    }

    fun resetTitle(what: Reset) = "Reset ${what.title.replaceFirstChar(Char::lowercase)}?"

    /** What's asked before [what] is reset, with your changes in use, [applied], where they'd go. */
    fun resetAsks(what: Reset, applied: List<Instruction>): String {
        val changes = if (what == Reset.Instructions || what == Reset.Everything) applied.map { it.text.take(80) } else emptyList()
        return "This goes: ${what.takes}." + (if (changes.isNotEmpty()) " Your changes: " + changes.joinToString("; ") { "“$it”" } + "." else "") + " A copy of everything is saved first."
    }
}
