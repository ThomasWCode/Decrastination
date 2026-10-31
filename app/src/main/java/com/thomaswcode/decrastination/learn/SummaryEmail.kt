package com.thomaswcode.decrastination.learn

import android.content.Intent
import androidx.core.net.toUri

/** The week's summary for home (NF-02) as the email it goes in. */
object SummaryEmail {
    /**
     * The email that sends [text], the [week]'s summary, to [to] (none: the mail app asks): your mail app,
     * to send or change. Handed over as one to send, with a mailto selector so only mail apps take it, as
     * Gmail keeps only the address of a mailto link's and drops its subject and words.
     */
    fun email(to: String, week: String, text: String): Intent =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_EMAIL, listOfNotNull(to.trim().takeIf { it.isNotEmpty() }).toTypedArray())
            .putExtra(Intent.EXTRA_SUBJECT, Summary.subject(week))
            .putExtra(Intent.EXTRA_TEXT, text)
            .apply { selector = Intent(Intent.ACTION_SENDTO, "mailto:".toUri()) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
