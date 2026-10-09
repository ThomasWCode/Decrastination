package com.thomaswcode.decrastination.protect

/**
 * The watchdog's reading of the phone's accessibility settings (docs/scheduler.md §6, layer 5),
 * pure. The focus service must be in `enabled_accessibility_services`, with accessibility on, and
 * on none of the shortcuts that would let a key press switch it off. With WRITE_SECURE_SETTINGS
 * (granted over adb at setup, Q19) the watchdog can put all of that right itself.
 */
object ProtectionCheck {

    /** What each shortcut setting is, in words. */
    val SHORTCUT_NAMES = mapOf(
        "accessibility_shortcut_target_service" to "the volume-key shortcut",
        "accessibility_button_targets" to "the accessibility button",
        "accessibility_gesture_targets" to "the accessibility gesture",
        "accessibility_qs_targets" to "Quick Settings",
    )

    /** The secure settings that hold accessibility shortcuts on Android 16 / One UI. */
    val SHORTCUT_KEYS = listOf(
        "accessibility_shortcut_target_service",
        "accessibility_button_targets",
        "accessibility_gesture_targets",
        "accessibility_qs_targets",
    )

    /** The service's component as settings write it, long or short: `pkg/pkg.block.X` or `pkg/.block.X`. */
    fun matches(entry: String, packageName: String, className: String): Boolean {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) return false
        val pkg = trimmed.substringBefore('/')
        val cls = trimmed.substringAfter('/', "")
        val full = if (cls.startsWith(".")) pkg + cls else cls
        return pkg == packageName && full == className
    }

    fun entries(list: String?): List<String> = list.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }

    fun isEnabled(enabledServices: String?, packageName: String, className: String): Boolean =
        entries(enabledServices).any { matches(it, packageName, className) }

    /** The service added to the enabled list, the others kept as they were. */
    fun withService(enabledServices: String?, packageName: String, className: String): String {
        val kept = entries(enabledServices)
        return if (kept.any { matches(it, packageName, className) }) kept.joinToString(":") else (kept + "$packageName/$className").joinToString(":")
    }

    /** The service taken off a shortcut list, the others kept. */
    fun withoutService(list: String?, packageName: String, className: String): String =
        entries(list).filterNot { matches(it, packageName, className) }.joinToString(":")

    data class Report(
        val serviceEnabled: Boolean,
        val accessibilityOn: Boolean,
        /** The shortcut settings the service is on. */
        val onShortcuts: List<String>,
        /**
         * Shortcut settings Android won't let this app read (Android 12+ refuses some hidden keys:
         * `accessibility_qs_targets` on the phone). Unknown, so never reported as clear.
         */
        val unreadableShortcuts: List<String> = emptyList(),
        val adminActive: Boolean,
        val canRepair: Boolean,
        /**
         * Whether Android has the service bound. Switched on but not bound means it crashed:
         * Android doesn't bind a crashed service again until it's switched off and on.
         */
        val serviceRunning: Boolean = true,
    )

    /** Switched on in the settings but not running. */
    fun stopped(report: Report): Boolean = report.serviceEnabled && report.accessibilityOn && !report.serviceRunning

    /** What's wrong, in words, worst first. Unarmed, only the service being off matters (blocking needs it). */
    fun problems(report: Report, armed: Boolean): List<String> = buildList {
        if (!report.serviceEnabled || !report.accessibilityOn) add("The focus service is off: nothing is blocked")
        else if (!report.serviceRunning) add("The focus service has stopped: nothing is blocked")
        if (!armed) return@buildList
        if (report.onShortcuts.isNotEmpty()) add("The focus service is on an accessibility shortcut")
        if (!report.adminActive) add("Device admin is off: the app can be uninstalled")
    }

    /** How long a stopped service is given to come back by itself: it's briefly unbound while binding, at boot and after an update. */
    const val RESTART_GRACE_MS = 60_000L

    /** At most one restart in this long, so a service that crashes as it starts isn't restarted over and over. */
    const val RESTART_GAP_MS = 10 * 60_000L

    /** A restart that leaves the service stopped within this long didn't take. */
    const val RESTART_SETTLE_MS = 60_000L

    /**
     * When to switch a stopped service off and on again: a minute after it was first seen stopped,
     * and ten minutes after the last restart; but at once if the first restart of a run ([tries]
     * 1) left it stopped within a minute. Null while it's running or switched off, or if it can't
     * be. That's no override of anyone's choice (it's still switched on), so it doesn't wait for
     * arming.
     */
    fun restartAt(report: Report, stoppedSince: Long?, restartedAt: Long?, tries: Int = 0): Long? {
        if (!stopped(report) || !report.canRepair || stoppedSince == null) return null
        if (restartedAt != null && tries == 1 && stoppedSince - restartedAt in 0..RESTART_SETTLE_MS) return stoppedSince
        val afterGrace = stoppedSince + RESTART_GRACE_MS
        return if (restartedAt == null) afterGrace else maxOf(afterGrace, restartedAt + RESTART_GAP_MS)
    }

    fun shouldRestart(report: Report, stoppedSince: Long?, restartedAt: Long?, now: Long, tries: Int = 0): Boolean =
        restartAt(report, stoppedSince, restartedAt, tries)?.let { now >= it } == true

    /** What the shortcut check found, in words, saying plainly what it couldn't check. */
    fun shortcutsDetail(report: Report): String {
        if (report.onShortcuts.isNotEmpty()) return "It's on ${report.onShortcuts.joinToString { SHORTCUT_NAMES[it] ?: it }}."
        if (report.unreadableShortcuts.isEmpty()) return "Good: no shortcut can switch the service off."
        val unknown = report.unreadableShortcuts.joinToString { SHORTCUT_NAMES[it] ?: it }
        return "Not on the shortcuts Android lets this app read. $unknown can't be read on this phone; " +
            "once armed, the service switched off any way is switched back on at once."
    }
}
