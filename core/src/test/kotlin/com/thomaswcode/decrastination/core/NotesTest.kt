package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A task's notes, its own and standing ones (Q31: A), and which are kept (docs/plan_v4/07-notes-and-reminders.md §Tests). */
class NotesTest {
    private val now = Fixtures.at("2026-10-20T17:00")
    private val classMap = mapOf("12X/Ch1" to "Chemistry", "12X/Ph1" to "Physics")

    private fun task(id: String, title: String, className: String?, source: Source = Source.Teams) = TaskItem(
        id = id, source = source, sourceId = id.substringAfter(':'), title = title, className = className, kind = Kind.Homework,
        sourceEffortMin = 30, firstSeenAt = now, lastSeenAt = now,
    )

    private val rates = task("teams:rates", "Rates of reaction worksheet", "12X/Ch1")
    private val forces = task("teams:forces", "Forces worksheet", "12X/Ph1")
    private val own = task("own:o1", "Revise bonding", "Chemistry", Source.Own)
    private val history = task("powerplanner:p1", "Essay plan", "12X/Hi1", Source.PowerPlanner)

    private fun note(id: String, taskId: String? = null, scope: NoteScope? = null, at: Long = now, deletedAt: Long? = null) = Note(id, "words of $id", at, By.Felix, taskId, scope, deletedAt)

    @Test
    fun `a task's notes are its own, then each standing one it matches, none deleted`() {
        val notes = listOf(
            note("standing", scope = NoteScope(subject = "chemistry"), at = now - 1),
            note("own", taskId = rates.id),
            note("gone", taskId = rates.id, deletedAt = now),
            note("other", taskId = forces.id),
        )
        assertEquals(listOf("own", "standing"), Notes.forTask(rates, notes, classMap).map { it.id })
        // Your own task's class is its subject, so a subject's note reaches it with no class mapped.
        assertEquals(listOf("standing"), Notes.forTask(own, notes, classMap).map { it.id })
        assertEquals(listOf("other"), Notes.forTask(forces, notes, classMap).map { it.id })
    }

    @Test
    fun `a standing note matches by subject through the class map, class code, source and title, each given all of them`() {
        fun matches(scope: NoteScope, task: TaskItem) = Notes.matches(scope, task, classMap)
        assertTrue(matches(NoteScope(subject = "Physics"), forces) && !matches(NoteScope(subject = "Physics"), rates))
        assertTrue(matches(NoteScope(className = "12x/ch1"), rates) && !matches(NoteScope(className = "12X/Ch1"), forces))
        assertTrue(matches(NoteScope(source = Source.PowerPlanner), history) && !matches(NoteScope(source = Source.PowerPlanner), rates))
        assertTrue(matches(NoteScope(titleContains = "WORKSHEET"), rates) && matches(NoteScope(titleContains = "worksheet"), forces) && !matches(NoteScope(titleContains = "worksheet"), history))
        assertTrue(matches(NoteScope(subject = "Chemistry", titleContains = "worksheet"), rates) && !matches(NoteScope(subject = "Chemistry", titleContains = "worksheet"), own))
        // A subject no class is mapped to matches nothing; nor does an empty scope.
        assertTrue(!matches(NoteScope(subject = "History"), history) && !matches(NoteScope(), rates))
    }

    @Test
    fun `a deleted note, or one on a task gone, is kept while its act can bring it back`() {
        val deleted = note("deleted", taskId = rates.id, deletedAt = now)
        val onGone = note("onGone", taskId = "own:gone")
        val kept = note("kept", taskId = rates.id)
        val all = listOf(deleted, onGone, kept)
        val acts = ChatMessage(
            "m", Speaker.Felix, "Done.", now,
            acts = listOf(
                Act("Note deleted", ChatAction(ChatAction.Type.DeleteNote, rates.id, value = "deleted")),
                Act("Deleted: Gone", ChatAction(ChatAction.Type.Delete, "own:gone"), was = own.copy(id = "own:gone")),
            ),
        )
        val tasks = setOf(rates.id)
        assertEquals(listOf("deleted", "onGone", "kept"), Notes.prune(all, tasks, listOf(acts)).map { it.id })
        assertEquals(listOf("kept"), Notes.prune(all, tasks, listOf(acts.copy(acts = acts.acts.map { it.copy(undone = true) }))).map { it.id })
        assertEquals(listOf("kept"), Notes.prune(all, tasks, emptyList()).map { it.id })
        // On a source's task not listed: kept till that source has been read since the note (a backup restored before the first sync).
        val onTeams = note("onTeams", taskId = "teams:later")
        assertEquals(listOf("onTeams", "kept"), Notes.prune(listOf(onTeams, kept), tasks, emptyList()).map { it.id })
        assertEquals(listOf("kept"), Notes.prune(listOf(onTeams, kept), tasks, emptyList()) { onTeams.at + 1 }.map { it.id })
    }
}
