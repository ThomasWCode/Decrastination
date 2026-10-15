package com.thomaswcode.decrastination

import com.thomaswcode.decrastination.core.Plan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The plan made again as what it depends on changes (PL-13). The stores' states are StateFlows, as
 * these are: one written with what it holds already doesn't change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanWatcherTest {
    private val plan = Plan(Fixtures.at("2026-10-13T16:00"), LocalDate.parse("2026-10-13"), emptyList(), emptyList())

    @Test
    fun `a change makes one plan once things are quiet, and the hooks run on it in order`() = runTest {
        val tasks = MutableStateFlow(0)
        var plans = 0
        val ran = mutableListOf<String>()
        val watcher = PlanWatcher(
            backgroundScope, tasks, plan = { plans++; plan }, keep = { ran += "keep" },
            hooks = listOf({ _ -> ran += "alarms" }, { _ -> ran += "widget" }),
        )
        watcher.start()
        advanceTimeBy(PlanWatcher.QUIET_MS + 1)
        assertEquals(1, plans)
        assertEquals(listOf("keep", "alarms", "widget"), ran)
        // A sync's writes, one after another: one plan after them.
        tasks.value = 1
        advanceTimeBy(500)
        tasks.value = 2
        advanceTimeBy(PlanWatcher.QUIET_MS + 1)
        assertEquals(2, plans)
        // An alarm's poke: one more.
        watcher.poke()
        advanceTimeBy(PlanWatcher.QUIET_MS + 1)
        assertEquals(3, plans)
    }

    @Test
    fun `keeping today's starts as they were makes no plan again`() = runTest {
        val tasks = MutableStateFlow(0)
        val previous = MutableStateFlow(emptyMap<String, Long>())
        var plans = 0
        val starts = mapOf("teams:a#-1#1" to Fixtures.at("2026-10-13T17:00"))
        val watcher = PlanWatcher(backgroundScope, merge(tasks, previous), plan = { plans++; plan }, keep = { previous.value = starts }, hooks = emptyList())
        watcher.start()
        advanceTimeBy(PlanWatcher.QUIET_MS + 1)
        // The first plan's starts were new: once more, and that one keeps them as they are.
        advanceTimeBy(PlanWatcher.QUIET_MS * 5)
        runCurrent()
        assertEquals(2, plans)
    }

    @Test
    fun `a hook that fails doesn't keep the others from running`() = runTest {
        val ran = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val watcher = PlanWatcher(
            backgroundScope, MutableStateFlow(0), plan = { plan }, keep = {},
            hooks = listOf({ _ -> error("no alarms") }, { _ -> ran += "widget" }), onFailure = { failures += it },
        )
        watcher.start()
        advanceTimeBy(PlanWatcher.QUIET_MS + 1)
        assertEquals(listOf("widget"), ran)
        assertEquals(listOf("no alarms"), failures.map { it.message })
    }
}
