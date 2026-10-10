package com.thomaswcode.decrastination.sources.gmail

import com.thomaswcode.decrastination.core.Kind
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The rules that triage an inbox thread when the AI is off (docs/data-sources.md §4): what kind
 * of task it is, roughly how long it takes, and when it becomes relevant. Pure, for the tests.
 *
 * - A note you sent yourself (Gmail labels it `\Sent`): something to deal with, 10 minutes.
 * - A shared document: open it, 5 minutes.
 * - One that asks you to do something ("please complete", "return the form by…", "action
 *   required"): something to do, whoever sent it and whatever else it says, 15 minutes, due by the
 *   date it names after "by", if any. A registration or a form from a no-reply address is still
 *   work, so it isn't hidden as an event or a notification (the review's BUG-P1-003).
 * - Notifications and newsletters (no-reply senders, LinkedIn, an unsubscribe link): read and
 *   archive, 2 minutes.
 * - A ticket, booking or open day with a date ahead: an event. It appears the day before, and
 *   wants archiving by the day after.
 * - Anything else: read it and decide, 15 minutes.
 *
 * The enrichment (Phase 4) does this better when it's on; these are the floor.
 */
object EmailRules {

    data class Email(
        val fromName: String?,
        val fromAddress: String?,
        val subject: String,
        val body: String,
        /** Gmail's `\Sent` label: you wrote it, from either of your addresses. */
        val sent: Boolean,
    )

    data class Triage(
        val kind: Kind,
        val effortMin: Int,
        /** What to do, shown as the task's next step. */
        val nextStep: String,
        val availableFrom: Long? = null,
        val dueAt: Long? = null,
    )

    private const val PAST_WINDOW_DAYS = 120L

    /** How far after a "by" its date can be: "by Friday 12 October 2026". */
    private const val BY_REACH = 40

    private val SHARED_DOCUMENT = Regex("""drive-shares|docs\.google\.com|shared with you""", RegexOption.IGNORE_CASE)
    private val NO_REPLY = Regex("""no-?reply|do-?not-?reply|notifications?@|newsletter|news@|marketing|mailer@|updates@|info@""", RegexOption.IGNORE_CASE)
    private val NOISE_DOMAINS = Regex("""@(.+\.)?(linkedin\.com|facebookmail\.com|instagram\.com|twitter\.com|x\.com|medium\.com|substack\.com|sportograf\.com)$""", RegexOption.IGNORE_CASE)
    private val UNSUBSCRIBE = Regex("""unsubscribe""", RegexOption.IGNORE_CASE)
    private val EVENT_WORDS = Regex(
        """\b(e-?tickets?|tickets?|booking|booked|reservation|reserved|open day|appointment|registered|registration|admission|boarding pass)\b""",
        RegexOption.IGNORE_CASE,
    )
    /**
     * Asked to do something: "please complete / return / sign / fill in…" (not "please do not
     * reply"), "action required", "must be returned", "reply by". An event's or a notification's
     * words don't make that nothing to do.
     */
    private val ACTION = Regex(
        """\b(?:please|kindly)\s+(?!do\s+not\b|don'?t\b)(?:\w+\s+){0,2}?(?:complete|return|sign|fill\s+(?:in|out)|submit|reply|respond|confirm|register|pay|send|bring|upload)\b""" +
            """|\baction\s+(?:required|needed)\b""" +
            """|\b(?:must|needs?\s+to)\s+be\s+(?:completed|returned|signed|submitted|paid|filled)\b""" +
            """|\b(?:reply|respond|register|sign\s+up|apply|submit|return|complete)\s+(?:\w+\s+){0,3}?by\b""",
        RegexOption.IGNORE_CASE,
    )

    /** "by" before a date: where an email that asks for something says when by. */
    private val BY = Regex("""\bby\b""", RegexOption.IGNORE_CASE)


    fun triage(email: Email, now: Long, zone: ZoneId): Triage {
        val address = email.fromAddress.orEmpty()
        val text = email.subject + "\n" + email.body
        if (email.sent) return Triage(Kind.Admin, 10, "Deal with your note")
        if (SHARED_DOCUMENT.containsMatchIn(address + " " + email.subject)) return Triage(Kind.Admin, 5, "Open the shared document")
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (ACTION.containsMatchIn(text)) {
            // Due by the first date written just after a "by", if there is one.
            val by = BY.findAll(text).firstNotNullOfOrNull { futureDates(text.substring(it.range.last + 1).take(BY_REACH), today).firstOrNull() }
            return Triage(Kind.Admin, Kind.Admin.defaultEffortMin, "Do what it asks", dueAt = by?.atTime(23, 59)?.atZone(zone)?.toInstant()?.toEpochMilli())
        }
        if (EVENT_WORDS.containsMatchIn(text)) {
            futureDates(text, today).firstOrNull()?.let { day ->
                // Calendar days, not 24-hour steps: the clocks change in October and March.
                return Triage(
                    kind = Kind.Event,
                    effortMin = Kind.Event.defaultEffortMin,
                    nextStep = "Keep for ${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.SHORT, Locale.UK)}, then archive",
                    availableFrom = day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    dueAt = day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
            }
        }
        if (NO_REPLY.containsMatchIn(address) || NOISE_DOMAINS.containsMatchIn(address) || UNSUBSCRIBE.containsMatchIn(email.body)) {
            return Triage(Kind.Info, Kind.Info.defaultEffortMin, "Read and archive")
        }
        return Triage(Kind.Admin, Kind.Admin.defaultEffortMin, "Read it and decide")
    }

    private val MONTHS = Month.entries.associateBy { it.getDisplayName(TextStyle.FULL, Locale.UK).lowercase() } +
        Month.entries.associateBy { it.getDisplayName(TextStyle.SHORT, Locale.UK).lowercase().removeSuffix(".") } +
        mapOf("sept" to Month.SEPTEMBER)
    private val MONTH = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
    private val DAY_MONTH = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+($MONTH)\.?(?:,?\s+(\d{4}))?\b""", RegexOption.IGNORE_CASE)
    private val MONTH_DAY = Regex("""\b($MONTH)\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?\b""", RegexOption.IGNORE_CASE)
    private val NUMERIC = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{2}|\d{4})\b""")

    /**
     * Dates written in [text] from [today] on, within a year, earliest first: "10 October",
     * "Sat 10th Oct 2026", "October 10", "10/10/2026" (day first, as in the UK). A date with no
     * year is this year's, unless that was over [PAST_WINDOW_DAYS] ago, when it's next year's: an
     * email from last month saying "booked on 1 October" means this October, now past.
     */
    fun futureDates(text: String, today: LocalDate): List<LocalDate> {
        val found = mutableListOf<LocalDate>()
        fun add(day: Int, month: Month?, year: Int?) {
            month ?: return
            val date = runCatching {
                if (year != null) {
                    LocalDate.of(year, month, day)
                } else {
                    LocalDate.of(today.year, month, day).let { if (it < today.minusDays(PAST_WINDOW_DAYS)) it.plusYears(1) else it }
                }
            }.getOrNull() ?: return
            if (date >= today && date <= today.plusYears(1)) found += date
        }
        DAY_MONTH.findAll(text).forEach { add(it.groupValues[1].toInt(), MONTHS[it.groupValues[2].lowercase()], it.groupValues[3].toIntOrNull()) }
        MONTH_DAY.findAll(text).forEach { add(it.groupValues[2].toInt(), MONTHS[it.groupValues[1].lowercase()], it.groupValues[3].toIntOrNull()) }
        NUMERIC.findAll(text).forEach { match ->
            val year = match.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
            add(match.groupValues[1].toInt(), Month.entries.getOrNull(match.groupValues[2].toInt() - 1), year)
        }
        return found.distinct().sorted()
    }
}
