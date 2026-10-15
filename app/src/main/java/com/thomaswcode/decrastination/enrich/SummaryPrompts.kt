package com.thomaswcode.decrastination.enrich

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The weekly summary's prompt and answer (NF-02). Pure. */
object SummaryPrompts {
    val SYSTEM = """
        Write the student's weekly summary for their dad, in the student's voice, first person, plainly, no excuses and no spin: what got done, what didn't and why, what changed. Short paragraphs and one small table. Under 300 words.
    """.trimIndent()

    val SCHEMA: Map<String, Any> = InstructionPrompts.obj("summary" to InstructionPrompts.STRING)

    /** The most of it kept: room for 300 words and a table. */
    const val MAX_CHARS = 3_000

    @Serializable
    private data class Answer(val summary: String = "")

    private val json = Json { ignoreUnknownKeys = true }

    /** [text] as the summary, or null where it can't be read or says nothing. */
    fun parse(text: String): String? = runCatching { json.decodeFromString(Answer.serializer(), text) }.getOrNull()?.summary?.trim()?.take(MAX_CHARS)?.takeIf { it.isNotEmpty() }
}
