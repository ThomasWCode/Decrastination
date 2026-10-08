package com.thomaswcode.decrastination.sources.gmail

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A value in an IMAP response (RFC 3501 §4): an atom, a string (quoted or literal), NIL, or a list. */
sealed interface ImapValue {
    data class Atom(val text: String) : ImapValue

    class Str(val bytes: ByteArray) : ImapValue {
        val text: String get() = bytes.decodeToString()
        override fun equals(other: Any?) = other is Str && other.bytes.contentEquals(bytes)
        override fun hashCode() = bytes.contentHashCode()
        override fun toString() = "Str($text)"
    }

    data object Nil : ImapValue

    data class Items(val items: List<ImapValue>) : ImapValue
}

/** An atom's or a string's text; null for NIL or a list. */
val ImapValue.string: String?
    get() = when (this) {
        is ImapValue.Atom -> text
        is ImapValue.Str -> text
        else -> null
    }

val ImapValue.list: List<ImapValue> get() = (this as? ImapValue.Items)?.items.orEmpty()

/**
 * One response line. [tag] is `*` for untagged data, `+` for a continuation, or a command's tag.
 * Status responses (OK, NO, BAD, BYE, PREAUTH) keep the rest of the line as [text] rather than
 * parsing it, since human-readable text needn't balance its brackets or quotes.
 */
data class ImapLine(val tag: String, val values: List<ImapValue>, val text: String? = null) {
    val status: String? get() = if (text != null) values.firstOrNull()?.string?.uppercase() else null
}

/**
 * Reads responses from the server. Literals (`{n}` then n bytes) may contain anything, line
 * breaks included, and a response carries on after one; lists nest. Section specs such as
 * `BODY[HEADER.FIELDS (FROM)]` are one atom, spaces and brackets and all.
 */
class ImapReader(input: InputStream) {
    private val input = input.buffered()
    private var peeked = -2

    fun readLine(): ImapLine {
        val tag = atomUntilSpace()
        if (tag == "+") return ImapLine(tag, emptyList(), restOfLine())
        val values = mutableListOf<ImapValue>()
        while (true) {
            skipSpaces()
            if (atLineEnd()) break
            val value = value()
            values += value
            // A status keeps its text whole; a numbered untagged response ("* 3 EXISTS") isn't one.
            val word = (value as? ImapValue.Atom)?.text?.uppercase()
            if (values.size == 1 && word in STATUSES) return ImapLine(tag, values, restOfLine().trimStart())
        }
        return ImapLine(tag, values)
    }

    private fun value(): ImapValue = when (peek()) {
        -1 -> throw EOFException("The server closed the connection")
        '('.code -> {
            next()
            val items = mutableListOf<ImapValue>()
            while (true) {
                skipSpaces()
                if (peek() == ')'.code) {
                    next()
                    break
                }
                if (atLineEnd()) throw IOException("A list ended with its line")
                items += value()
            }
            ImapValue.Items(items)
        }
        '"'.code -> quoted()
        '{'.code -> literal()
        else -> atom().let { if (it.equals("NIL", ignoreCase = true)) ImapValue.Nil else ImapValue.Atom(it) }
    }

    private fun quoted(): ImapValue.Str {
        next()
        val out = ByteArrayOutputStream()
        while (true) {
            when (val c = next()) {
                '"'.code -> return ImapValue.Str(out.toByteArray())
                '\\'.code -> out.write(next())
                '\r'.code, '\n'.code -> throw IOException("A quoted string ran into the line's end")
                else -> out.write(c)
            }
        }
    }

    private fun literal(): ImapValue.Str {
        next()
        val digits = StringBuilder()
        while (true) {
            val c = next()
            if (c == '}'.code) break
            if (c !in '0'.code..'9'.code) throw IOException("Bad literal length")
            digits.append(c.toChar())
        }
        expect('\r'.code)
        expect('\n'.code)
        val length = digits.toString().toInt()
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) throw EOFException("The connection closed inside a literal")
            read += n
        }
        return ImapValue.Str(bytes)
    }

    private fun atom(): String {
        val out = StringBuilder()
        var depth = 0
        while (true) {
            val c = peek()
            if (c < 0) break
            if (depth == 0 && (c == ' '.code || c == '('.code || c == ')'.code || c == '\r'.code || c == '\n'.code)) break
            if (c == '\r'.code || c == '\n'.code) throw IOException("A section spec ran into the line's end")
            if (c == '['.code) depth++
            if (c == ']'.code && depth > 0) depth--
            out.append(next().toChar())
        }
        if (out.isEmpty()) throw IOException("Expected a value")
        return out.toString()
    }

    private fun atomUntilSpace(): String {
        val out = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0) throw EOFException("The server closed the connection")
            if (c == ' '.code || c == '\r'.code || c == '\n'.code) break
            out.append(next().toChar())
        }
        return out.toString()
    }

    /** The rest of the line as text, without its CRLF. */
    private fun restOfLine(): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val c = next()
            if (c == '\n'.code) break
            if (c != '\r'.code) out.write(c)
        }
        return out.toByteArray().decodeToString().trimStart()
    }

    private fun skipSpaces() {
        while (peek() == ' '.code) next()
    }

    /** Consumes the line's end, if it's next. */
    private fun atLineEnd(): Boolean {
        if (peek() == '\r'.code) next()
        if (peek() == '\n'.code) {
            next()
            return true
        }
        return false
    }

    private fun expect(c: Int) {
        if (next() != c) throw IOException("Unexpected byte in a response")
    }

    private fun peek(): Int {
        if (peeked == -2) peeked = input.read()
        return peeked
    }

    private fun next(): Int {
        val c = peek()
        if (c < 0) throw EOFException("The server closed the connection")
        peeked = -2
        return c
    }

    private companion object {
        val STATUSES = setOf("OK", "NO", "BAD", "BYE", "PREAUTH")
    }
}

/**
 * Just enough IMAP4rev1 to read an inbox, plus Gmail's `X-GM-` extensions. Read-only by
 * construction: it opens mailboxes with EXAMINE and fetches bodies with BODY.PEEK, so nothing is
 * marked as read or changed, and it has no command that writes, moves or sends anything.
 */
class ImapClient(input: InputStream, private val output: OutputStream) : Closeable {
    private val reader = ImapReader(input)
    private var counter = 0

    class CommandFailed(command: String, line: ImapLine) : IOException("$command: ${line.status} ${line.text.orEmpty()}")

    /** The server's greeting: `* OK …`. */
    fun greeting() {
        val line = reader.readLine()
        if (line.tag != "*" || line.status != "OK") throw IOException("Unexpected greeting: ${line.status} ${line.text.orEmpty()}")
    }

    fun login(user: String, password: String) {
        run("LOGIN ${quote(user)} ${quote(password)}", "LOGIN")
    }

    /** Opens [mailbox] read-only. Returns how many messages it holds. */
    fun examine(mailbox: String): Int =
        run("EXAMINE ${quote(mailbox)}", "EXAMINE")
            .firstOrNull { it.values.getOrNull(1)?.string.equals("EXISTS", ignoreCase = true) }
            ?.values?.firstOrNull()?.string?.toIntOrNull() ?: 0

    fun uidSearch(criteria: String): List<Long> =
        run("UID SEARCH $criteria", "UID SEARCH")
            .filter { it.values.firstOrNull()?.string.equals("SEARCH", ignoreCase = true) }
            .flatMap { line -> line.values.drop(1).mapNotNull { it.string?.toLongOrNull() } }

    /** Each FETCH response's attributes, name (upper case) to value. */
    fun uidFetch(uids: Collection<Long>, items: String): List<Map<String, ImapValue>> {
        if (uids.isEmpty()) return emptyList()
        return run("UID FETCH ${uids.joinToString(",")} ($items)", "UID FETCH")
            .filter { it.values.getOrNull(1)?.string.equals("FETCH", ignoreCase = true) }
            .map { line ->
                val pairs = line.values.getOrNull(2)?.list.orEmpty()
                (0 until pairs.size - 1 step 2).associate { i -> pairs[i].string.orEmpty().uppercase() to pairs[i + 1] }
            }
    }

    fun logout() {
        runCatching { run("LOGOUT", "LOGOUT") }
    }

    override fun close() {
        runCatching { output.close() }
    }

    /** Sends one command and collects its untagged responses until its tagged completion. */
    private fun run(command: String, name: String): List<ImapLine> {
        val tag = "d${++counter}"
        output.write("$tag $command\r\n".encodeToByteArray())
        output.flush()
        val untagged = mutableListOf<ImapLine>()
        while (true) {
            val line = reader.readLine()
            when (line.tag) {
                tag -> {
                    if (line.status != "OK") throw CommandFailed(name, line)
                    return untagged
                }
                "*" -> {
                    if (line.status == "BYE" && name != "LOGOUT") throw CommandFailed(name, line)
                    untagged += line
                }
                else -> Unit
            }
        }
    }

    companion object {
        /** An IMAP quoted string. Passwords may hold anything printable; line breaks can't be sent. */
        fun quote(value: String): String {
            require('\r' !in value && '\n' !in value) { "Line breaks can't be sent in a quoted string" }
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }
}
