package com.thomaswcode.decrastination.block

/**
 * Whether each checked browser's address bar can still be read (IMP-P1-005). An update that renames
 * it would leave the blocked sites in that browser uncovered, unseen, while protection says all is
 * well. Counted by visits (the browser brought to the front): one with its address never read
 * counts, one with it read starts the count again. [LIMIT] running, it's unreadable, and it's a
 * protection problem until its address is read again. A visit can rightly show no address (a video
 * full screen, the tab switcher), so one alone means nothing. Pure.
 */
class AddressHealth {
    /** For each browser: whether its address has been read this visit, and visits running without. */
    private val readThisVisit = HashMap<String, Boolean>()
    private val unreadVisits = HashMap<String, Int>()

    /** The browsers whose address bar can't be read. */
    val unreadable: Set<String> get() = unreadVisits.filterValues { it >= LIMIT }.keys

    /** [browser] brought to the front: the visit before counts, if its address wasn't read. */
    fun visit(browser: String) {
        if (readThisVisit[browser] == false) unreadVisits[browser] = (unreadVisits[browser] ?: 0) + 1
        readThisVisit[browser] = false
    }

    /** [browser]'s address read: it can be, and the count starts again. */
    fun read(browser: String) {
        readThisVisit[browser] = true
        unreadVisits.remove(browser)
    }

    fun clear() {
        readThisVisit.clear()
        unreadVisits.clear()
    }

    companion object {
        /** Visits running without an address read before it's a problem. */
        const val LIMIT = 10
    }
}
