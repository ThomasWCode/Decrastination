package com.thomaswcode.decrastination.data

import kotlinx.serialization.Serializable

/**
 * Everything you can change about how the app plans and blocks. Kept in `settings.json`. Times of
 * day are minutes after midnight, local time.
 */
@Serializable
data class Settings(
    /** Which textbook's decks a German section number like "1.2" means (Q18): `Textbook 1::1.2`. */
    val ankiTextbook: Int = 1,
    /** When the daily Anki quota is due (21:30, decided 8 Oct). */
    val ankiDeadlineMin: Int = 21 * 60 + 30,
)
