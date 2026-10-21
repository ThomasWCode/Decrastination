package com.thomaswcode.decrastination.sources.gmail

/**
 * The writes a finished email needs (docs/plan_v4/10-emails-done.md, D-7 as amended), over [imap]'s
 * connection and tags: a mailbox opened for writing (SELECT), and flags or Gmail's labels stored on
 * UIDs, silently. Nothing is deleted, moved, sent or marked read: [ImapClient] keeps every other
 * command, and its own only read. Each command is logged as [ImapClient]'s are, by name and UIDs,
 * never a subject or an address.
 */
class ImapWriter(private val imap: ImapClient) {
    /** Opens [mailbox] for writing (a write can't be made on one EXAMINE opened): how many it holds, its UIDVALIDITY. */
    fun select(mailbox: String): Examined = imap.opened(imap.send("SELECT ${ImapClient.quote(mailbox)}", "SELECT"))

    /**
     * [item] stored on [uids] ([UNSTAR], [STAR], [ARCHIVE], [INBOX]), each `.SILENT`, so nothing comes back
     * but the command's end. Where the server refuses the set (NO: a message gone since), each is stored
     * alone, so one gone doesn't stop the rest. The UIDs refused, each alone; any other failure throws.
     */
    fun uidStore(uids: Collection<Long>, item: String): List<Long> {
        if (uids.isEmpty()) return emptyList()
        return try {
            imap.send("UID STORE ${uids.joinToString(",")} $item", "UID STORE")
            emptyList()
        } catch (refused: ImapClient.CommandFailed) {
            if (refused.status != "NO") throw refused
            uids.filter { uid ->
                try {
                    imap.send("UID STORE $uid $item", "UID STORE")
                    false
                } catch (no: ImapClient.CommandFailed) {
                    if (no.status != "NO") throw no
                    true
                }
            }
        }
    }

    companion object {
        /** The star taken off: Gmail's star is IMAP's `\Flagged`. */
        const val UNSTAR = "-FLAGS.SILENT (\\Flagged)"

        /** The star put back. */
        const val STAR = "+FLAGS.SILENT (\\Flagged)"

        /** Archived: the conversation's messages out of the inbox, which in Gmail is the label `\Inbox` taken off. */
        const val ARCHIVE = "-X-GM-LABELS.SILENT (\\Inbox)"

        /** Back in the inbox. */
        const val INBOX = "+X-GM-LABELS.SILENT (\\Inbox)"
    }
}
