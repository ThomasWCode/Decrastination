package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** A line's **bold** runs as bold. */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    MdBlocks.runs(text).forEachIndexed { i, run -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(run) } else append(run) }
}

/**
 * Claude's words as Markdown's blocks lay them out ([MdBlocks]): a table as a table, its columns sharing the width,
 * a list as a list, the rest as the words were written. The website draws the same (`web/src/Markdown.tsx`).
 */
@Composable
fun MarkdownText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MdBlocks.parse(text).forEach { block ->
            when (block) {
                is MdBlocks.Paragraph -> Text(inline(block.text), style = style)
                is MdBlocks.Heading -> Text(inline(block.text), style = style, fontWeight = FontWeight.Bold)
                is MdBlocks.Items -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEachIndexed { n, item ->
                        Row {
                            Text(if (block.ordered) "${n + 1}." else "•", style = style, modifier = Modifier.padding(end = 8.dp))
                            Text(inline(item), style = style, modifier = Modifier.weight(1f))
                        }
                    }
                }
                is MdBlocks.Table -> Column {
                    TableRow(block.header, style, bold = true)
                    block.rows.forEach { row ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TableRow(row, style, bold = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, style: TextStyle, bold: Boolean) {
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        cells.forEach { cell ->
            Text(inline(cell), style = style, fontWeight = if (bold) FontWeight.Bold else null, modifier = Modifier.weight(1f))
        }
    }
}
