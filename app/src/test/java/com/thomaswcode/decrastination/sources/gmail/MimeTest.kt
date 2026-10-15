package com.thomaswcode.decrastination.sources.gmail

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Invite
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `a part read whole keeps what it quotes`() {
        val part = Mime.TextPart("1", "plain", "7bit", "utf-8")
        val reply = "Over to Thomas.\n\nOn Wed, 7 Oct 2026, John <john@example.com> wrote:\n> My computer won't start."
        assertEquals("Over to Thomas.\n\nOn Wed, 7 Oct 2026, John <john@example.com> wrote:\n> My computer won't start.", Mime.readable(reply.toByteArray(), part, 1000, cut = false).text)
        assertEquals(Mime.Cut("Over to Thomas.", Mime.tidy("On Wed, 7 Oct 2026, John <john@example.com> wrote:\n> My computer won't start.", 1000).length), Mime.readable(reply.toByteArray(), part, 1000))
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

    @Test
    fun `an Outlook forward, a forward by its subject alone, keeps what it forwards`() {
        // Outlook forwards with a header block like a reply's and no marker (the Warwick Open Day email).
        val forward = "Sent from Outlook for Android\n________________________________\nFrom: Warwick Open Days <opendays@warwick.example>\nSent: Saturday, 10 October 2026 07:03\nTo: Richard White\nSubject: Final Reminder\n\nWe can't wait to meet you at 10:00.\n\nFrom: Thomas\nSent: Friday\nTo: Open Days\nSubject: Question"
        val cut = Mime.cutQuoted(forward, html = false, forwarded = true)
        assertTrue("We can't wait to meet you at 10:00." in cut.text, cut.text)
        assertTrue("Subject: Question" !in cut.text, cut.text)
        // Read as a reply (its subject says Re:), the header block is a quote, and goes.
        assertEquals("Sent from Outlook for Android", Mime.cutQuoted(forward, html = false).text)
        assertTrue(Mime.isForward("Fw: Final Reminder"))
        assertTrue(Mime.isForward("FWD: x"))
        assertFalse(Mime.isForward("Re: Fw: Final Reminder"))
    }

    @Test
    fun `a forward in a blockquote keeps its content, Apple Mail's by its marker and another by its subject`() {
        val apple = "<div>FYI</div><blockquote type=\"cite\"><div>Begin forwarded message:</div><div>From: Mr Hughes</div><div>Please sign the form.</div><blockquote>Old reply</blockquote></blockquote>"
        val kept = Mime.tidy(Mime.htmlToText(Mime.cutQuoted(apple, html = true).text), 1000)
        assertTrue("Please sign the form." in kept, kept)
        assertTrue("Old reply" !in kept, kept)
        val other = "<div>FYI</div><blockquote><div>Please sign the form.</div><blockquote>Old reply</blockquote></blockquote><blockquote>Another</blockquote>"
        val byItsSubject = Mime.tidy(Mime.htmlToText(Mime.cutQuoted(other, html = true, forwarded = true).text), 1000)
        assertEquals("FYI\nPlease sign the form.", byItsSubject)
    }

    @Test
    fun `the fullest form of a file's name wins, RFC 2231's or its pieces joined`() {
        fun named(disposition: String) = Mime.attachments(
            structure("(\"APPLICATION\" \"PDF\" NIL NIL NIL \"BASE64\" 400 NIL (\"ATTACHMENT\" ($disposition)) NIL NIL)"),
        ).single().name
        // Sent with a plain name too, for older readers: the extended one is the file's.
        assertEquals("résumé.pdf", named("\"FILENAME\" \"resume.pdf\" \"FILENAME*\" \"utf-8''r%C3%A9sum%C3%A9.pdf\""))
        assertEquals("long name.pdf", named("\"FILENAME*0*\" \"utf-8''long%20\" \"FILENAME*1*\" \"name.pdf\""))
        assertEquals("part one two.pdf", named("\"FILENAME*0\" \"part one \" \"FILENAME*1\" \"two.pdf\""))
        assertEquals("plain.pdf", named("\"FILENAME\" \"plain.pdf\""))
    }

    @Test
    fun `an attached email with no file name goes by its subject`() {
        val files = Mime.attachments(
            structure(
                "((\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 0 0 NIL NIL NIL)" +
                    "(\"MESSAGE\" \"RFC822\" NIL NIL NIL \"7BIT\" 16384 (\"Fri, 9 Oct 2026\" \"STEM Potential: how to apply\" NIL NIL NIL NIL NIL NIL NIL \"<x@y>\") (\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 100 3 NIL NIL NIL) 300 NIL (\"ATTACHMENT\" NIL) NIL NIL) \"MIXED\" (\"BOUNDARY\" \"b\") NIL NIL)",
            ),
        )
        assertEquals(listOf(Mime.Attachment("STEM Potential: how to apply", "message/rfc822", 16_384)), files)
        assertEquals("STEM Potential: how to apply (an email, 16 KB)", files.single().label)
    }

    @Test
    fun `an invite's part is found inline or as a file, and a message without one has none`() {
        val gmail = structure(
            "(((\"TEXT\" \"PLAIN\" (\"CHARSET\" \"utf-8\") NIL NIL \"7BIT\" 300 9 NIL NIL NIL)" +
                "(\"TEXT\" \"CALENDAR\" (\"CHARSET\" \"utf-8\" \"METHOD\" \"REQUEST\") NIL NIL \"BASE64\" 1200 16 NIL NIL NIL) \"ALTERNATIVE\" (\"BOUNDARY\" \"b1\") NIL NIL)" +
                "(\"APPLICATION\" \"ICS\" (\"NAME\" \"invite.ics\") NIL NIL \"BASE64\" 1200 NIL (\"ATTACHMENT\" (\"FILENAME\" \"invite.ics\")) NIL) \"MIXED\" (\"BOUNDARY\" \"b0\") NIL NIL)",
        )
        assertEquals(Mime.TextPart("1.2", "calendar", "base64", "utf-8", size = 1200), Mime.invitePart(gmail))
        // A booking's file, by its name alone.
        val booking = structure(
            "((\"TEXT\" \"PLAIN\" (\"CHARSET\" \"utf-8\") NIL NIL \"7BIT\" 300 9 NIL NIL NIL)" +
                "(\"APPLICATION\" \"OCTET-STREAM\" (\"NAME\" \"open-day.ics\") NIL NIL \"BASE64\" 900 NIL (\"ATTACHMENT\" (\"FILENAME\" \"open-day.ics\")) NIL) \"MIXED\" (\"BOUNDARY\" \"b0\") NIL NIL)",
        )
        assertEquals("2", Mime.invitePart(booking)?.section)
        assertNull(Mime.invitePart(structure("(\"TEXT\" \"PLAIN\" (\"CHARSET\" \"utf-8\") NIL NIL \"7BIT\" 120 4 NIL NIL NIL)")))
    }

    @Test
    fun `an invite reads its event's times in a zone or in UTC, past the time zones and its alarm, and with no end it's an hour`() {
        val ics = listOf(
            "BEGIN:VCALENDAR", "METHOD:REQUEST",
            "BEGIN:VTIMEZONE", "TZID:Europe/London", "BEGIN:STANDARD", "DTSTART:19701025T020000", "END:STANDARD", "END:VTIMEZONE",
            "BEGIN:VEVENT",
            "DTSTART;TZID=Europe/London:20261016T180000",
            "DTEND;TZID=Europe/London:20261016T200000",
            "SUMMARY:Sixth form open evening\\, Year 12",
            "LOCATION:Main Hall",
            "BEGIN:VALARM", "TRIGGER:-PT15M", "DESCRIPTION:Reminder", "END:VALARM",
            "END:VEVENT", "END:VCALENDAR",
        ).joinToString("\r\n")
        assertEquals(Invite("Sixth form open evening, Year 12", Fixtures.at("2026-10-16T18:00"), Fixtures.at("2026-10-16T20:00"), "Main Hall"), Mime.invite(ics, LONDON))
        val utc = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART:20261016T170000Z\r\nSUMMARY:Call\r\nEND:VEVENT\r\nEND:VCALENDAR"
        assertEquals(Invite("Call", Fixtures.at("2026-10-16T18:00"), Fixtures.at("2026-10-16T19:00")), Mime.invite(utc, LONDON))
    }

    @Test
    fun `an invite's folded lines are joined, a zone Java doesn't name is the student's, a date alone is all day, and one called off says so`() {
        val outlook = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART;TZID=\"GMT Standard Time\":20261016T090000\r\nDURATION:PT1H30M\r\nSUMMARY:Visit to the\r\n  physics department\r\nEND:VEVENT\r\nEND:VCALENDAR"
        assertEquals(Invite("Visit to the physics department", Fixtures.at("2026-10-16T09:00"), Fixtures.at("2026-10-16T10:30")), Mime.invite(outlook, LONDON))
        val day = Mime.invite("BEGIN:VCALENDAR\nBEGIN:VEVENT\nDTSTART;VALUE=DATE:20261026\nSUMMARY:Sports day\nEND:VEVENT\nEND:VCALENDAR", LONDON)!!
        assertEquals(Fixtures.at("2026-10-26T00:00") to Fixtures.at("2026-10-27T00:00"), day.start to day.end)
        assertTrue(day.allDay(LONDON))
        assertEquals(true, Mime.invite("BEGIN:VCALENDAR\nMETHOD:CANCEL\nBEGIN:VEVENT\nDTSTART:20261016T170000Z\nEND:VEVENT\nEND:VCALENDAR", LONDON)?.cancelled)
        assertNull(Mime.invite("BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:No time\nEND:VEVENT\nEND:VCALENDAR", LONDON))
        // Kept with its task, a bar in its name made a slash.
        assertEquals(Invite("Trip / coach", 1, 2, "Gate 3"), Invite.read(Invite("Trip | coach", 1, 2, "Gate 3").stored))
    }

    @Test
    fun `an invite's zone is Java's, Windows' name for one, or the calendar's own, and none it can't tell`() {
        val eastern = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART;TZID=Eastern Standard Time:20261016T100000\r\nSUMMARY:Call\r\nEND:VEVENT\r\nEND:VCALENDAR"
        assertEquals(Fixtures.at("2026-10-16T15:00"), Mime.invite(eastern, LONDON)?.start)
        val custom = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VTIMEZONE", "TZID:Customized Time Zone",
            "BEGIN:STANDARD", "DTSTART:16010101T030000", "TZOFFSETFROM:+0100", "TZOFFSETTO:+0000", "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=10", "END:STANDARD",
            "BEGIN:DAYLIGHT", "DTSTART:16010101T010000", "TZOFFSETFROM:+0000", "TZOFFSETTO:+0100", "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=3", "END:DAYLIGHT",
            "END:VTIMEZONE",
            "BEGIN:VEVENT", "DTSTART;TZID=\"Customized Time Zone\":20261016T180000", "DTEND;TZID=\"Customized Time Zone\":20261116T180000", "SUMMARY:Open evening", "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n")
        val read = Mime.invite(custom, LONDON)!!
        // Summer time in October, winter's by November: the calendar's own rules, read.
        assertEquals(Fixtures.at("2026-10-16T18:00") to Fixtures.at("2026-11-16T18:00"), read.start to read.end)
        assertNull(Mime.invite("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART;TZID=Nowhere Standard Time:20261016T100000\r\nEND:VEVENT\r\nEND:VCALENDAR", LONDON))
    }

    @Test
    fun `an all-day invite goes to the calendar at UTC midnights`() {
        val day = Invite("Sports day", Fixtures.at("2026-10-16T00:00"), Fixtures.at("2026-10-17T00:00"))
        assertEquals(java.time.Instant.parse("2026-10-16T00:00:00Z").toEpochMilli() to java.time.Instant.parse("2026-10-17T00:00:00Z").toEpochMilli(), day.calendarTimes(LONDON))
        val timed = Invite("Call", Fixtures.at("2026-10-16T18:00"), Fixtures.at("2026-10-16T19:00"))
        assertEquals(timed.start to timed.end, timed.calendarTimes(LONDON))
    }

    @Test
    fun `a day's duration over the clocks changing is still a day`() {
        // 28 March 2027, the day the clocks go forward: a day long, midnight to midnight.
        val day = Mime.invite("BEGIN:VCALENDAR\nBEGIN:VEVENT\nDTSTART;VALUE=DATE:20270328\nDURATION:P1D\nSUMMARY:Trip\nEND:VEVENT\nEND:VCALENDAR", LONDON)!!
        assertEquals(Fixtures.at("2027-03-29T00:00"), day.end)
        assertTrue(day.allDay(LONDON))
    }
}

