package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AstraUiReviewTest {
    @Test
    fun `a subject named No subject and an unclassified task must not duplicate Work list keys`() {
        val now = Fixtures.at("2026-10-29T17:00")
        val named = OwnTask(title = "Homework", className = "No subject", minutes = 30)
        val unclassified = OwnTask(title = "Buy stationery", minutes = 15)
        assertNull(OwnTaskRules.problem(named))
        assertNull(OwnTaskRules.problem(unclassified))
        val tasks = listOf(OwnTaskRules.create(named, "named", now), OwnTaskRules.create(unclassified, "unclassified", now))
        val groups = Groups.bySubject(tasks, emptyMap(), now)
        val keys = groups.map { (title, _) -> "group-$title" }
        assertEquals(keys.size, keys.toSet().size, "WorkScreen uses these values as LazyColumn keys: $keys")
    }
}
