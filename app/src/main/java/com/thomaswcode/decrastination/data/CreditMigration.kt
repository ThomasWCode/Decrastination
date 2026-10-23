package com.thomaswcode.decrastination.data

/**
 * A kept slot's record says what free time its keep put in the day's ledger (`SlotRecord.earnedMin`), so taking the
 * keep back gives back that, not its minutes at the rate now: in log.json, and in session.json's keeps under way. A
 * file from before has none, each such record taken back at the rate then, as before; each store's version is raised
 * all the same, so an older build sets a newer file aside rather than read it without them.
 */
object CreditMigration {
    /** log.json from version 3. */
    val LOG = Migration(3) { it }

    /** session.json from version 1. */
    val SESSION = Migration(1) { it }
}
