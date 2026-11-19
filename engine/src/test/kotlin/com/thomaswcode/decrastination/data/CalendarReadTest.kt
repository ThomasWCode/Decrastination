package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.learn.CalendarEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarReadTest {
    private val event = CalendarEvent(1L, "A made-up event", 10_000L, 20_000L, allDay = false)
    private val read = CalendarRead(listOf(event), mapOf("A made-up event" to "busy"), from = 1_000L, to = 100_000L)

    @Test
    fun `a read that's changed is news, and one that isn't, by when it began alone, isn't`() {
        assertTrue(read.news(null), "the first, or the first after the phone's held state is gone")
        assertFalse(read.news(read))
        assertFalse(read.copy(from = 2_000L).news(read), "its start moves with every read")
        assertTrue(read.copy(events = emptyList()).news(read))
        assertTrue(read.copy(answers = emptyMap()).news(read))
        assertTrue(read.copy(events = listOf(event.copy(end = 25_000L))).news(read))
        assertTrue(read.copy(to = 200_000L, from = 2_000L).news(read), "how far it reaches moves each midnight")
    }
}
