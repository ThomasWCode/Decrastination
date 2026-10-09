package com.thomaswcode.decrastination.protect

/**
 * The watchdog's reading of the phone's accessibility settings (docs/scheduler.md §6, layer 5),
 * pure. The focus service must be in `enabled_accessibility_services`, with accessibility on, and
 * on none of the shortcuts that would let a key press switch it off. With WRITE_SECURE_SETTINGS
 * (granted over adb at setup, Q19) the watchdog can put all of that right itself.
 */
object ProtectionCheck {

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
        val adminActive: Boolean,
        val canRepair: Boolean,
    )

    /** What's wrong, in words, worst first. Unarmed, only the service being off matters (blocking needs it). */
    fun problems(report: Report, armed: Boolean): List<String> = buildList {
        if (!report.serviceEnabled || !report.accessibilityOn) add("The focus service is off: nothing is blocked")
        if (!armed) return@buildList
        if (report.onShortcuts.isNotEmpty()) add("The focus service is on an accessibility shortcut")
        if (!report.adminActive) add("Device admin is off: the app can be uninstalled")
    }
}
