package com.thomaswcode.decrastination.data

/** The nightly backups' names (LR-08, AR-07), and how many are kept, wherever they're written. Pure. */
object BackupNames {
    /** How many nightly backups are kept: two weeks of them. */
    const val KEEP = 14

    private val NIGHTLY = Regex("""decrastination-(\d{4}-\d{2}-\d{2})\.json""")

    /** Of [names], the nightly backups past the newest [keep], by the date in each name: a reset's copy never. */
    fun toPrune(names: List<String>, keep: Int = KEEP): List<String> =
        names.mapNotNull { name -> NIGHTLY.matchEntire(name)?.let { name to it.groupValues[1] } }.sortedByDescending { it.second }.drop(keep).map { it.first }

    /** The nightly backup's name for [date] (`yyyy-MM-dd`). */
    fun nightlyName(date: String): String = "decrastination-$date.json"
}
