package com.thomaswcode.decrastination.ui

/** Where the app opens (D-121): the launcher's start goes to the chat, the app's home. */
object Home {
    /** What a start of [MainActivity] does before it draws anything. */
    enum class Start { Chat, Leave, Stay }

    /**
     * The launcher's own start ([launcher]: `ACTION_MAIN` with `CATEGORY_LAUNCHER`) of a task's root hands over
     * to the chat; one on top of a task already there finishes, so what was on top shows (Q114). Made again
     * ([restored]) or asked for a tab or a task, it stays.
     */
    fun start(launcher: Boolean, root: Boolean, restored: Boolean, asked: Boolean): Start = when {
        restored || asked || !launcher -> Start.Stay
        root -> Start.Chat
        else -> Start.Leave
    }

    /** Back on Day, Work or Stats with nothing under it opens the chat in its place (Q116). */
    fun backOpensChat(root: Boolean): Boolean = root
}
