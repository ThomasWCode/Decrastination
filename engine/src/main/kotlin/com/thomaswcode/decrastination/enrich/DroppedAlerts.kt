package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.TaskItem

/** Which of Claude's dropped plans still stand, and which of those haven't been warned of. Pure. */
object DroppedAlerts {
    /** The open tasks with a dropped plan standing (their enrichment current), and why. */
    fun standing(tasks: List<TaskItem>): List<Pair<TaskItem, String>> =
        tasks.mapNotNull { task -> Enrichments.current(task)?.dropped?.takeIf { task.isOpen && !task.hidden }?.let { task to it } }

    /** Of [standing], those whose warning hasn't been shown for the plan as it is ([shown]: task id to content hash). */
    fun unshown(standing: List<Pair<TaskItem, String>>, shown: Map<String, String>): List<Pair<TaskItem, String>> =
        standing.filter { (task, _) -> shown[task.id] != task.enrichment?.inputHash }
}
