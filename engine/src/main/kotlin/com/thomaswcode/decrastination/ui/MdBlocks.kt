package com.thomaswcode.decrastination.ui

/**
 * The little of Markdown that Claude's words use (a review's summary has "one small table"): pipe tables, lists,
 * headings and paragraphs, as blocks. The website's `mdBlocks.ts` reads the same and is held to the same cases
 * (`fixtures/web/markdown.json`). Anything else is left as the words were written.
 */
object MdBlocks {
    sealed interface Block

    data class Paragraph(val text: String) : Block

    data class Heading(val level: Int, val text: String) : Block

    data class Items(val ordered: Boolean, val start: Int, val items: List<String>) : Block

    data class Table(val header: List<String>, val rows: List<List<String>>) : Block

    // (?U): Unicode's spaces are spaces, as JavaScript's \s has them (a no-break space after a list's number).
    private val SEPARATOR = Regex("""(?U)\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*""")
    // Closing hashes go only after a space, so "C#" keeps its own.
    private val HEADING = Regex("""(?U)(#{1,6})\s+(.*?)(?:\s+#+)?\s*""")
    private val BULLET = Regex("""(?U)\s*[-*•]\s+(.*)""")
    // Its digits ASCII's alone, as JavaScript's \d is, where (?U) would take any script's.
    private val NUMBERED = Regex("""(?U)\s*([0-9]+)[.)]\s+(.*)""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""")

    /** A table row's cells, its outer pipes dropped. */
    private fun cells(line: String): List<String> {
        var row = line.trim()
        if (row.startsWith("|")) row = row.drop(1)
        if (row.endsWith("|")) row = row.dropLast(1)
        return row.split("|").map { it.trim() }
    }

    /** A row the header's width: cut, or filled with empty cells. */
    private fun fitted(row: List<String>, width: Int): List<String> = List(width) { row.getOrElse(it) { "" } }

    fun parse(text: String): List<Block> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val blocks = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun endPara() {
            if (para.isNotEmpty()) blocks += Paragraph(para.joinToString("\n"))
            para.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            // A table's separator has a cell for each of its header's, or it's a line of words and a rule.
            if ("|" in line && i + 1 < lines.size && SEPARATOR.matches(lines[i + 1]) && cells(lines[i + 1]).size == cells(line).size) {
                endPara()
                val header = cells(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && "|" in lines[i] && lines[i].isNotBlank()) {
                    rows += fitted(cells(lines[i]), header.size)
                    i++
                }
                blocks += Table(header, rows)
                continue
            }
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                endPara()
                blocks += Heading(heading.groupValues[1].length, heading.groupValues[2])
                i++
                continue
            }
            val bullet = BULLET.matchEntire(line)
            val numbered = if (bullet == null) NUMBERED.matchEntire(line) else null
            if (bullet != null || numbered != null) {
                endPara()
                val ordered = numbered != null
                // A numbered list starts where its first number says, as words can come between its items.
                val start = numbered?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val item = (if (ordered) NUMBERED else BULLET).matchEntire(lines[i]) ?: break
                    items += item.groupValues[if (ordered) 2 else 1]
                    i++
                }
                blocks += Items(ordered, start, items)
                continue
            }
            if (line.isBlank()) endPara() else para += line
            i++
        }
        endPara()
        return blocks
    }

    /** A line's **bold** runs, in turn with the plain ones: odd places are bold. */
    fun runs(text: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        for (match in BOLD.findAll(text)) {
            out += text.substring(from, match.range.first)
            out += match.groupValues[1]
            from = match.range.last + 1
        }
        out += text.substring(from)
        return out
    }
}
