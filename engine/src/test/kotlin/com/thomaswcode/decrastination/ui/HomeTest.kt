package com.thomaswcode.decrastination.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where the app opens (D-121): the launcher's start of a fresh task to the chat; every other start where it was asked to go. */
class HomeTest {
    @Test
    fun `the launcher's start of a fresh task goes to the chat`() {
        assertEquals(Home.Start.Chat, Home.start(launcher = true, root = true, restored = false, asked = false))
    }

    @Test
    fun `the launcher's start on top of a live task leaves, so what was on top shows`() {
        assertEquals(Home.Start.Leave, Home.start(launcher = true, root = false, restored = false, asked = false))
    }

    @Test
    fun `a start made again after a rotation or the process's death stays`() {
        assertEquals(Home.Start.Stay, Home.start(launcher = true, root = true, restored = true, asked = false))
        assertEquals(Home.Start.Stay, Home.start(launcher = true, root = false, restored = true, asked = false))
    }

    @Test
    fun `the widget's start, with no action, stays, at the root or over the chat`() {
        assertEquals(Home.Start.Stay, Home.start(launcher = false, root = true, restored = false, asked = false))
        assertEquals(Home.Start.Stay, Home.start(launcher = false, root = false, restored = false, asked = false))
    }

    @Test
    fun `a start asking for a tab or a task stays, Setup's included`() {
        assertEquals(Home.Start.Stay, Home.start(launcher = true, root = true, restored = false, asked = true))
        assertEquals(Home.Start.Stay, Home.start(launcher = false, root = true, restored = false, asked = true))
    }

    @Test
    fun `Back opens the chat only at the task's root`() {
        assertTrue(Home.backOpensChat(root = true))
        assertFalse(Home.backOpensChat(root = false))
    }
}
