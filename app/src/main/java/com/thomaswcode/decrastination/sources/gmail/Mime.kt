package com.thomaswcode.decrastination.sources.gmail

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64

/** The bits of MIME (RFCs 2045–2047) that reading a message's text needs. Pure, for the tests. */
object Mime {

    /** Where a message's readable text is, from its BODYSTRUCTURE: the part to fetch and how to decode it. */
    data class TextPart(val section: String, val subtype: String, val encoding: String, val charset: String?)

    /**
     * The first `text/plain` part, else the first `text/html`, skipping attachments. Part numbers
     * follow RFC 3501 §6.4.5: a single-part message's body is part 1; a multipart's children are
     * 1, 2, …, and theirs 1.1, 1.2, ….
     */
    fun textPart(structure: ImapValue): TextPart? {
        val parts = mutableListOf<TextPart>()
        collect(structure, "", parts)
        return parts.firstOrNull { it.subtype == "plain" } ?: parts.firstOrNull { it.subtype == "html" }
    }

    private fun collect(node: ImapValue, prefix: String, out: MutableList<TextPart>) {
        val items = node.list
        if (items.isEmpty()) return
        if (items.first() is ImapValue.Items) {
            // Multipart: the child parts, then the subtype and its extension data.
            items.takeWhile { it is ImapValue.Items }.forEachIndexed { i, child ->
                collect(child, if (prefix.isEmpty()) "${i + 1}" else "$prefix.${i + 1}", out)
            }
            return
        }
        val type = items.getOrNull(0)?.string?.lowercase()
        val subtype = items.getOrNull(1)?.string?.lowercase() ?: return
        if (type != "text") return
        val params = items.getOrNull(2)?.list.orEmpty()
        val charset = (0 until params.size - 1 step 2)
            .firstOrNull { params[it].string.equals("charset", ignoreCase = true) }
            ?.let { params[it + 1].string }
        val encoding = items.getOrNull(5)?.string?.lowercase() ?: "7bit"
        // A text part's fields: type, subtype, params, id, description, encoding, size, lines;
        // then, if sent, MD5 and the disposition. An "attachment" isn't the message's text.
        val disposition = items.getOrNull(9)?.list?.firstOrNull()?.string
        if (disposition.equals("attachment", ignoreCase = true)) return
        out += TextPart(prefix.ifEmpty { "1" }, subtype, encoding, charset)
    }

    /** Decodes a part's bytes, which may be cut short by a partial fetch. */
    fun decode(bytes: ByteArray, part: TextPart): String {
        val raw = when (part.encoding) {
            "base64" -> base64(bytes)
            "quoted-printable" -> quotedPrintable(bytes)
            else -> bytes
        }
        val text = raw.toString(charset(part.charset))
        return if (part.subtype == "html") htmlToText(text) else text
    }

    fun charset(name: String?): Charset = runCatching { Charset.forName(name ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)

    /** Base64 with line breaks; a tail cut short by a partial fetch is dropped. */
    fun base64(bytes: ByteArray): ByteArray {
        val clean = bytes.decodeToString().filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        val whole = clean.substring(0, clean.length - clean.length % 4)
        return runCatching { Base64.getDecoder().decode(whole) }.getOrDefault(ByteArray(0))
    }

    fun quotedPrintable(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b == '='.code) {
                // "=" at a line's end is a soft break; "=XX" a byte.
                if (i + 1 < bytes.size && (bytes[i + 1] == '\r'.code.toByte() || bytes[i + 1] == '\n'.code.toByte())) {
                    i += if (i + 2 < bytes.size && bytes[i + 1] == '\r'.code.toByte() && bytes[i + 2] == '\n'.code.toByte()) 3 else 2
                    continue
                }
                val hex = if (i + 2 < bytes.size) "${bytes[i + 1].toInt().toChar()}${bytes[i + 2].toInt().toChar()}".toIntOrNull(16) else null
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(b)
            i++
        }
        return out.toByteArray()
    }

    private val ENCODED_WORD = Regex("""=\?([^?]+)\?([bBqQ])\?([^?]*)\?=""")
    private val BETWEEN_WORDS = Regex("""(\?=)\s+(=\?)""")

    /** RFC 2047 encoded words in a header ("=?UTF-8?Q?Caf=C3=A9?="); the whitespace between two is dropped. */
    fun decodeHeader(value: String): String {
        val joined = value.replace(BETWEEN_WORDS, "$1$2")
        return ENCODED_WORD.replace(joined) { match ->
            val (charset, encoding, text) = match.destructured
            val bytes = if (encoding.equals("B", ignoreCase = true)) {
                base64(text.encodeToByteArray())
            } else {
                quotedPrintable(text.replace('_', ' ').encodeToByteArray())
            }
            bytes.toString(charset(charset))
        }
    }

    private val DROPPED_BLOCKS = Regex("""<(style|script|head)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val LINE_TAGS = Regex("""<\s*(br|/p|/div|/tr|/li|/h\d)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]*>""")
    private val ENTITY = Regex("""&(#\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);""")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    /** A style or script block a partial fetch cut off before its end tag. */
    private val UNCLOSED_BLOCK = Regex("""<(style|script)\b[^>]*>.*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun htmlToText(html: String): String {
        val text = html.replace(DROPPED_BLOCKS, " ").replace(UNCLOSED_BLOCK, " ").replace(LINE_TAGS, "\n").replace(TAG, " ")
        return ENTITY.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x") -> name.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                name.startsWith("#") -> name.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> NAMED[name.lowercase()]
            } ?: match.value
        }
    }

    private val SPACES = Regex("""[ \t\x{00A0}]+""")
    private val BLANK_LINES = Regex("""\n\s*\n(\s*\n)+""")

    /**
     * Invisible padding marketing emails put in their preview text, as characters or, in plain
     * parts too, as entities: Warwick's had 4 000 characters of `&zwnj;` before its first word.
     */
    private val INVISIBLE = Regex("""[\x{200B}-\x{200F}\x{2060}\x{FEFF}\x{034F}\x{00AD}]|&(zwnj|zwj|shy|#8203|#8204|#8205|#847|#173|#xfeff);""", RegexOption.IGNORE_CASE)

    /** Drops invisible padding, collapses runs of spaces and blank lines, and cuts to [max] characters. */
    fun tidy(text: String, max: Int): String =
        text.replace(INVISIBLE, "").replace("\r\n", "\n").lines().joinToString("\n") { it.replace(SPACES, " ").trim() }
            .replace(BLANK_LINES, "\n\n").trim().take(max)
}
