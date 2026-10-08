package com.thomaswcode.decrastination.probe

/**
 * AnkiDroid's `deck_count` column: a JSON array of three counts. FlashCardsContract documents the
 * order as learn, review, new; Phase 0 checks that against the deck picker before anything
 * depends on it.
 */
data class DeckCounts(val learn: Int, val review: Int, val new: Int) {
    companion object {
        /** Null unless [json] is an array of exactly three whole numbers. */
        fun parse(json: String?): DeckCounts? {
            val body = json?.trim()?.takeIf { it.startsWith("[") && it.endsWith("]") } ?: return null
            val numbers = body.substring(1, body.length - 1).split(",").map { it.trim().toIntOrNull() ?: return null }
            return numbers.takeIf { it.size == 3 }?.let { (learn, review, new) -> DeckCounts(learn, review, new) }
        }
    }
}
