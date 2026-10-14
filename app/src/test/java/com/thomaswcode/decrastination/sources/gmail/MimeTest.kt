package com.thomaswcode.decrastination.sources.gmail

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MimeTest {

    private fun structure(text: String): ImapValue =
        ImapReader(ByteArrayInputStream("* 1 FETCH (BODYSTRUCTURE $text)\r\n".toByteArray())).readLine().values[2].list[1]

    @Test
    fun `a single-part message's text is part 1`() {
        val part = Mime.textPart(structure("(\"TEXT\" \"PLAIN\" (\"CHARSET\" \"utf-8\") NIL NIL \"QUOTED-PRINTABLE\" 120 4 NIL NIL NIL)"))
        // Its size as sent too, so a part longer than a read takes is known to be cut.
        assertEquals(Mime.TextPart("1", "plain", "quoted-printable", "utf-8", size = 120), part)
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
        assertEquals(Mime.TextPart("1.2", "plain", "7bit", "iso-8859-1", size = 300), part)
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
    fun `a numeric entity that names no character is left as written`() {
        assertEquals("a &#1114112; b &#x110000; c", Mime.htmlToText("a &#1114112; b &#x110000; c"))
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

    // --- What a message quotes, and its signature, cut (DX-06) ---

    @Test
    fun `nested blockquotes go whole, and what was cut is counted as text`() {
        val html = "<p>Yes, Friday works.</p><blockquote>Can you come?<blockquote>Old <b>news</b></blockquote>Thanks</blockquote><p>Tom</p>"
        val cut = Mime.cutQuoted(html, html = true)
        assertEquals("<p>Yes, Friday works.</p><p>Tom</p>", cut.text)
        assertEquals(Mime.tidy(Mime.htmlToText("<blockquote>Can you come?<blockquote>Old <b>news</b></blockquote>Thanks</blockquote>"), 1000).length, cut.cutChars)
    }

    @Test
    fun `a Gmail quote div goes whole, nested divs and all`() {
        val html = """<div dir="ltr">Sounds good</div><br><div class="gmail_quote gmail_quote_container"><div dir="ltr" class="gmail_attr">On Thu, 8 Oct 2026 at 18:02, Dad &lt;dad@example.com&gt; wrote:<br></div><blockquote class="gmail_quote"><div>Please book the train.</div></blockquote></div>"""
        val cut = Mime.cutQuoted(html, html = true)
        assertEquals("""<div dir="ltr">Sounds good</div><br>""", cut.text)
        assertTrue(cut.cutChars > 0)
    }

    @Test
    fun `a Gmail forward keeps what it forwards and cuts what that quoted`() {
        val html = """<div dir="ltr">FYI<br><div class="gmail_quote"><div dir="ltr" class="gmail_attr">---------- Forwarded message ---------<br>From: Mr Hughes &lt;hughes@school.example&gt;<br>Date: Thu, 8 Oct 2026<br>Subject: Trip<br>To: &lt;dad@example.com&gt;<br></div><br><div dir="ltr">Please sign the form by Friday.</div><div class="gmail_quote"><div class="gmail_attr">On Wed, 7 Oct 2026, Dad wrote:</div><blockquote class="gmail_quote">Is there a form?</blockquote></div></div></div>"""
        val text = Mime.tidy(Mime.htmlToText(Mime.cutQuoted(html, html = true).text), 1000)
        assertTrue("Please sign the form by Friday." in text, text)
        assertTrue("Forwarded message" in text, text)
        assertTrue("Is there a form?" !in text, text)
    }

    @Test
    fun `plain text is cut from its attribution line, wrapped or not`() {
        val reply = "Yes, I'll bring it.\n\nOn Thu, 8 Oct 2026 at 18:02, Mr Hughes <hughes@school.example> wrote:\n> Please bring the form.\n> Thanks"
        val cut = Mime.cutQuoted(reply, html = false)
        assertEquals("Yes, I'll bring it.", cut.text)
        assertEquals(Mime.tidy("On Thu, 8 Oct 2026 at 18:02, Mr Hughes <hughes@school.example> wrote:\n> Please bring the form.\n> Thanks", 1000).length, cut.cutChars)
        // Gmail wraps a long one onto the next line.
        assertEquals("Done.", Mime.cutQuoted("Done.\nOn Thu, 8 Oct 2026 at 18:02, Richard White <\nrichard.white@example.com> wrote:\n> Book it", html = false).text)
        // A line that only says someone wrote, with no date in it, is the message's own.
        assertEquals("On Monday Sir wrote:\nthe dates on the board.", Mime.cutQuoted("On Monday Sir wrote:\nthe dates on the board.", html = false).text)
    }

    @Test
    fun `Outlook's original message and its header block are cut, its rule with them`() {
        assertEquals("Thanks!", Mime.cutQuoted("Thanks!\n-----Original Message-----\nFrom: A\nSent: Monday", html = false).text)
        assertEquals("See below.", Mime.cutQuoted("See below.\n________________________________\nFrom: Office <office@school.example>\nSent: 08 October 2026 18:02\nTo: Tom\nSubject: Trip", html = false).text)
        // A From: line with no Sent: or To: near it is the message's own.
        assertEquals("From: the office, a reminder.\nBring it Friday.", Mime.cutQuoted("From: the office, a reminder.\nBring it Friday.", html = false).text)
    }

    @Test
    fun `a signature is cut`() {
        val cut = Mime.cutQuoted("See you then.\n-- \nMr Hughes\nHead of Physics", html = false)
        assertEquals("See you then.", cut.text)
        assertEquals(Mime.tidy("-- \nMr Hughes\nHead of Physics", 1000).length, cut.cutChars)
    }

    @Test
    fun `a plain forward keeps what it forwards and cuts at the quote after it`() {
        val forward = "FYI\n\n---------- Forwarded message ---------\nFrom: Mr Hughes <hughes@school.example>\nDate: Thu, 8 Oct 2026\nSubject: Trip\nTo: Dad <dad@example.com>\n\nPlease sign the form by Friday.\n\nOn Wed, 7 Oct 2026, Dad <dad@example.com> wrote:\n> Is there a form?"
        val cut = Mime.cutQuoted(forward, html = false)
        assertTrue(cut.text.endsWith("Please sign the form by Friday."), cut.text)
        assertTrue("From: Mr Hughes" in cut.text)
        assertTrue("Is there a form?" !in cut.text)
    }

    @Test
    fun `a message with nothing quoted is as it was`() {
        assertEquals(Mime.Cut("Please bring the form.", 0), Mime.cutQuoted("Please bring the form.", html = false))
        assertEquals(Mime.Cut("<p>Hi</p>", 0), Mime.cutQuoted("<p>Hi</p>", html = true))
    }

    @Test
    fun `a part's readable text has HTML's quotes cut before it's flattened, then plain text's`() {
        val part = Mime.TextPart("1", "html", "7bit", "utf-8")
        val html = "<p>Thanks, done.</p><p>-- </p><p>Tom</p><blockquote>Did you do it?</blockquote>"
        val read = Mime.readable(html.toByteArray(), part, 1000)
        assertEquals("Thanks, done.", read.text)
        assertEquals(Mime.tidy("Did you do it?", 1000).length + Mime.tidy("--\nTom", 1000).length, read.cutChars)
    }

    // --- Attachments (DX-01) ---

    @Test
    fun `a message's attachments are named, its inline logo and its text not`() {
        val files = Mime.attachments(
            structure(
                "((\"TEXT\" \"PLAIN\" (\"CHARSET\" \"utf-8\") NIL NIL \"7BIT\" 120 4 NIL NIL NIL)" +
                    "(\"IMAGE\" \"PNG\" (\"NAME\" \"image001.png\") \"<image001.png@01D>\" NIL \"BASE64\" 4000 NIL (\"INLINE\" (\"FILENAME\" \"image001.png\")) NIL NIL)" +
                    "(\"APPLICATION\" \"PDF\" (\"NAME\" \"consent-form.pdf\") NIL NIL \"BASE64\" 150186 NIL (\"ATTACHMENT\" (\"FILENAME\" \"consent-form.pdf\")) NIL NIL) \"MIXED\" (\"BOUNDARY\" \"b0\") NIL NIL)",
            ),
        )
        // Base64's size cut to the file's: 150 186 bytes sent, 110 KB of PDF.
        assertEquals(listOf(Mime.Attachment("consent-form.pdf", "application/pdf", 112_639)), files)
        assertEquals("consent-form.pdf (110 KB)", files.single().label)
    }

    @Test
    fun `an attachment is found by its name where it has no disposition, and an encoded name is read`() {
        val files = Mime.attachments(
            structure(
                "((\"TEXT\" \"HTML\" NIL NIL NIL \"7BIT\" 30 1 NIL NIL NIL)" +
                    "(\"APPLICATION\" \"VND.OPENXMLFORMATS-OFFICEDOCUMENT.WORDPROCESSINGML.DOCUMENT\" (\"NAME\" \"=?UTF-8?Q?Itiner=C3=A4r.docx?=\") NIL NIL \"7BIT\" 2048 NIL NIL NIL NIL)" +
                    "(\"APPLICATION\" \"PDF\" (\"NAME*\" \"utf-8''trip%20form.pdf\") NIL NIL \"BASE64\" 400 NIL (\"ATTACHMENT\" (\"FILENAME*\" \"utf-8''trip%20form.pdf\")) NIL NIL) \"MIXED\" (\"BOUNDARY\" \"b\") NIL NIL)",
            ),
        )
        assertEquals(listOf("Itinerär.docx", "trip form.pdf"), files.map { it.name })
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", files.first().type)
    }

    @Test
    fun `an attached text file is an attachment, and a size past a thousand kilobytes is in megabytes`() {
        val files = Mime.attachments(
            structure(
                "((\"TEXT\" \"PLAIN\" (\"NAME\" \"notes.txt\") NIL NIL \"BASE64\" 40 1 NIL (\"ATTACHMENT\" (\"FILENAME\" \"notes.txt\")) NIL)" +
                    "(\"TEXT\" \"HTML\" NIL NIL NIL \"7BIT\" 30 1 NIL NIL NIL) \"MIXED\" (\"BOUNDARY\" \"b\") NIL NIL)",
            ),
        )
        assertEquals(listOf("notes.txt"), files.map { it.name })
        assertEquals("scan.pdf (2.4 MB)", Mime.Attachment("scan.pdf", "application/pdf", 2_500_000).label)
        assertEquals("plan.docx", Mime.Attachment("plan.docx", "application/msword").label)
    }
}
