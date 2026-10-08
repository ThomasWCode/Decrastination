package com.thomaswcode.decrastination.probe

/**
 * Phase 0 settings guard: given the texts a Settings or package-installer window shows, whether
 * to press Back. It only ever matches this app's own pages, so the rest of Settings, other apps'
 * pages included, stays usable:
 * - the focus service's accessibility page, where it would be switched off, recognised by the
 *   service's description (the list of installed services shows only labels and summaries);
 * - this app's App info, where Force stop switches the service off and Uninstall removes it;
 * - an uninstall prompt that names this app (not the installer's other prompts, such as an update).
 *
 * Phase 0 is about learning what One UI shows on these pages; the service logs every text it
 * sees there so these rules can be checked against the real screens.
 */
object GuardRules {

    const val SETTINGS = "com.android.settings"

    /** The packages whose windows are read at all. */
    fun watches(packageName: String): Boolean = packageName == SETTINGS || "packageinstaller" in packageName

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
    private const val UNINSTALL = "uninstall"

    fun decide(packageName: String, texts: List<String>, labels: Labels): Verdict {
        if (!watches(packageName)) return Verdict.Leave
        val trimmed = texts.map { it.trim() }
        if (packageName == SETTINGS) {
            val onServicePage = labels.service in trimmed &&
                trimmed.any { it.startsWith(labels.serviceDescriptionStart) || it == "Use ${labels.service}" }
            if (onServicePage) return Verdict.Back("the focus service's accessibility page")
            if (labels.app in trimmed && trimmed.any { it in APP_INFO_ACTIONS }) return Verdict.Back("this app's App info")
        } else if (trimmed.any { labels.app in it } && trimmed.any { UNINSTALL in it.lowercase() }) {
            // One UI: "Uninstall this app?", the app's name, Cancel and Uninstall. The installer
            // also asks before installing or updating an app, and must still be able to.
            return Verdict.Back("an uninstall prompt for this app")
        }
        return Verdict.Leave
    }
}
