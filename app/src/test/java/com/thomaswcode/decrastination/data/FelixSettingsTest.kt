package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.FelixSettings
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Window
import com.thomaswcode.decrastination.protect.SettingsChanges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The settings Felix may change from the chat (16 Oct). */
class FelixSettingsTest {
    private val settings = Settings()

    @Test
    fun `a setting is set from its words, said, and refused out of range or unknown`() {
        val (box, line) = FelixSettings.set(settings, "boxMin", "40")!!
        assertEquals(40, box.boxMin)
        assertEquals("A piece's length (minutes): 30 → 40", line)
        assertEquals(7 * 60 + 30, FelixSettings.set(settings, "briefingWeekdayMin", "07:30")!!.first.briefingWeekdayMin)
        assertEquals(Window(17 * 60, 21 * 60 + 30), FelixSettings.set(settings, "weekdayHours", "17:00-21:30")!!.first.weekdayHours)
        assertEquals("Max", FelixSettings.set(settings, "felixName", " Max ")!!.first.felixName)
        assertNull(FelixSettings.set(settings, "boxMin", "4000"))
        assertNull(FelixSettings.set(settings, "briefingWeekdayMin", "25:00"))
        // Hours that end before they start aren't working hours.
        assertNull(FelixSettings.set(settings, "weekdayHours", "21:00-17:00"))
        assertNull(FelixSettings.set(settings, "felixName", " "))
        assertNull(FelixSettings.set(settings, "blockedApps", "[]"))
        assertNull(FelixSettings.set(settings, "aiMonthlyCapUsd", "1000"))
    }

    @Test
    fun `none of them is the blocker's, so none ever waits or needs the code`() {
        val armed = settings.copy(armed = true)
        val others = mapOf(
            FelixSettings.Shape.Number to listOf("0", "1", "2", "3", "5", "10", "30", "60", "120", "240"),
            FelixSettings.Shape.Time to listOf("06:15"),
            FelixSettings.Shape.Hours to listOf("09:00-10:00"),
            FelixSettings.Shape.Words to listOf("Someone else"),
        )
        for (field in FelixSettings.FIELDS) {
            assertFalse(field.name in setOf("armed", "blockedApps", "blockedSites", "workMinPerFreeMin", "quietHours", "freeAllowanceMin", "photoFromMin", "aiMonthlyCapUsd"), field.name)
            assertEquals(true, field.name in SettingsChanges.fields, field.name)
            // Another value, armed: never one that loosens blocking, which would wait its hours.
            val changed = others.getValue(field.shape).mapNotNull { field.set(armed, it) }.first { field.get(it) != field.get(armed) }
            assertFalse(SettingsChanges.loosens(field.name, armed, changed), field.name)
        }
        assertEquals("boxMin | A piece's length (minutes) | 30", FelixSettings.describe(settings).first { it.startsWith("boxMin") })
    }
}
