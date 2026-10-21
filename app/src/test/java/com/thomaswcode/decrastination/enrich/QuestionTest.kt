package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Enrichments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestionTest {
    @Test
    fun `a question it can't settle is read, and only one worth asking`() {
        val text = """{"kind":"Admin","actionableFrom":null,"deadline":"2026-10-16","deadlineQuoted":true,"effortMin":10,"nextStep":"Sign the form","question":" Is it this Friday or next? ","blocks":[]}"""
        assertEquals("Is it this Friday or next?", ReadingAnswers.parse(Enrichments.Job.Email, text, email(), "claude-opus-5-5", NOW, LONDON)!!.question)
        assertNull(ReadingAnswers.parse(Enrichments.Job.Email, text.replace("\" Is it this Friday or next? \"", "null"), email(), "claude-opus-5-5", NOW, LONDON)!!.question)
    }

    @Test
    fun `an answer is read with the task, and only a task with one is read again`() {
        val task = email()
        assertEquals(Enrichments.inputHash(task), Enrichments.inputHash(task.copy(answers = emptyList())))
        val answered = task.copy(answers = listOf("Next Friday, the 23rd"))
        assertNotEquals(Enrichments.inputHash(task), Enrichments.inputHash(answered))
        assertTrue("The student says: Next Friday, the 23rd" in ReadingPrompts.describe(answered, Enrichments.Job.Email, NOW, LONDON))
    }
}
