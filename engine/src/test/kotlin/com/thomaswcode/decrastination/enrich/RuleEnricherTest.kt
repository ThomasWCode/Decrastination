package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.AnkiRules
import com.thomaswcode.decrastination.core.Enrichments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RuleEnricherTest {
    @Test
    fun `listed parts become steps sharing the estimate`() = runBlocking {
        val task = assignment("Hausaufgaben\n1. Complete the reading task: past paper ( June 2022 )\n2. Mark it and highlight your mistakes")
        val steps = RuleEnricher().enrich(task, Enrichments.Job.Assignment, NOW).enrichment!!.subSteps!!
        assertEquals(listOf("Complete the reading task: past paper ( June 2022 )", "Mark it and highlight your mistakes"), steps.map { it.title })
        assertEquals(listOf(20, 20), steps.map { it.minutes })
    }

    @Test
    fun `the rules' parts over an hour are in sittings too`() = runBlocking {
        val task = assignment("1. Write the essay\n2. Make the poster").copy(sourceEffortMin = 240)
        val steps = RuleEnricher().enrich(task, Enrichments.Job.Assignment, NOW).enrichment!!.subSteps!!
        assertEquals(
            listOf("Write the essay" to 60, "Continue and finish: Write the essay" to 60, "Make the poster" to 60, "Continue and finish: Make the poster" to 60),
            steps.map { it.title to it.minutes },
        )
    }

    @Test
    fun `the rules share the source's estimate, not a stale one of the model's`() = runBlocking {
        val task = assignment("1. Complete the reading task\n2. Mark it").copy(sourceEffortMin = 40, aiEffortMin = 120)
        val steps = RuleEnricher().enrich(task, Enrichments.Job.Assignment, NOW).enrichment!!.subSteps!!
        assertEquals(listOf(20, 20), steps.map { it.minutes })
    }

    @Test
    fun `only vocabulary and nothing else counts as vocabulary-only`() {
        assertTrue(AnkiRules.vocabularyOnly("Learn vocabulary p46-47/ 2.2/2.3 ( vocabulary test ! )"))
        assertTrue(AnkiRules.vocabularyOnly("Revise vocabulary 3.1 on Anki"))
        assertFalse(AnkiRules.vocabularyOnly("Learn vocabulary 2.2 and complete exercises 3-5"))
        // Work it doesn't know is still work.
        assertFalse(AnkiRules.vocabularyOnly("Learn vocabulary 2.2 and revise the grammar"))
        assertFalse(AnkiRules.vocabularyOnly("Complete exercise 4"))
    }

    @Test
    fun `a part that mixes vocabulary with other work isn't tagged, so it stays planned`() {
        val steps = RuleEnricher.steps("1. Learn vocabulary 2.2 and complete exercises 3-5\n2. Translation of the text", 40)!!
        assertEquals(listOf(emptyList<String>(), emptyList()), steps.map { it.ankiSections })
    }

    @Test
    fun `a part that's learning numbered vocabulary is a step tagged with its sections`() {
        val steps = RuleEnricher.steps("1. Learn vocabulary p20-21/ 3.1/3.2 ( vocabulary test ! )\n2. Complete the reading task", 40)!!
        assertEquals(listOf(listOf("3.1", "3.2"), emptyList()), steps.map { it.ankiSections })
    }

    @Test
    fun `dashes and a number without its space count, a section number doesn't`() {
        assertEquals(2, RuleEnricher.steps("- Learn vocabulary - Wohnen in der Stadt\n- Translation - from the booklet", 40)!!.size)
        assertEquals(2, RuleEnricher.steps("1.Past paper: print it out\n2.Mark it", 40)!!.size)
        // "1.2 Stadt" is a section, and one item is no list.
        assertNull(RuleEnricher.steps("1.2 Stadt und Land - both ways.\nPage 9 - exercises 2 and 3.", 40))
        assertNull(RuleEnricher.steps("1. Do page 7", 40))
    }

    @Test
    fun `a long item is shortened, and every step gets five minutes at least`() {
        val steps = RuleEnricher.steps("1. " + "a".repeat(100) + "\n2. b\n3. c\n4. d\n5. e\n6. f\n7. g\n8. h\n9. i\n10. j", 30)!!
        assertEquals(60, steps.first().title.length)
        assertTrue(steps.first().title.endsWith("…"))
        assertTrue(steps.all { it.minutes == 5 })
    }
}
