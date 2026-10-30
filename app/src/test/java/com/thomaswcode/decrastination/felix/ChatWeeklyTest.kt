package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.WeeklyRule
import com.thomaswcode.decrastination.core.WeeklyRules
import com.thomaswcode.decrastination.enrich.InstructionAnswers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Weekly work in Felix's answer and the instructions' reading (D-124): a rule read from what he gives, changed, ended, and told him. */
class ChatWeeklyTest {
    private val now = Fixtures.at("2026-10-30T12:00")
    private val guitar = WeeklyRule("g1", "Guitar practice", 180, listOf(2, 4), where = "home", subject = "Music", setAt = now - 86_400_000L)

    private fun answer(vararg changes: String) = """{"reply":"added","changes":[${changes.joinToString(",")}]}"""

    private fun weekly(title: String = "Guitar practice", minutes: Int = 180, key: String = "", id: String = "") =
        """{"type":"Weekly","title":"$title","minutes":$minutes,"eventKey":"$key","afterTaskId":"$id"}"""

    private fun read(json: String, rules: List<WeeklyRule> = emptyList()) =
        assertNotNull(ChatAnswers.parse(json, emptyList(), emptyList(), About(), now, LONDON, context = ChatPrompts.Context("", "", weekly = rules)))

    private fun rule(json: String, rules: List<WeeklyRule> = emptyList()) = assertNotNull(read(json, rules).changes.single().weekly)

    @Test
    fun `a total alone is a rule the planner places`() {
        val rule = rule(answer(weekly()))
        assertEquals("Guitar practice" to 180, rule.title to rule.minutes)
        assertEquals(emptyList(), rule.days)
        assertEquals(0, rule.sessionMin)
        assertEquals("anywhere", rule.where)
        assertEquals(now, rule.setAt)
        assertTrue(rule.id.isNotEmpty())
    }

    @Test
    fun `days, a session, where and a subject are read from its key`() {
        val rule = rule(answer(weekly(key = "days:Thu,tue;session:45;where:home;subject:Music")))
        assertEquals(listOf(2, 4), rule.days)
        assertEquals(45, rule.sessionMin)
        assertEquals("home", rule.where)
        assertEquals("Music", rule.subject)
        assertEquals((1..7).toList(), rule(answer(weekly(key = "days:every"))).days)
    }

    @Test
    fun `what can't be kept is left out and said`() {
        for (bad in listOf(weekly(title = " "), weekly(minutes = 2), weekly(minutes = 5, key = "days:every"), weekly(key = "days:someday"), weekly(key = "session:1"), weekly(key = "where:school"), weekly(key = "at:18:00"), weekly(id = "gone"))) {
            val reading = read(answer(bad))
            assertTrue(reading.changes.isEmpty(), bad)
            assertTrue(reading.leftOut.single().contains("weekly rule"), bad)
        }
    }

    @Test
    fun `a change to a listed rule keeps its id and all it doesn't say`() {
        val changed = rule(answer(weekly(title = "", minutes = 240, id = "g1")), listOf(guitar))
        assertEquals(guitar.copy(minutes = 240, setAt = now), changed)
    }

    @Test
    fun `no more rules than the app takes`() {
        val full = List(WeeklyRules.MAX_RULES) { guitar.copy(id = "r$it") }
        assertTrue(read(answer(weekly()), full).changes.isEmpty())
    }

    @Test
    fun `only a listed rule can be ended`() {
        val end = """{"type":"WeeklyEnd","afterTaskId":"g1"}"""
        assertEquals(guitar, read(answer(end), listOf(guitar)).changes.single().weekly)
        val reading = read(answer(end))
        assertTrue(reading.changes.isEmpty())
        assertTrue(reading.leftOut.single().contains("isn't listed"))
    }

    @Test
    fun `the instructions' reader makes one too`() {
        val json = """{"understood":true,"question":null,"changes":[${weekly(minutes = 60)}]}"""
        val reading = assertIs<InstructionAnswers.Reading.Changes>(InstructionAnswers.parse(json, emptyList(), emptyList(), About(), now, LONDON, rules = listOf(guitar)))
        assertEquals(ChangeType.Weekly, reading.changes.single().type)
    }

    @Test
    fun `a rule is said in a line, and ended`() {
        assertEquals("“Guitar practice”: 3 h a week, Tue, Thu, at home", InstructionRules.describe(Change(ChangeType.Weekly, weekly = guitar), emptyMap(), LONDON))
        assertEquals("“Reading”: 1 h a week, in 20 min sessions", InstructionRules.weeklyLine(WeeklyRule("r", "Reading", 60, sessionMin = 20)))
        assertEquals("“Guitar practice” no longer comes round every week", InstructionRules.describe(Change(ChangeType.WeeklyEnd, weekly = guitar), emptyMap(), LONDON))
    }

    @Test
    fun `he's told the rules by id, with how this week and the last went`() {
        val applied = listOf(Instruction("i1", "", at = now, state = InstructionStatus.Applied, changes = listOf(Change(ChangeType.Weekly, weekly = guitar)), appliedAt = now))
        val monday = WeeklyRules.mondayOf(now, LONDON)
        val last = WeeklyRules.make(guitar, monday.minusWeeks(1), monday.minusWeeks(1).atStartOfDay(LONDON).toInstant().toEpochMilli(), LONDON)!!.copy(workedMin = 40)
        val context = ChatPrompts.context(listOf(last), emptyList(), applied, now, LONDON)
        assertTrue("- g1 | “Guitar practice”: 3 h a week, Tue, Thu, at home | - | 40 of 180 min" in context, context)
        assertTrue("Weekly rules" !in ChatPrompts.context(emptyList(), emptyList(), emptyList(), now, LONDON))
    }
}
