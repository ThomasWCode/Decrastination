package com.thomaswcode.decrastination.probe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.thomaswcode.decrastination.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 0 spike of the blocker (docs/scheduler.md §4, §6). It logs every app and screen that
 * comes to the front, covers YouTube with [BlockedProbeActivity], and presses Back on this app's
 * own pages in Settings ([GuardRules]). Nothing is decided by a task list yet: YouTube is always
 * blocked while the service is on.
 */
class FocusProbeService : AccessibilityService() {

    private lateinit var labels: GuardRules.Labels
    private var lastGuardAt = 0L
    private var lastBackAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        labels = GuardRules.Labels(
            app = getString(R.string.app_name),
            service = getString(R.string.focus_service_label),
            serviceDescriptionStart = getString(R.string.focus_service_description).substringBefore("."),
        )
        _connected.value = true
        ProbeLog.add("Focus service connected; blocking ${BLOCKED.joinToString()}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val lag = SystemClock.uptimeMillis() - event.eventTime
            ProbeLog.add("Front: $pkg ${event.className} \"${event.text.joinToString(" | ")}\" (+$lag ms)")
            when {
                pkg in BLOCKED -> block(pkg, event.eventTime)
                GuardRules.watches(pkg) -> guard(pkg, firstLook = true)
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && GuardRules.watches(pkg)) {
            // Settings pages fill in after they open, and in-page navigation may not change the window.
            guard(pkg, firstLook = false)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        _connected.value = false
        ProbeLog.add("Focus service disconnected")
        return super.onUnbind(intent)
    }

    private fun block(pkg: String, eventTime: Long) {
        ProbeLog.add("Blocking $pkg")
        startActivity(
            Intent(this, BlockedProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BlockedProbeActivity.EXTRA_PACKAGE, pkg)
                .putExtra(BlockedProbeActivity.EXTRA_EVENT_TIME, eventTime),
        )
    }

    private fun guard(pkg: String, firstLook: Boolean) {
        if (!guardEnabled) return
        val now = SystemClock.uptimeMillis()
        // Content changes come in bursts; one look per interval is plenty.
        if (!firstLook && now - lastGuardAt < GUARD_INTERVAL_MS) return
        if (now - lastBackAt < BACK_COOLDOWN_MS) return
        lastGuardAt = now
        val texts = screenTexts(pkg)
        if (firstLook) ProbeLog.add("  $pkg shows: ${texts.take(LOGGED_TEXTS).joinToString(" | ")}")
        val verdict = GuardRules.decide(pkg, texts, labels)
        if (verdict is GuardRules.Verdict.Back) {
            lastBackAt = now
            ProbeLog.add("Guard: Back, from ${verdict.reason}")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /** The texts and descriptions in [pkg]'s window, breadth first, at most [MAX_NODES] nodes. */
    private fun screenTexts(pkg: String): List<String> {
        val root = windows.mapNotNull { it.root }.firstOrNull { it.packageName == pkg }
            ?: rootInActiveWindow?.takeIf { it.packageName == pkg }
            ?: return emptyList()
        val texts = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return texts
    }

    companion object {
        /** Phase 0 blocks one app, always; Phase 3 takes the blocklist and the policy from the planner. */
        val BLOCKED = setOf("com.google.android.youtube")

        private const val GUARD_INTERVAL_MS = 250L
        private const val BACK_COOLDOWN_MS = 1_000L
        private const val MAX_NODES = 400
        private const val LOGGED_TEXTS = 40

        /** Switched off from the probe screen while developing; on whenever the process starts. */
        @Volatile
        var guardEnabled = true

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val info = it.resolveInfo.serviceInfo
                info.packageName == context.packageName && info.name == FocusProbeService::class.java.name
            }
        }
    }
}
