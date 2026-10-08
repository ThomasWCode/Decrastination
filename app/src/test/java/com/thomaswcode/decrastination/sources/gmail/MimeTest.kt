package com.thomaswcode.decrastination.sources.gmail

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MimeTest {

    private fun structure(text: String): ImapValue =
        ImapReader(ByteArrayInputStream("* 1 FETCH (BODYSTRUCTURE $text)\r\n".toByteArray())).readLine().values[2].list[1]

    @Test
    fun `a single-part message's text is part 1`() {
        val part = Mime.textPart(structure("(\"TEXT\" \"PLAIN\" (\"CHARSET\" \"utf-8\") NIL NIL \"QUOTED-PRINTABLE\" 120 4 NIL NIL NIL)"))
        assertEquals(Mime.TextPart("1", "plain", "quoted-printable", "utf-8"), part)
    }

    @Test
    fun `the plain part of a multipart alternative wins over the HTML`() {
        val part = Mime.textPart(
            structure(
                "(((\"TEXT\" \"HTML\" (\"CHARSET\" \"utf-8\") NIL NIL \"BASE64\" 900 12 NIL NIL NIL)" +
                    "(\"TEXT\" \"PLAIN\" (\"CHARSET\" \"iso-8859-1\") NIL NIL \"7BIT\" 300 9 NIL NIL NIL) \"ALTERNATIVE\" (\"BOUNDARY\" \"b1\") NIL NIL)" +
                    "(\"APPLICATION\" \"PDF\" (\"NAME\" \"ticket.pdf\") NIL NIL \"BASE64\" 5000 NIL (\"ATTACHMENT\" (\"FILENAME\" \"ticket.pdf\")) NIL) \"MIXED\" (\"BOUNDARY\" \"b0\") NIL NIL)",
            ),
        )
        assertEquals(Mime.TextPart("1.2", "plain", "7bit", "iso-8859-1"), part)
    }

    @Test
    fun `an attached text file isn't the message's text`() {
        val part = Mime.textPart(
            structure(
                "((\"TEXT\" \"PLAIN\" (\"NAME\" \"notes.txt\") NIL NIL \"BASE64\" 40 1 NIL (\"ATTACHMENT\" (\"FILENAME\" \"notes.txt\")) NIL)" +
                    "(\"TEXT\" \"HTML\" NIL NIL NIL \"7BIT\" 30 1 NIL NIL NIL) \"MIXED\" (\"BOUNDARY\" \"b\") NIL NIL)",
            ),
        )
        assertEquals("2", part?.section)
    }

    @Test
    fun `no text at all`() {
        assertNull(Mime.textPart(structure("(\"IMAGE\" \"PNG\" NIL NIL NIL \"BASE64\" 100 NIL NIL NIL)")))
    }

    @Test
    fun `encoded words decode, and the space between two goes`() {
        assertEquals("Café menu", Mime.decodeHeader("=?UTF-8?Q?Caf=C3=A9_menu?="))
        assertEquals("Document shared with you: ‘Imperial notes’", Mime.decodeHeader("Document shared with you: =?UTF-8?B?4oCYSW1wZXJpYWwgbm90ZXPigJk=?="))
        assertEquals("ab", Mime.decodeHeader("=?utf-8?q?a?= =?utf-8?q?b?="))
        assertEquals("plain", Mime.decodeHeader("plain"))
    }

    @Test
    fun `quoted-printable joins soft breaks and decodes bytes`() {
        val decoded = Mime.quotedPrintable("Gr=C3=BC=\r\nße =3D done=".toByteArray())
        assertEquals("Grüße = done=", decoded.decodeToString())
    }

    @Test
    fun `base64 cut short by a partial fetch decodes what it can`() {
        assertEquals("Hello wor", Mime.base64("SGVsbG8g\r\nd29ybGQh".toByteArray().copyOf(14)).decodeToString().take(9))
    }

    @Test
    fun `html becomes text`() {
        val text = Mime.htmlToText("<html><head><style>p{}</style></head><body><p>Hi&nbsp;Tom</p><div>Open day &amp; tour&#33;</div><script>x()</script></body></html>")
        assertEquals("Hi Tom\nOpen day & tour!", Mime.tidy(text, 100))
    }

    @Test
    fun `a style block cut off by a partial fetch is dropped, not read as text`() {
        assertEquals("Hi", Mime.tidy(Mime.htmlToText("<p>Hi</p><style>td > h1 { font-size: 2rem; }\n@media screen {"), 100))
    }

    @Test
    fun `invisible padding is dropped before the text is cut`() {
        val padded = "&zwnj;".repeat(1000) + "\u200B\u034F" + "See you on 10 October"
        assertEquals("See you on 10 October", Mime.tidy(padded, 4000))
    }

    @Test
    fun `decode applies the transfer encoding and charset`() {
        val part = Mime.TextPart("1", "plain", "quoted-printable", "iso-8859-1")
        assertEquals("Grüße", Mime.decode("Gr=FC=DFe".toByteArray(), part))
    }
}
