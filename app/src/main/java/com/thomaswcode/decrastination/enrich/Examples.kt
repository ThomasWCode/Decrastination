package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.Enrichments

/**
 * Worked examples for each job (AI-07), made up, each an item as the model reads it ([Prompts.describe])
 * and the answer it should give, on one line: appended to the job's system prompt. Every answer is
 * one the checks pass whole (the tests read each through [Answers.read]).
 */
object Examples {
    /** An item as the model reads it, the answer to it, and the item's own due date where it has one (for the tests' check). */
    data class Example(val input: String, val answer: String, val due: String? = null)

    val EMAIL = listOf(
        Example(
            """
            Today: Friday 9 October 2026, 17:00
            Received: Friday 9 October 2026 08:15
            From: Sixth Form Office <no-reply@school.example>
            Subject: Year 12 field trip consent form

            Email:
            Please complete the online consent form for the Year 12 geography field trip by 9am on Wednesday 14 October. Late forms can't be accepted.
            """.trimIndent(),
            """{"kind":"Admin","actionableFrom":null,"deadline":"2026-10-14T09:00","deadlineQuoted":true,"effortMin":15,"nextStep":"Fill in the field trip consent form online","question":null,"reason":"by 9am on Wednesday 14 October","blocks":[]}""",
        ),
        Example(
            """
            Today: Friday 9 October 2026, 17:00
            Received: Friday 9 October 2026 16:40
            From: Parent <parent@example.com>
            Subject: Open days

            Email:
            Three open days worth booking: Bristol (book by 20 October), Exeter (book by 1 November) and Bath (book by 15 November). Each takes about ten minutes to register for.
            """.trimIndent(),
            """{"kind":"Admin","actionableFrom":null,"deadline":"2026-11-15","deadlineQuoted":true,"effortMin":30,"nextStep":"Register for the Bristol open day","question":null,"reason":"Bristol (book by 20 October), Exeter (book by 1 November) and Bath (book by 15 November)","blocks":[{"title":"Register for the Bristol open day","minutes":10,"from":null,"due":"2026-10-20"},{"title":"Register for the Exeter open day","minutes":10,"from":null,"due":"2026-11-01"},{"title":"Register for the Bath open day","minutes":10,"from":null,"due":"2026-11-15"}]}""",
        ),
        Example(
            """
            Today: Friday 9 October 2026, 17:00
            Received: Thursday 8 October 2026 21:05
            From: the student (a note to self)
            Subject: forces lecture recording

            Email:
            https://example.com/recordings/forces
            """.trimIndent(),
            """{"kind":"Admin","actionableFrom":null,"deadline":null,"deadlineQuoted":false,"effortMin":10,"nextStep":"Watch the recording","question":null,"reason":"","blocks":[]}""",
        ),
    )

    val ASSIGNMENT = listOf(
        Example(
            """
            Today: Friday 9 October 2026, 17:00
            Title: Vocabulary and reading
            Class: German
            Due: Tuesday 13 October 2026 08:30

            Details:
            1. Learn vocabulary 2.1 and 2.2 for the unit test on Thursday 15 October.
            2. Read the article on page 34 and answer questions 1-5 in full sentences.
            """.trimIndent(),
            """{"subSteps":[{"title":"Learn vocabulary 2.1 and 2.2","minutes":30,"ankiSections":["2.1","2.2"],"where":"anywhere","from":null,"due":null},{"title":"Read the article on page 34 and answer questions 1-5 in full sentences","minutes":45,"ankiSections":[],"where":"home","from":null,"due":null}],"effortMin":75,"ankiSections":["2.1","2.2"],"testDate":"2026-10-15","question":null,"reason":"for the unit test on Thursday 15 October"}""",
            due = "2026-10-13T08:30",
        ),
        Example(
            """
            Today: Friday 9 October 2026, 17:00
            Title: Past paper 1
            Class: Biology
            Due: Wednesday 14 October 2026 08:30

            Details:
            Print the attached past paper and answer questions 1-6 on pages 2-5. Mark your answers with the mark scheme on Teams, then upload a photo of your marked work.
            """.trimIndent(),
            """{"subSteps":[{"title":"Past paper 1, questions 1-6 (print it first), then mark it and upload a photo","minutes":55,"ankiSections":[],"where":"home","from":null,"due":null}],"effortMin":55,"ankiSections":[],"testDate":null,"question":null,"reason":""}""",
            due = "2026-10-14T08:30",
        ),
        Example(
            """
            Today: Friday 9 October 2026, 17:00
            Title: Exercise 6B
            Class: Maths
            Due: Friday 16 October 2026 08:30

            Details:
            Complete all of exercise 6B (questions 1-20) and check your answers in the back of the book.
            """.trimIndent(),
            """{"subSteps":[{"title":"Exercise 6B, questions 1-20, then check the answers in the back","minutes":100,"ankiSections":[],"where":"anywhere","from":null,"due":null}],"effortMin":100,"ankiSections":[],"testDate":null,"question":null,"reason":""}""",
            due = "2026-10-16T08:30",
        ),
        Example(
            """
            Today: Friday 9 October 2026, 17:00
            Title: Worksheet 4
            Class: Physics
            Due: Monday 12 October 2026 08:30

            Details:
            Complete worksheet 4 on momentum, questions 1-8.
            """.trimIndent(),
            """{"subSteps":[{"title":"Complete worksheet 4, questions 1-8","minutes":40,"ankiSections":[],"where":"anywhere","from":null,"due":null}],"effortMin":40,"ankiSections":[],"testDate":null,"question":null,"reason":""}""",
            due = "2026-10-12T08:30",
        ),
    )

    val EFFORT = listOf(
        Example(
            """
            Today: Friday 9 October 2026, 17:00
            Title: Careers interview
            Due: Wednesday 14 October 2026 13:10

            Details:
            Bring your CV.
            """.trimIndent(),
            """{"effortMin":5,"blocks":[]}""",
            due = "2026-10-14T13:10",
        ),
        Example(
            """
            Today: Friday 9 October 2026, 17:00
            Title: Revise for the maths mock
            Class: Maths
            Due: Friday 23 October 2026 08:30

            Details:
            Paper 1: algebra, functions and calculus.
            """.trimIndent(),
            """{"effortMin":120,"blocks":[]}""",
            due = "2026-10-23T08:30",
        ),
    )

    fun of(job: Enrichments.Job): List<Example> = when (job) {
        Enrichments.Job.Email -> EMAIL
        Enrichments.Job.Assignment -> ASSIGNMENT
        Enrichments.Job.Effort -> EFFORT
    }

    /** [job]'s examples as its prompt has them: each item indented, its answer on one line. */
    fun text(job: Enrichments.Job): String = "Examples (made up, to show the form of an answer):\n\n" +
        of(job).joinToString("\n\n") { "Item:\n" + it.input.prependIndent("    ") + "\nAnswer: " + it.answer }
}
