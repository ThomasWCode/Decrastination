package com.thomaswcode.decrastination.sources.gmail

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64
import java.util.Locale

/** The bits of MIME (RFCs 2045–2047) that reading a message's text needs. Pure, for the tests. */
object Mime {

    /**
     * Where a message's readable text is, from its BODYSTRUCTURE: the part to fetch and how to decode
     * it, and its [size] in bytes as sent, where given.
     */
    data class TextPart(val section: String, val subtype: String, val encoding: String, val charset: String?, val size: Long? = null)

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
        out += TextPart(prefix.ifEmpty { "1" }, subtype, encoding, charset, items.getOrNull(6)?.string?.toLongOrNull())
    }

    /** A file attached to a message (DX-01): its name, type (`application/pdf`) and size in bytes, where given. */
    @Serializable
    data class Attachment(val name: String, val type: String, val size: Long? = null) {
        /** "consent-form.pdf (110 KB)", or an attached email's "Trip details (an email, 16 KB)": as the sheet shows it and the model reads it. */
        val label: String
            get() {
                val about = listOfNotNull("an email".takeIf { type == "message/rfc822" }, size?.let(::sizeLabel)).joinToString(", ")
                return if (about.isEmpty()) name else "$name ($about)"
            }
    }

    /** Bytes as the sheet says them: whole kilobytes, or megabytes to one place past a thousand of them. */
    private fun sizeLabel(bytes: Long): String {
        val kb = maxOf(1L, (bytes + 512) / 1024)
        return if (kb < 1024) "$kb KB" else "%.1f MB".format(Locale.UK, bytes / (1024.0 * 1024.0))
    }

    /**
     * Every file attached to a message, from its BODYSTRUCTURE, walked as [textPart] walks it: a
     * part whose disposition is `attachment`, or one that isn't text and has a name, by its
     * disposition's `filename` or its own `name`. An inline image isn't one, named or not: a
     * signature's logo is an inline `image001.png`. Where a part's disposition is depends on its
     * type (RFC 3501 §7.4.2): after a text part's line count, a basic part's MD5, or an attached
     * message's envelope, body and lines. Sizes are of the part as sent: base64's are cut by a
     * quarter to the file's.
     */
    fun attachments(structure: ImapValue): List<Attachment> {
        val out = mutableListOf<Attachment>()
        collectAttachments(structure, out)
        return out
    }

    private fun collectAttachments(node: ImapValue, out: MutableList<Attachment>) {
        val items = node.list
        if (items.isEmpty()) return
        if (items.first() is ImapValue.Items) {
            items.takeWhile { it is ImapValue.Items }.forEach { collectAttachments(it, out) }
            return
        }
        val type = items.getOrNull(0)?.string?.lowercase() ?: return
        val subtype = items.getOrNull(1)?.string?.lowercase() ?: return
        val dispositionAt = when {
            type == "text" -> 9
            type == "message" && subtype == "rfc822" -> 11
            else -> 8
        }
        val disposition = items.getOrNull(dispositionAt)?.list.orEmpty()
        val how = disposition.firstOrNull()?.string?.lowercase()
        val name = parameter(disposition.getOrNull(1), "filename") ?: parameter(items.getOrNull(2), "name")
        val attached = when {
            how == "attachment" -> true
            type == "image" && how == "inline" -> false
            else -> type != "text" && name != null
        }
        if (!attached) return
        val size = items.getOrNull(6)?.string?.toLongOrNull()?.let { if (items.getOrNull(5)?.string.equals("base64", ignoreCase = true)) it * 3 / 4 else it }
        // An attached email with no file name goes by its own subject, from its envelope.
        val subject = if (type == "message" && subtype == "rfc822") items.getOrNull(7)?.list?.getOrNull(1)?.string?.let(::decodeHeader)?.trim()?.takeIf { it.isNotEmpty() } else null
        out += Attachment(name ?: subject ?: if (type == "message") "an attached email" else "unnamed $type/$subtype", "$type/$subtype", size)
    }

    /**
     * A body or disposition parameter's value, by [key], as a name may be written: plain, as encoded
     * words (RFC 2047), or as RFC 2231's `key*` (`utf-8''consent%20form.pdf`).
     */
    private fun parameter(list: ImapValue?, key: String): String? {
        val params = list?.list.orEmpty()
        for (i in 0 until params.size - 1 step 2) {
            val name = params[i].string?.lowercase() ?: continue
            val value = params[i + 1].string ?: continue
            if (name == key) return decodeHeader(value).trim().takeIf { it.isNotEmpty() }
            if (name == "$key*") return extended(value)?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** RFC 2231's `charset'language'percent-encoded` value. */
    private fun extended(value: String): String? {
        val parts = value.split('\'', limit = 3)
        if (parts.size != 3) return value
        val text = parts[2]
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val hex = if (text[i] == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                bytes.write(hex)
                i += 3
            } else {
                bytes.write(text[i].code)
                i++
            }
        }
        return bytes.toByteArray().toString(charset(parts[0].ifEmpty { null }))
    }

    /** Decodes a part's bytes, which may be cut short by a partial fetch. */
    fun decode(bytes: ByteArray, part: TextPart): String {
        val text = decodeRaw(bytes, part)
        return if (part.subtype == "html") htmlToText(text) else text
    }

    /** A part's bytes as the text they encode, HTML still HTML. */
    private fun decodeRaw(bytes: ByteArray, part: TextPart): String {
        val raw = when (part.encoding) {
            "base64" -> base64(bytes)
            "quoted-printable" -> quotedPrintable(bytes)
            else -> bytes
        }
        return raw.toString(charset(part.charset))
    }

    /** A text with what it quotes, or its signature, cut (DX-06), and how many characters of text that was, spaces collapsed. */
    data class Cut(val text: String, val cutChars: Int)

    /**
     * A part's text as a task and the model have it: decoded, flattened, its quotes and signature
     * cut ([cutQuoted]: HTML's before it's flattened, then plain text's), and tidied to [max].
     * [forwarded]: its message's subject says it's a forward ([isForward]).
     */
    fun readable(bytes: ByteArray, part: TextPart, max: Int, forwarded: Boolean = false): Cut {
        val raw = decodeRaw(bytes, part)
        val html = if (part.subtype == "html") cutQuoted(raw, html = true, forwarded) else null
        val plain = cutQuoted(html?.let { htmlToText(it.text) } ?: raw, html = false, forwarded)
        return Cut(tidy(plain.text, max), plain.cutChars + (html?.cutChars ?: 0))
    }

    private val FORWARD_SUBJECT = Regex("""^\s*fwd?\s*:""", RegexOption.IGNORE_CASE)

    /** A subject that says its message is a forward ("Fw: …", "Fwd: …"), not a reply to one ("Re: Fw: …"). */
    fun isForward(subject: String): Boolean = FORWARD_SUBJECT.containsMatchIn(subject)

    /**
     * [text] without the earlier messages it quotes, or its signature (DX-06): what the model reads
     * should be what's new in it, not a request quoted from further down. [html]: every
     * `<blockquote>` and every `gmail_quote` div goes whole, nested ones matched by depth; a forward
     * is in one too (Gmail's div, Apple Mail's blockquote), so one that begins with the forward
     * marker stays, and the quotes inside it go. Plain: cut from the first line that starts a quote
     * (an `On … wrote:` line, perhaps wrapped onto the next; `-----Original Message-----`; a `From:`
     * line with `Sent:` or `To:` within three lines) or a signature (`-- `); after a forward's
     * marker its own header is its message's, so the cut is at the marker after that.
     * [forwarded]: the message is a forward by its subject, as Outlook's are, with no marker but
     * a header like a reply's: its first header block, and its first quoting subtree, are what it
     * forwards, kept.
     */
    fun cutQuoted(text: String, html: Boolean, forwarded: Boolean = false): Cut {
        if (html) {
            val removed = mutableListOf<String>()
            val kept = cutHtml(text, removed, keepFirst = forwarded)
            return Cut(kept, removed.sumOf { tidy(htmlToText(it), Int.MAX_VALUE).length })
        }
        val lines = text.lines()
        var forward = forwarded
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (FORWARD.matches(line)) {
                forward = true
                continue
            }
            val header = FROM_HEADER.matches(line) && lines.drop(i + 1).take(3).any { SENT_OR_TO.containsMatchIn(it.trim()) }
            val quote = header || wrote(line, lines.getOrNull(i + 1)) || ORIGINAL.matches(line) || lines[i].trimEnd('\r') == "-- " || line == "--"
            if (!quote) continue
            // A forward's own header, the first after its marker: its message follows.
            if (forward && header) {
                forward = false
                continue
            }
            // Outlook rules a line of underscores above its header: it goes with the quote.
            val from = if (i > 0 && RULE.matches(lines[i - 1].trim())) i - 1 else i
            val cut = lines.drop(from).joinToString("\n")
            return Cut(lines.take(from).joinToString("\n").trimEnd(), tidy(cut, Int.MAX_VALUE).length)
        }
        return Cut(text, 0)
    }

    /** An `On … wrote:` attribution, written with a date or time in it, on one line or wrapped onto [next]. */
    private fun wrote(line: String, next: String?): Boolean {
        if (!line.startsWith("On ")) return false
        return WROTE.matches(line) || (next != null && WROTE.matches(line + " " + next.trim()))
    }

    /**
     * [html] without its quoting subtrees, each whole, added to [removed]; a forward's kept, what it
     * quotes cut: one beginning with the forward marker, or with [keepFirst] the first of them.
     */
    private fun cutHtml(html: String, removed: MutableList<String>, keepFirst: Boolean = false): String {
        val out = StringBuilder()
        var at = 0
        var first = keepFirst
        while (true) {
            val open = QUOTE_OPEN.find(html, at) ?: break
            out.append(html, at, open.range.first)
            val tag = open.groupValues[1].ifEmpty { open.groupValues[2] }.lowercase()
            val (closeStart, closeEnd) = closeOf(html, open.range.last + 1, tag)
            val whole = html.substring(open.range.first, closeEnd)
            val forward = first || FORWARD_TEXT.containsMatchIn(tidy(htmlToText(whole), 200).lineSequence().firstOrNull().orEmpty())
            first = false
            if (forward) {
                out.append(html, open.range.first, open.range.last + 1)
                out.append(cutHtml(html.substring(open.range.last + 1, closeStart), removed))
                out.append(html, closeStart, closeEnd)
            } else {
                removed += whole
            }
            at = closeEnd
        }
        out.append(html, at, html.length)
        return out.toString()
    }

    /**
     * Where the [tag] opened just before [from] closes: the closing tag's start and end, counting the
     * same tags opened inside it. One a partial fetch cut off runs to the end.
     */
    private fun closeOf(html: String, from: Int, tag: String): Pair<Int, Int> {
        var depth = 1
        for (match in Regex("""<(/?)$tag\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html, from)) {
            when {
                match.groupValues[1] == "/" -> depth--
                !match.value.endsWith("/>") -> depth++
            }
            if (depth == 0) return match.range.first to match.range.last + 1
        }
        return html.length to html.length
    }

    /** A quote's start in HTML: a blockquote, or a div of Gmail's `gmail_quote` class; the tag is whichever group matched. */
    private val QUOTE_OPEN = Regex(
        """<(blockquote)\b[^>]*>|<(div)\b[^>]*?\bclass\s*=\s*(["'])[^"']*?\bgmail_quote\b[^"']*?\3[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    /** "On Thu, 8 Oct 2026 at 18:02, Dad <dad@…> wrote:": a date or a time in it, so "On Monday Sir wrote:" in a message isn't taken for one. */
    private val WROTE = Regex("""^On .{0,200}\d.{0,200} wrote:$""")
    private val ORIGINAL = Regex("""^-{2,}\s*Original Message\s*-{2,}$""", RegexOption.IGNORE_CASE)
    private val FROM_HEADER = Regex("""^From: .+$""")
    private val SENT_OR_TO = Regex("""^(Sent|To): """)
    private val RULE = Regex("""^[_-]{10,}$""")

    /** A forward's marker, Gmail's ("---------- Forwarded message ---------") or Apple Mail's. */
    private val FORWARD = Regex("""^-{2,}\s*Forwarded message\s*-{2,}$|^Begin forwarded message:$""", RegexOption.IGNORE_CASE)
    private val FORWARD_TEXT = Regex("""^-{2,}\s*Forwarded message|^Begin forwarded message""", RegexOption.IGNORE_CASE)

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
                name.startsWith("#x") -> codePoint(name.substring(2).toIntOrNull(16))
                name.startsWith("#") -> codePoint(name.substring(1).toIntOrNull())
                else -> NAMED[name.lowercase()]
            } ?: match.value
        }
    }

    /** A numeric entity's character, or null for one that names no character (`&#1114112;`): left as written. */
    private fun codePoint(value: Int?): String? = value?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }

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
