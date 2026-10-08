package com.thomaswcode.decrastination.probe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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

    /** Events arrive on the main thread; deferred looks run there too. */
    private val handler = Handler(Looper.getMainLooper())
    private var lastGuardAt = 0L
    private var lastBackAt = Long.MIN_VALUE / 2
    private var lookPending: String? = null
    private var emptyLooks = 0
    private var lastBlockAt = Long.MIN_VALUE / 2

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
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // The windows on screen changed: a blocked app may have come forward without a
            // window-state event of its own, or before one arrives (they can be 0.6 s late), and
            // a Settings window beside another app may just have become the one in use.
            val front = frontPackage() ?: return
            val now = SystemClock.uptimeMillis()
            when {
                front in BLOCKED -> if (now - lastBlockAt > WINDOWS_BLOCK_GAP_MS) block(front, event.eventTime)
                GuardRules.watches(front) -> guard(front, firstLook = false)
            }
            return
        }
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val lag = SystemClock.uptimeMillis() - event.eventTime
            ProbeLog.add("Front: $pkg ${event.className} \"${event.text.joinToString(" | ")}\" (+$lag ms)")
            when {
                pkg in BLOCKED -> blockIfInFront(pkg, event.eventTime)
                GuardRules.watches(pkg) -> guard(pkg, firstLook = true)
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && GuardRules.watches(pkg)) {
            // Settings pages fill in after they open, and in-page navigation may not change the window.
            guard(pkg, firstLook = false)
        }
    }

    /**
     * Blocks [pkg] unless another app is plainly in front by now. A blocked app's events can
     * arrive after it has gone, its own screen replacing its splash behind the block screen, or
     * after the user has gone home, and covering Home would be wrong. When what's in front can't
     * be told, it blocks.
     */
    private fun blockIfInFront(pkg: String, eventTime: Long) {
        val front = frontPackage()
        if (front != null && front != pkg) {
            ProbeLog.add("  $pkg isn't in front any more ($front is): left alone")
            return
        }
        block(pkg, eventTime)
    }

    /** The package of the window the user is using (its active window), or null if there's none to read. */
    private fun frontPackage(): String? =
        (windows.firstOrNull { it.isActive }?.root ?: rootInActiveWindow)?.packageName?.toString()

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(deferredLook)
        lookPending = null
        _connected.value = false
        ProbeLog.add("Focus service disconnected")
        return super.onUnbind(intent)
    }

    private fun block(pkg: String, eventTime: Long) {
        lastBlockAt = SystemClock.uptimeMillis()
        ProbeLog.add("Blocking $pkg")
        startActivity(
            Intent(this, BlockedProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BlockedProbeActivity.EXTRA_PACKAGE, pkg)
                .putExtra(BlockedProbeActivity.EXTRA_EVENT_TIME, eventTime),
        )
    }

    /**
     * Looks at [pkg]'s window and presses Back if it's one of this app's pages. Content changes
     * come in bursts, so it looks at most once per [GUARD_INTERVAL_MS], and never within
     * [BACK_COOLDOWN_MS] of pressing Back; a change that arrives meanwhile is looked at once the
     * wait is over rather than dropped, since the last change of a burst is often the one that
     * fills the page in. A window that shows nothing yet is looked at again shortly.
     */
    private fun guard(pkg: String, firstLook: Boolean) {
        if (!guardEnabled) return
        // A new window starts a new run of looks, with its own retries if it shows nothing yet.
        if (firstLook) emptyLooks = 0
        val now = SystemClock.uptimeMillis()
        val wait = maxOf(
            if (firstLook) 0L else GUARD_INTERVAL_MS - (now - lastGuardAt),
            BACK_COOLDOWN_MS - (now - lastBackAt),
        )
        if (wait > 0) return lookLater(pkg, wait)
        lastGuardAt = now
        val texts = screenTexts(pkg)
        if (firstLook) ProbeLog.add("  $pkg shows: ${texts.take(LOGGED_TEXTS).joinToString(" | ")}")
        if (texts.isEmpty()) {
            if (emptyLooks++ < MAX_EMPTY_LOOKS) lookLater(pkg, GUARD_INTERVAL_MS)
            return
        }
        emptyLooks = 0
        val verdict = GuardRules.decide(pkg, texts, labels)
        if (verdict is GuardRules.Verdict.Back) {
            // Back goes to the window in use. With Settings beside another app (split screen, a
            // pop-up), pressing it now would hit that app; once Settings is in use, its window
            // change brings another look.
            val front = frontPackage()
            if (front != null && front != pkg) {
                ProbeLog.add("Guard: ${verdict.reason} is showing, but $front is in use: not pressing Back")
                return
            }
            lastBackAt = now
            ProbeLog.add("Guard: Back, from ${verdict.reason}")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /** One deferred look at the latest window to need it; further requests meanwhile join it. */
    private fun lookLater(pkg: String, delayMs: Long) {
        if (lookPending == null) handler.postDelayed(deferredLook, delayMs)
        lookPending = pkg
    }

    private val deferredLook = Runnable {
        val pkg = lookPending ?: return@Runnable
        lookPending = null
        guard(pkg, firstLook = false)
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

        /** How many times in a row an empty window is looked at again: a second's worth. */
        private const val MAX_EMPTY_LOOKS = 4

        /** Window changes come in bursts while the block screen opens; one block per burst. */
        private const val WINDOWS_BLOCK_GAP_MS = 500L
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
