package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable

/**
 * A note Felix keeps at your asking (docs/spec/felix.md §What he can do): on one task
 * ([taskId]), or standing, for every task its [scope] matches (D-100), never both; told to him
 * whenever the task comes up, and shown on its sheet. Yours from the sheet, his from the chat ([by]).
 * Deleted, it's marked so ([deletedAt]), shown nowhere and kept whole for the delete's Undo, till
 * that can no longer be undone ([Notes.prune]).
 */
@Serializable
data class Note(
    val id: String,
    val text: String,
    val at: Long,
    val by: By,
    val taskId: String? = null,
    val scope: NoteScope? = null,
    val deletedAt: Long? = null,
)

/**
 * What a standing note is for (D-100): every task of a [subject] (a class code's subject, by the
 * class map), of a class ([className], its code), from a [source], or with a word in its title
 * ([titleContains], from a word's start). Of those given, a task matches every one; each named in at
 * most [Notes.MAX_SCOPE] characters.
 */
@Serializable
data class NoteScope(val subject: String? = null, val className: String? = null, val source: Source? = null, val titleContains: String? = null) {
    /** Whether it names nothing to match: no scope at all. */
    val isEmpty: Boolean get() = subject.isNullOrBlank() && className.isNullOrBlank() && source == null && titleContains.isNullOrBlank()
}

/** The notes (docs/spec/felix.md §What he can do): which a task has, and which are kept. Pure. */
object Notes {
    /** A note's words, at most. */
    const val MAX_TEXT = 300

    /** The most notes one task keeps, standing ones aside. */
    const val PER_TASK = 10

    /** The most standing notes kept at once: each goes with every task it matches. */
    const val MAX_STANDING = 20

    /** The most characters a standing note's subject, class or word in a title is named in. */
    const val MAX_SCOPE = 60

    /** Whether [note], not kept now (deleted), has room to be again: its task under [PER_TASK] notes, or standing ones under [MAX_STANDING]. */
    fun hasRoom(note: Note, notes: List<Note>): Boolean {
        val live = live(notes).filter { it.id != note.id }
        return if (note.taskId != null) live.count { it.taskId == note.taskId } < PER_TASK else live.count { it.scope != null } < MAX_STANDING
    }

    /** Those of [notes] not deleted. */
    fun live(notes: List<Note>): List<Note> = notes.filter { it.deletedAt == null }

    /** [taskId]'s own notes, not deleted, oldest first. */
    fun on(taskId: String, notes: List<Note>): List<Note> = live(notes).filter { it.taskId == taskId }.sortedBy { it.at }

    /**
     * [task]'s notes (D-100): its own, then every standing one whose scope it matches ([matches]),
     * each oldest first, none deleted; [classMap] is Settings' (a class code to its subject).
     */
    fun forTask(task: TaskItem, notes: List<Note>, classMap: Map<String, String>): List<Note> =
        on(task.id, notes) + live(notes).filter { it.taskId == null && it.scope?.let { s -> matches(s, task, classMap) } == true }.sortedBy { it.at }

    /**
     * Whether [task] is one [scope] is for: its subject as [Duplicates.subject] gives it (the subject
     * [classMap] gives its class code, else the class's own name, as your own tasks' is their subject: a
     * code no subject is mapped to matches no subject but itself), or one it begins in whole words (a class
     * given as its Power Planner class, named for its subject and teacher: "Physics" is "Physics HALE"'s
     * subject, "Maths" not "Further Maths"'s); its class, its code; its source; a
     * word in its title, from a word's start ("art" in "Pop art" or "Artwork", not "Cartesian" or "start";
     * "worksheet" in "Worksheets"). Case ignored; each given, all of them; an empty scope matches nothing.
     */
    fun matches(scope: NoteScope, task: TaskItem, classMap: Map<String, String>): Boolean {
        if (scope.isEmpty) return false
        fun same(a: String?, b: String?) = a != null && b != null && a.trim().equals(b.trim(), ignoreCase = true)
        val subject = Duplicates.subject(task, classMap)
        fun ofSubject(given: String) = subject != null && given.trim().lowercase().let { g -> subject == g || subject.startsWith("$g ") }
        return (scope.subject.isNullOrBlank() || ofSubject(scope.subject)) &&
            (scope.className.isNullOrBlank() || same(scope.className, task.className)) &&
            (scope.source == null || scope.source == task.source) &&
            (scope.titleContains.isNullOrBlank() || fromWordStart(task.title, scope.titleContains.trim()))
    }

    /** Whether [words] are in [title] from the start of one of its words, case ignored. */
    private fun fromWordStart(title: String, words: String): Boolean {
        var i = title.indexOf(words, ignoreCase = true)
        while (i >= 0) {
            if (i == 0 || !title[i - 1].isLetterOrDigit()) return true
            i = title.indexOf(words, i + 1, ignoreCase = true)
        }
        return false
    }

    /**
     * Which tasks [scope] is for, in words: "every Chemistry task", "every 12X/Ch1 task from Teams", "every
     * task with “essay” in its title".
     */
    fun scopeLine(scope: NoteScope): String {
        val of = listOfNotNull(scope.subject?.trim()?.takeIf { it.isNotEmpty() }, scope.className?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" ")
        return listOfNotNull(
            "every ${if (of.isEmpty()) "" else "$of "}task",
            scope.source?.let { "from ${it.label}" },
            scope.titleContains?.trim()?.takeIf { it.isNotEmpty() }?.let { "with “$it” in its title" },
        ).joinToString(" ")
    }

    /**
     * [notes] kept (the nightly prune): a deleted one only while a message in the chat's window, [messages],
     * holds its delete's act not undone, as that Undo brings it back; one on a task gone from the task list
     * ([tasks], their ids) only while its task's delete can be undone, as that Undo brings the task back
     * whole (a deleted own task's act keeps the task); one on a source's task not listed till that source
     * has been read since it was made ([TaskItem.goneSince], [readAt]), so a backup restored before the first
     * sync keeps its notes. A note on a finished task stays with its record.
     */
    fun prune(notes: List<Note>, tasks: Set<String>, messages: List<ChatMessage>, readAt: (Source) -> Long? = { null }): List<Note> {
        val held = ChatActs.held(messages)
        return notes.filter { n ->
            val task = n.taskId
            (n.deletedAt == null || n.id in held) && (task == null || task in tasks || task in held || !TaskItem.goneSince(task, n.at, readAt))
        }
    }
}
