package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.HalfDay
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.Rule
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.SettingsLimits
import com.thomaswcode.decrastination.data.CallRecord
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.protect.PendingChange
import com.thomaswcode.decrastination.protect.SettingsChanges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/** Settings as the website gets them (W-14): each field with what it takes, so the page holds no limit of its own. */
class SettingsViewTest {
    private val now = Fixtures.at("2026-10-12T17:00")

    private fun view(
        settings: Settings = Settings(),
        pending: List<PendingChange> = emptyList(),
        school: SchoolCalendar = SchoolCalendar(),
        memory: MemoryState = MemoryState(),
        held: Set<String> = emptySet(),
        parentCode: Boolean = false,
        writable: Boolean = true,
    ) = SettingsViews.of(settings, pending, TaskState(), school, memory, AiUsage(), held, parentCode, now, LONDON, writable)

    private fun SettingsView.field(name: String) = pages.flatMap { it.fields }.single { it.name == name }

    @Test
    fun `what's the phone's alone is said, and Claude's calls are shown newest first`() {
        assertEquals(listOf("Blocking and protection", "Calendar", "This phone's pairing", "The last crash"), view().phoneOnly)
        assertEquals(ModelEffort.table().size, view().efforts.size)
        assertTrue(view().efforts.first().startsWith(ModelEffort.table().first().first + ": "), view().efforts.first())
        val calls = listOf(CallRecord(1L, "older", effort = "high", outcome = "ok"), CallRecord(2L, "newer", effort = "high", outcome = "ok"))
        val shown = SettingsViews.of(Settings(), emptyList(), TaskState(), SchoolCalendar(), MemoryState(), AiUsage(), emptySet(), false, now, LONDON, true, calls).calls
        assertEquals(listOf(2L, 1L), shown.map { it.at })
        assertEquals(calls.last().details(LONDON), shown.first().details)
        assertTrue("newer" in shown.first().line, shown.first().line)
    }

    @Test
    fun `the pages are the phone's that are shared state, and leave out what's the phone's own`() {
        assertEquals(listOf("about", "hours", "school", "planning", "felix", "claude", "sources", "notifications"), view().pages.map { it.key })
        val names = view().pages.flatMap { it.fields }.map { it.name }
        for (phones in listOf("armed", "blockedApps", "blockedSites", "calendarWriteId", "aiKeyActive", "version")) assertFalse(phones in names, phones)
    }

    @Test
    fun `every field is a setting, one a change can reach`() {
        val settings = SettingsChanges.fields
        for (field in view().pages.flatMap { it.fields }) assertTrue(field.name in settings, field.name)
    }

    @Test
    fun `a number says its range, as the phone's page holds it`() {
        val box = view().field("boxMin")
        assertEquals(SettingField.NUMBER, box.kind)
        assertEquals(SettingsLimits.BOX_MIN.first, box.min)
        assertEquals(SettingsLimits.BOX_MIN.last, box.max)
        assertEquals(JsonPrimitive(Settings().boxMin), box.value)
    }

    @Test
    fun `those that can loosen blocking say so, and those about the work don't`() {
        for (name in listOf("quietHours", "weekdayBlockFromMin", "weekendBlockFromMin", "workMinPerFreeMin", "photoFromMin", "freeAllowanceMin")) assertTrue(view().field(name).waits, name)
        for (name in listOf("boxMin", "weekdayHours", "felixName", "aiEnabled")) assertFalse(view().field(name).waits, name)
    }

    @Test
    fun `what can wait is what the rules say can loosen, so the two can't drift apart`() {
        val tighter = Settings(armed = true, workMinPerFreeMin = 5, photoFromMin = 30, freeAllowanceMin = 30, weekdayBlockFromMin = 600, weekendBlockFromMin = 600)
        val looser = tighter.copy(workMinPerFreeMin = 2, photoFromMin = 60, freeAllowanceMin = 60, weekdayBlockFromMin = 700, weekendBlockFromMin = 700)
        for (name in listOf("workMinPerFreeMin", "photoFromMin", "freeAllowanceMin", "weekdayBlockFromMin", "weekendBlockFromMin")) assertTrue(SettingsChanges.loosens(name, tighter, looser), name)
        for (field in view().pages.flatMap { it.fields }.filterNot { it.waits }) assertFalse(SettingsChanges.loosens(field.name, tighter, looser), field.name)
    }

    @Test
    fun `quiet hours may cross midnight, and working hours can't`() {
        assertTrue(view().field("quietHours").overnight)
        assertFalse(view().field("weekdayHours").overnight)
    }

    @Test
    fun `a field says what it does, as it stands, where the phone says`() {
        assertTrue(assertNotNull(view().field("weekendHours").note).contains("on a weekend day or a day off"))
        assertNull(view().field("felixName").note)
    }

    @Test
    fun `what's waiting shows, with its Cancel`() {
        val change = PendingChange("c", "workMinPerFreeMin", JsonPrimitive(1), "Minutes of work per free minute: 3 → 1", now, now + 3_600_000)
        val line = view(pending = listOf(change), settings = Settings(armed = true)).pending.single()
        assertEquals("c", line.id)
        assertTrue(line.line.startsWith("Minutes of work per free minute: 3 → 1: applies about"), line.line)
        assertNotNull(line.cancel)
    }

    @Test
    fun `a parent's code is offered only where the server holds the secret`() {
        assertFalse(view().parentCode)
        assertTrue(view(parentCode = true).parentCode)
        assertFalse(view(parentCode = true, writable = false).parentCode)
    }

    @Test
    fun `school's days are said as the phone says them`() {
        val school = SchoolCalendar(noSchool = listOf(NoSchool("2026-10-26", "2026-10-30", "half term")), halfDays = listOf(HalfDay("2026-11-02", 750)))
        assertEquals(listOf("No school, Mon 26 Oct to Fri 30 Oct · half term", "Half-day, Mon 2 Nov: ends 12:30"), view(school = school).school.map { it.line })
    }

    @Test
    fun `what Felix remembers can be deleted, the newest fact first`() {
        val memory = MemoryState(facts = listOf(Fact("sport", "football on Tuesdays", 1), Fact("travel", "away in June", 2)), rules = listOf(Rule("no messages before eight", 1)))
        val sheet = view(memory = memory)
        assertEquals(listOf("no messages before eight"), sheet.rules.map { it.line })
        assertEquals(listOf("travel: away in June", "sport: football on Tuesdays"), sheet.facts.map { it.line })
        assertTrue((sheet.rules + sheet.facts).all { it.delete != null })
        assertTrue(assertNotNull(sheet.factsRoom).endsWith("of the room for facts used."))
    }

    @Test
    fun `credentials say whether they're held, and never what they are`() {
        val groups = view(held = setOf("GmailAddress")).credentials
        assertEquals(listOf("Power Planner login", "Gmail"), groups.map { it.title })
        val gmail = groups.last().fields
        assertEquals(listOf(true, false), gmail.map { it.held })
        assertEquals(listOf(false, true), gmail.map { it.secret })
    }

    @Test
    fun `in mirror mode it's all there to read, and there's nothing to press`() {
        val change = PendingChange("c", "workMinPerFreeMin", JsonPrimitive(1), "A change", now, now + 3_600_000)
        val memory = MemoryState(rules = listOf(Rule("a rule", 1)))
        val sheet = view(pending = listOf(change), school = SchoolCalendar(halfDays = listOf(HalfDay("2026-11-02", 750))), memory = memory, writable = false)
        assertFalse(sheet.canChange)
        assertNull(sheet.pending.single().cancel)
        assertNull(sheet.school.single().delete)
        assertNull(sheet.rules.single().delete)
    }
}
