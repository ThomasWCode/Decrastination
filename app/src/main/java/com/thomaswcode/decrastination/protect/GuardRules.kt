package com.thomaswcode.decrastination.protect

/**
 * Layer 2 of the anti-tamper (docs/scheduler.md §6): given the texts a Settings, Device Care or
 * package-installer window shows, whether to press Back. Active only once protection is armed.
 * It only ever matches this app's own pages and the reset pages, so the rest of Settings, other
 * apps' pages included, stays usable:
 *
 * - the focus service's accessibility page, where it would be switched off (proved in Phase 0);
 * - this app's App info (Force stop, Uninstall) and its Storage page (Clear data);
 * - the device-admin page for this app, where the admin would be deactivated;
 * - Device Care's page for this app, which can force-stop it;
 * - the confirmation pages of "Reset all settings" and "Reset accessibility settings";
 * - an uninstall prompt that names this app (not the installer's other prompts, such as an update).
 *
 * Putting the service on an accessibility shortcut is caught by its click (the focus service) and
 * undone by the watchdog.
 */
object GuardRules {

    const val SETTINGS = "com.android.settings"
    const val DEVICE_CARE = "com.samsung.android.lool"

    /** The packages whose windows are read at all. */
    fun watches(packageName: String): Boolean =
        packageName == SETTINGS || packageName == DEVICE_CARE || "packageinstaller" in packageName

    /** What this app is called on screen. */
    data class Labels(
        val app: String,
        val service: String,
        /** The first sentence of the service's description: on its accessibility page only. */
        val serviceDescriptionStart: String,
    )

    sealed interface Verdict {
        data object Leave : Verdict
        data class Back(val reason: String) : Verdict
    }

    private val APP_INFO_ACTIONS = setOf("Force stop", "Uninstall")
    private val CLEAR_DATA = setOf("Clear data", "Clear storage")
    private val RESET_PAGES = setOf("Reset all settings", "Reset accessibility settings")
    private const val UNINSTALL = "uninstall"

    fun decide(packageName: String, texts: List<String>, labels: Labels): Verdict {
        if (!watches(packageName)) return Verdict.Leave
        val trimmed = texts.map { it.trim() }
        val namesApp = labels.app in trimmed
        when (packageName) {
            SETTINGS -> {
                val onServicePage = labels.service in trimmed &&
                    trimmed.any { it.startsWith(labels.serviceDescriptionStart) || it == "Use ${labels.service}" }
                if (onServicePage) return Verdict.Back("the focus service's accessibility page")
                if (namesApp && trimmed.any { it in APP_INFO_ACTIONS }) return Verdict.Back("this app's App info")
                if (namesApp && trimmed.any { it in CLEAR_DATA }) return Verdict.Back("this app's storage")
                if (namesApp && trimmed.any { it.startsWith("Deactivate") } && trimmed.any { "device admin" in it.lowercase() }) {
                    return Verdict.Back("this app's device admin page")
                }
                // The date and time: set forward, it would hurry what protection times (a parent's
                // hour, a session's end). On the phone (9 Oct) the page shows the guard its title
                // and "Use 24-hour format", not its automatic switches; its entry in General
                // management has neither of those beside it.
                if (trimmed.any { it == "Date and time" } && trimmed.any { it == "Use 24-hour format" || it == "Automatic date and time" }) {
                    return Verdict.Back("the date and time settings")
                }
                // The confirmation page, with its button, not the list of resets (titled "Reset") that
                // leads to it. One UI's button text is taken from its other reset pages: unverified.
                if (trimmed.any { it in RESET_PAGES } && trimmed.any { it == "Reset settings" }) {
                    return Verdict.Back("a settings reset")
                }
            }
            DEVICE_CARE -> if (namesApp && trimmed.any { it == "Force stop" }) return Verdict.Back("this app in Device Care")
            else -> if (trimmed.any { labels.app in it } && trimmed.any { UNINSTALL in it.lowercase() }) {
                // One UI: "Uninstall this app?", the app's name, Cancel and Uninstall. The installer
                // also asks before installing or updating an app, and must still be able to.
                return Verdict.Back("an uninstall prompt for this app")
            }
        }
        return Verdict.Leave
    }
}
