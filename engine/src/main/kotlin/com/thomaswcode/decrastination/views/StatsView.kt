package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Window
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.learn.Stats
import com.thomaswcode.decrastination.learn.Summary
import com.thomaswcode.decrastination.sync.protocol.Action
import com.thomaswcode.decrastination.sync.protocol.ChangeSettings
import com.thomaswcode.decrastination.sync.protocol.ForgetCalibration
import com.thomaswcode.decrastination.sync.protocol.UndoReviewChange
import com.thomaswcode.decrastination.sync.protocol.UndoTidy
import com.thomaswcode.decrastination.ui.Format
import com.thomaswcode.decrastination.ui.ReviewPage
import com.thomaswcode.decrastination.ui.StatsPage
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The Stats page as the website draws it (docs/spec/learning.md), the record of the chosen period, in the phone's words. */
@Serializable
data class StatsView(
    val periods: List<PeriodChoice>,
    val heading: String,
    val tiles: List<TileView>,
    val days: List<DayBar>,
    /** How many days show before the rest are folded. */
    val foldAfter: Int = StatsPage.WEEK,
    val subjects: List<String>,
    val heat: List<HeatRow>,
    val heatHours: List<Int>,
    val heatNote: String,
    /** Weekday hours from the slots kept, where there's enough to go on. */
    val hours: ChatButton? = null,
    val learned: List<LearnedLine>,
    val nothingLearned: String? = null,
    val inFull: List<String>,
    val canChange: Boolean = false,
)

/** A period to show, named: the page can't ask for any other. */
@Serializable
data class PeriodChoice(val key: String, val label: String, val chosen: Boolean)

@Serializable
data class TileView(val label: String, val value: String, val note: String, val warn: Boolean = false)

/** A day's row: its focus time as a [share] of the busiest day's, and its line, faded where it was [quiet]. */
@Serializable
data class DayBar(val label: String, val today: Boolean, val share: Double, val line: String, val quiet: Boolean)

/** An hour of a weekday: slots kept there, as a [share] of the busiest hour's, and [missedMore] where more were missed than kept. */
@Serializable
data class HeatCell(val kept: Int, val missed: Int, val share: Double, val missedMore: Boolean)

@Serializable
data class HeatRow(val day: String, val cells: List<HeatCell>)

@Serializable
data class LearnedLine(val line: String, val forget: ChatButton? = null)

/** This week on the website: the check-in's form, or that it's done, and the latest review. [week] goes back with what's sent, so a check-in's kept for the week the page showed. */
@Serializable
data class WeekView(
    val week: String,
    val done: Boolean,
    val doneWords: String,
    val feel: String,
    val feelNote: String,
    val questions: List<QuestionView>,
    val save: String,
    val latest: LatestReview? = null,
    val canChange: Boolean = false,
)

@Serializable
data class QuestionView(val name: String, val label: String)

@Serializable
data class LatestReview(val heading: String, val lines: List<String>, val by: String)

/** The week's check-in as the page sends it: the server makes it the one the phone's form makes. */
@Serializable
data class CheckInAsk(val week: String, val feel: Int, val avoided: String = "", val inTheWay: String = "", val change: String = "", val energy: String = "")

/** The reviews on the website, newest first, or what's said with none. */
@Serializable
data class ReviewsView(val cards: List<ReviewCard>, val none: String? = null)

@Serializable
data class ReviewLine(val text: String, val undo: Action? = null)

/** A review as a card: [mailto] the week's summary made into an email, as the phone's Send it does, with nothing sent till you send it. */
@Serializable
data class ReviewCard(
    val id: Long,
    val week: String,
    val by: String,
    val lines: List<String>,
    val changes: List<ReviewLine>,
    val tidy: ReviewLine? = null,
    val summary: String? = null,
    val mailto: String? = null,
    val checkIn: String? = null,
)

object StatsViews {
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /** The named periods: nothing but these reaches the record. */
    private val PERIODS = listOf("two" to Stats.DAYS, "six" to StatsPage.SIX_WEEKS, "term" to StatsPage.TERM)

    /** The record at [now], for [period] (the shortest, for one that isn't named), with [today]'s free time and Claude's month. */
    fun of(log: ActivityLog, calibration: Calibration, school: SchoolCalendar, settings: Settings, today: StatsPage.Today, period: String?, now: Long, zone: ZoneId, writable: Boolean): StatsView {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val chosen = PERIODS.firstOrNull { it.first == period } ?: PERIODS.first()
        val days = StatsPage.days(chosen.second, date) { school.isSchoolDay(it) }
        val stats = Stats.summary(log, now, zone, days)
        val heat = Stats.heat(log, now, zone)
        val most = stats.days.maxOf { it.slotMin }.coerceAtLeast(1)
        val busiest = heat.values.maxOfOrNull { it.first }?.coerceAtLeast(1) ?: 1
        val suggested = Stats.suggestedWeekdayHours(heat)
        val learned = Stats.calibrationRows(calibration)
        return StatsView(
            periods = PERIODS.map { (key, value) -> PeriodChoice(key, StatsPage.PERIODS.first { it.first == value }.second, key == chosen.first) },
            heading = "The last $days days",
            tiles = StatsPage.tiles(stats, today).map { TileView(it.label, it.value, it.note, it.warn) },
            days = stats.days.map { day ->
                DayBar(if (day.date == date) "Today" else day.date.format(DAY), day.date == date, day.slotMin / most.toDouble(), StatsPage.day(day), StatsPage.quiet(day))
            },
            subjects = Stats.bySubject(log, settings.classMap, now, zone, days).map(StatsPage::subject),
            heat = DayOfWeek.entries.map { day ->
                HeatRow(
                    day.getDisplayName(TextStyle.SHORT, Locale.UK),
                    StatsPage.HEAT_HOURS.map { hour ->
                        val (kept, missed) = heat[day to hour] ?: (0 to 0)
                        HeatCell(kept, missed, kept / busiest.toDouble(), missed > kept)
                    },
                )
            },
            heatHours = StatsPage.HEAT_HOURS.toList(),
            heatNote = StatsPage.HEAT_NOTE,
            hours = suggested?.takeIf { writable }?.let { window ->
                ChatButton(StatsPage.hoursLabel(window), hoursAction(window), confirm = StatsPage.HOURS_TITLE, detail = StatsPage.hoursDetail(window, settings.weekdayHours), yes = "Set them", no = "Keep mine")
            },
            learned = learned.map { row ->
                LearnedLine(row.line, row.forget?.takeIf { writable }?.let { key -> ChatButton("Forget", ForgetCalibration(key), confirm = StatsPage.FORGET_TITLE, detail = StatsPage.forgetDetail(row), yes = "Forget", no = "Keep") })
            },
            nothingLearned = StatsPage.NOTHING_LEARNED.takeIf { learned.isEmpty() },
            inFull = StatsPage.inFull(stats, today, days, chosen.second == StatsPage.TERM),
            canChange = writable,
        )
    }

    private fun hoursAction(window: Window) = ChangeSettings(mapOf("weekdayHours" to Json.encodeToJsonElement(Window.serializer(), window)))

    /** The week's form and the latest review, at [now]. */
    fun week(log: ActivityLog, settings: Settings, now: Long, zone: ZoneId, writable: Boolean): WeekView {
        val week = Daily.checkInWeek(now, zone, settings.checkInMin)
        return WeekView(
            week = week,
            done = log.checkIns.any { it.weekOf == week },
            doneWords = ReviewPage.CHECK_IN_DONE,
            feel = ReviewPage.FEEL,
            feelNote = ReviewPage.FEEL_NOTE,
            questions = listOf(
                QuestionView("avoided", ReviewPage.AVOIDED),
                QuestionView("inTheWay", ReviewPage.IN_THE_WAY),
                QuestionView("change", ReviewPage.CHANGE),
                QuestionView("energy", ReviewPage.ENERGY),
            ),
            save = ReviewPage.SAVE,
            latest = log.reviews.maxByOrNull { it.at }?.let { review -> LatestReview("Review, ${Format.at(review.at, now, zone)}", review.lines, ReviewPage.byLine(review) + ".") },
            canChange = writable,
        )
    }

    /** The reviews as they stand, each with what can still be undone. */
    fun reviews(log: ActivityLog, settings: Settings, memory: MemoryState, zone: ZoneId, writable: Boolean): ReviewsView {
        val cards = ReviewPage.cards(log, settings, memory, zone)
        return ReviewsView(
            cards.map { card ->
                val at = card.review.at
                ReviewCard(
                    at,
                    card.week,
                    card.by,
                    card.lines,
                    card.changes.map { change -> ReviewLine(change.text, UndoReviewChange(at, change.setting).takeIf { writable && change.undoable }) },
                    card.tidy?.let { ReviewLine(it, UndoTidy(at).takeIf { writable && card.tidyUndoable }) },
                    card.summary,
                    card.summary?.let { text -> card.review.week?.let { mailto(settings.summaryEmail, it, text) } },
                    card.checkIn,
                )
            },
            ReviewPage.NONE_YET.takeIf { cards.isEmpty() },
        )
    }

    /** The email that sends [text], the [week]'s summary, to [to] (none: the mail app asks), as a link: a week that can't be read has none. */
    internal fun mailto(to: String, week: String, text: String): String? {
        val subject = runCatching { Summary.subject(week) }.getOrNull() ?: return null
        fun encoded(words: String) = URLEncoder.encode(words, Charsets.UTF_8).replace("+", "%20")
        return "mailto:${encoded(to.trim())}?subject=${encoded(subject)}&body=${encoded(text)}"
    }
}
