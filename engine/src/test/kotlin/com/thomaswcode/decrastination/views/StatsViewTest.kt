package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Window
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.MemoryTidyRecord
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.ReviewInput
import com.thomaswcode.decrastination.sync.protocol.ChangeSettings
import com.thomaswcode.decrastination.sync.protocol.ForgetCalibration
import com.thomaswcode.decrastination.sync.protocol.UndoReviewChange
import com.thomaswcode.decrastination.sync.protocol.UndoTidy
import com.thomaswcode.decrastination.ui.ReviewPage
import com.thomaswcode.decrastination.ui.StatsPage
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** Stats, This week and the reviews as the website draws them (docs/spec/website.md). */
class StatsViewTest {
    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val now = Fixtures.at("2026-10-14T12:00")
    private val today = StatsPage.Today(30, 10, 20, 1.5, 2, 5, false)

    private fun stats(
        log: ActivityLog = ActivityLog(),
        calibration: Calibration = Calibration(),
        period: String? = null,
        writable: Boolean = true,
    ) = StatsViews.of(log, calibration, SchoolCalendar(), Settings(), today, period, now, zone, writable)

    @Test
    fun `the period's one of those named, and one that isn't is the shortest`() {
        assertEquals("The last 14 days", stats().heading)
        assertEquals("The last 42 days", stats(period = "six").heading)
        assertEquals(listOf("2 weeks", "6 weeks", "This term"), stats().periods.map { it.label })
        assertEquals(listOf(false, true, false), stats(period = "six").periods.map { it.chosen })
        assertEquals("The last 14 days", stats(period = "9999999").heading, "a page can't ask for any length it likes")
        assertEquals(listOf(true, false, false), stats(period = "9999999").periods.map { it.chosen })
    }

    @Test
    fun `each day has its bar against the busiest, today marked, and the first week's shown before the fold`() {
        val view = stats()
        assertEquals(14, view.days.size)
        assertEquals("Today", view.days.first().label)
        assertTrue(view.days.first().today)
        assertFalse(view.days.last().today)
        assertEquals(StatsPage.WEEK, view.foldAfter)
        assertEquals(0.0, view.days.first().share, "with nothing worked, no bar: not a sum by nothing")
    }

    @Test
    fun `the heat's a row a weekday and a cell an hour`() {
        val view = stats()
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), view.heat.map { it.day })
        assertEquals((7..23).toList(), view.heatHours)
        assertTrue(view.heat.all { it.cells.size == 17 })
        assertEquals(StatsPage.HEAT_NOTE, view.heatNote)
    }

    private fun slot(start: String, outcome: SlotOutcome = SlotOutcome.Kept, worked: Int = 30) = SlotRecord(
        date = start.take(10), key = "own:t#-1#1", taskId = "own:t", kind = Kind.Homework, label = "t", plannedMin = 30,
        start = Fixtures.at(start), end = Fixtures.at(start) + 30 * 60_000L, window = WindowKind.Evening, outcome = outcome,
        workedMin = worked, photo = false, earned = false, at = Fixtures.at(start) + 30 * 60_000L,
    )

    @Test
    fun `a day's bar is its share of the busiest's`() {
        val view = stats(ActivityLog(slots = listOf(slot("2026-10-13T17:00", worked = 40), slot("2026-10-12T17:00", worked = 20))))
        assertEquals(listOf(0.0, 1.0, 0.5), view.days.take(3).map { it.share })
    }

    @Test
    fun `an hour's cell is its share of the busiest's, and says where more were missed than kept`() {
        val view = stats(
            ActivityLog(
                slots = listOf(
                    slot("2026-10-12T17:00"), slot("2026-10-05T17:00"),
                    slot("2026-10-13T17:00"), slot("2026-10-06T17:00", SlotOutcome.Missed), slot("2026-09-29T17:00", SlotOutcome.Missed),
                    slot("2026-10-07T17:00"), slot("2026-09-30T17:00", SlotOutcome.Missed),
                ),
            ),
        )
        fun cell(day: String) = view.heat.single { it.day == day }.cells[17 - view.heatHours.first()]
        assertEquals(HeatCell(2, 0, 1.0, false), cell("Mon"))
        assertEquals(HeatCell(1, 2, 0.5, true), cell("Tue"))
        assertEquals(HeatCell(1, 1, 0.5, false), cell("Wed"), "as many as kept isn't more")
    }

    @Test
    fun `hours from the slots kept are offered, asking first, and not while mirroring`() {
        val log = ActivityLog(slots = (0 until 12).map { i -> slot("2026-10-0${5 + i % 5}T1${7 + i % 2}:00") })
        val hours = assertNotNull(stats(log).hours)
        assertEquals(ChangeSettings(mapOf("weekdayHours" to Json.encodeToJsonElement(Window.serializer(), Window(17 * 60, 19 * 60)))), hours.action)
        assertEquals(StatsPage.HOURS_TITLE, hours.confirm)
        assertEquals("Set them", hours.yes)
        assertEquals("Keep mine", hours.no)
        assertNull(stats(log, writable = false).hours)
        assertNull(stats().hours, "with too little to go on, none")
    }

    @Test
    fun `what's learned can be forgotten where it's a class's, asking first, and not while mirroring`() {
        val calibration = Calibration(multipliers = mapOf("Homework|" to 1.2, "Homework|Maths" to 1.5))
        val view = stats(calibration = calibration)
        assertNull(view.nothingLearned)
        val forgettable = view.learned.mapNotNull { it.forget }
        assertEquals(ForgetCalibration("Homework|Maths"), forgettable.single().action)
        assertEquals(StatsPage.FORGET_TITLE, forgettable.single().confirm)
        assertEquals("Forget", forgettable.single().yes)
        assertEquals("Keep", forgettable.single().no)
        assertTrue(stats(calibration = calibration, writable = false).learned.all { it.forget == null })
    }

    @Test
    fun `nothing learned says so`() {
        assertEquals(StatsPage.NOTHING_LEARNED, stats().nothingLearned)
        assertTrue(stats().learned.isEmpty())
    }

    @Test
    fun `this week's form, its week and what's next`() {
        val view = StatsViews.week(ActivityLog(), Settings(), now, zone, writable = true)
        assertFalse(view.done)
        assertEquals("2026-10-05", view.week)
        assertEquals(listOf("avoided", "inTheWay", "change", "energy"), view.questions.map { it.name })
        assertNull(view.latest)
        val done = StatsViews.week(ActivityLog(checkIns = listOf(CheckIn("2026-10-05", now, 3, "", "", "", ""))), Settings(), now, zone, writable = true)
        assertTrue(done.done)
    }

    @Test
    fun `the latest review's shown, newest of them`() {
        val log = ActivityLog(reviews = listOf(WeeklyReview(now - 1, listOf("Older"), by = "rules"), WeeklyReview(now, listOf("Newer"), by = "rules", why = "Claude was off")))
        val latest = assertNotNull(StatsViews.week(log, Settings(), now, zone, writable = true).latest)
        assertEquals(listOf("Newer"), latest.lines)
        assertEquals("By the rules: Claude was off.", latest.by)
    }

    private val change = ReviewInput.Change("softMinPerDay", 90, "made up")
    private val after = listOf(Fact("school", "Takes the bus", 1L))
    private val review = WeeklyReview(
        now, listOf("A made-up note."), by = "rules", week = "2026-10-12", changes = listOf(change), settingsBefore = mapOf("softMinPerDay" to 60),
        memoryTidy = MemoryTidyRecord(after + after, after), summary = "A made-up week",
    )

    private fun reviews(settings: Settings = Settings(softMinPerDay = 90), memory: MemoryState = MemoryState(facts = after), writable: Boolean = true) =
        StatsViews.reviews(ActivityLog(reviews = listOf(review)), settings, memory, zone, writable)

    @Test
    fun `a review names itself by its moment, for its Undos to read it from the log`() {
        val card = reviews().cards.single()
        assertEquals(now, card.id)
        assertEquals(UndoReviewChange(now, "softMinPerDay"), card.changes.single().undo)
        assertEquals(UndoTidy(now), card.tidy?.undo)
    }

    @Test
    fun `what can't be undone has no Undo, and mirroring nothing has`() {
        assertNull(reviews(settings = Settings(softMinPerDay = 80)).cards.single().changes.single().undo)
        assertNull(reviews(memory = MemoryState()).cards.single().tidy?.undo)
        val mirrored = reviews(writable = false).cards.single()
        assertNull(mirrored.changes.single().undo)
        assertNull(mirrored.tidy?.undo)
    }

    @Test
    fun `no review yet says so`() {
        val view = StatsViews.reviews(ActivityLog(), Settings(), MemoryState(), zone, writable = true)
        assertEquals(ReviewPage.NONE_YET, view.none)
        assertNull(reviews().none)
    }

    @Test
    fun `a summary's made an email, to the address set or none, with nothing sent`() {
        val mailto = assertNotNull(reviews().cards.single().mailto)
        assertTrue(mailto.startsWith("mailto:?subject=Week%20of"), mailto)
        assertTrue(mailto.endsWith("&body=A%20made-up%20week"), mailto)
        val to = assertNotNull(StatsViews.mailto("home@example.com", "2026-10-12", "Words"))
        assertTrue(to.startsWith("mailto:home%40example.com?"), to)
        assertNull(StatsViews.mailto("", "not a week", "Words"), "a week that can't be read has no subject to go by")
    }
}
