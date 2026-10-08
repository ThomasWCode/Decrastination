package com.thomaswcode.decrastination.probe

import com.thomaswcode.decrastination.probe.GuardRules.Verdict
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GuardRulesTest {

    private val labels = GuardRules.Labels(
        app = "Decrastination",
        service = "Decrastination focus",
        serviceDescriptionStart = "Decrastination watches which app is in front",
    )

    private fun decide(packageName: String, vararg texts: String) = GuardRules.decide(packageName, texts.toList(), labels)

    @Test
    fun `the service's own accessibility page is left`() {
        val verdict = decide(
            GuardRules.SETTINGS,
            "Decrastination focus", "On",
            "Decrastination watches which app is in front. While homework or other work is due…",
        )

        assertEquals(Verdict.Back("the focus service's accessibility page"), verdict)
    }

    @Test
    fun `the stock Use switch also marks the service's page`() {
        assertIs<Verdict.Back>(decide(GuardRules.SETTINGS, "Decrastination focus", "Use Decrastination focus", "Shortcut"))
    }

    @Test
    fun `the list of installed services stays usable`() {
        val verdict = decide(
            GuardRules.SETTINGS,
            "Installed apps", "Decrastination focus", "On",
            "Shows your next task in place of apps you've chosen to block while work is due",
            "Teams Assignments sync", "On",
        )

        assertEquals(Verdict.Leave, verdict)
    }

    @Test
    fun `other services' pages stay usable`() {
        assertEquals(
            Verdict.Leave,
            decide(GuardRules.SETTINGS, "Teams Assignments sync", "Use Teams Assignments sync", "When you tap refresh on the Teams Assignments widget…"),
        )
    }

    @Test
    fun `this app's App info is left`() {
        assertEquals(
            Verdict.Back("this app's App info"),
            decide(GuardRules.SETTINGS, "App info", "Decrastination", "Version 0.0.1", "Open", "Uninstall", "Force stop"),
        )
    }

    @Test
    fun `other apps' App info stays usable`() {
        assertEquals(Verdict.Leave, decide(GuardRules.SETTINGS, "App info", "YouTube", "Open", "Uninstall", "Force stop"))
    }

    @Test
    fun `the apps list, which names this app among others, stays usable`() {
        assertEquals(Verdict.Leave, decide(GuardRules.SETTINGS, "Apps", "Decrastination", "Discord", "YouTube"))
    }

    @Test
    fun `an uninstall prompt naming this app is left`() {
        // Captured on the phone (One UI, 8 Oct).
        assertEquals(
            Verdict.Back("an uninstall prompt for this app"),
            decide("com.google.android.packageinstaller", "Uninstall this app?", "Decrastination", "Cancel", "Uninstall"),
        )
        assertIs<Verdict.Back>(decide("com.android.packageinstaller", "Decrastination", "Do you want to uninstall this app?", "Cancel", "OK"))
    }

    @Test
    fun `the installer's other prompts for this app stay usable`() {
        // Codex review: installing or updating it goes through the same installer.
        assertEquals(Verdict.Leave, decide("com.google.android.packageinstaller", "Decrastination", "Do you want to update this app?", "Cancel", "Update"))
        assertEquals(Verdict.Leave, decide("com.google.android.packageinstaller", "Allow Decrastination to access existing notes, cards…", "Allow", "Don't allow"))
    }

    @Test
    fun `an uninstall prompt for another app is left alone`() {
        assertEquals(Verdict.Leave, decide("com.google.android.packageinstaller", "YouTube", "Do you want to uninstall this app?"))
    }

    @Test
    fun `other apps are never read`() {
        assertEquals(Verdict.Leave, decide("com.google.android.youtube", "Decrastination focus", "Use Decrastination focus"))
    }

    @Test
    fun `padding around texts doesn't hide a match`() {
        assertIs<Verdict.Back>(decide(GuardRules.SETTINGS, " Decrastination ", "Force stop "))
    }
}
