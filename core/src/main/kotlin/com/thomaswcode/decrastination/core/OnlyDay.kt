package com.thomaswcode.decrastination.core

/**
 * A day kept for only some tasks (D-115, [ChangeType.DayOnly]): its tasks alone have time on it, [taskIds] (those of its
 * DayOnly changes, and those a move put there since, [joined]); Anki's cards too where [anki]; and, where [deadlines], work
 * that would otherwise miss its real deadline, as a last resort. The time they don't use stays free.
 */
data class OnlyDay(val taskIds: Set<String>, val anki: Boolean, val deadlines: Boolean, val joined: Set<String> = emptySet())
