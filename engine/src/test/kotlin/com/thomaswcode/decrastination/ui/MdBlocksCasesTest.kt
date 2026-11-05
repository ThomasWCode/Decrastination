package com.thomaswcode.decrastination.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Markdown's blocks held to the cases `web/src/mdBlocks.test.ts` holds the website's to, so a table reads as one on both. */
class MdBlocksCasesTest {
    /** A block as the cases write it: [kind] says which of the others it has. */
    @Serializable
    private data class Shape(
        val kind: String,
        val text: String = "",
        val level: Int = 0,
        val ordered: Boolean = false,
        val items: List<String> = emptyList(),
        val header: List<String> = emptyList(),
        val rows: List<List<String>> = emptyList(),
    ) {
        fun block(): MdBlocks.Block = when (kind) {
            "p" -> MdBlocks.Paragraph(text)
            "h" -> MdBlocks.Heading(level, text)
            "list" -> MdBlocks.Items(ordered, items)
            "table" -> MdBlocks.Table(header, rows)
            else -> error("no kind $kind")
        }
    }

    @Serializable
    private data class Parse(val name: String, val text: String, val blocks: List<Shape>)

    @Serializable
    private data class Runs(val text: String, val runs: List<String>)

    @Serializable
    private data class Cases(val parse: List<Parse>, val runs: List<Runs>)

    private val cases: Cases = Json.decodeFromString(Cases.serializer(), File("../fixtures/web/markdown.json").readText())

    @Test
    fun `words read into blocks`() = cases.parse.forEach { assertEquals(it.blocks.map(Shape::block), MdBlocks.parse(it.text), it.name) }

    @Test
    fun `bold runs`() = cases.runs.forEach { assertEquals(it.runs, MdBlocks.runs(it.text), it.text) }
}
