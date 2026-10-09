package com.thomaswcode.decrastination.block

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.protect.GuardRules
import com.thomaswcode.decrastination.protect.Watchdog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The blocker (docs/scheduler.md §4 and §6; proved as Phase 0's spike, docs/phase0-findings.md
 * §5). An accessibility service: it hears every app come to the front, and covers a blocked one
 * with [BlockedActivity] when [Focus] says so:
 *
 * - **Apps and browsers**: a blocked app, or Firefox or Tor, the moment it owns the window in use
 *   (window changes as well as window states, so within about 0.2 s), or anywhere on screen in a
 *   split or pop-up window.
 * - **Sites**: in Chrome and Brave, the address bar is read as it changes.
 * - **Picture-in-picture**: the block screen starts with NO_USER_ACTION, so covering a playing
 *   video never sends it there, and a blocked app already floating is brought back and covered.
 * - **Free time**: while a blocked app is allowed on earned time, the time is spent, and it's
 *   blocked when that runs out.
 *
 * Besides: it ends focus sessions on time, offers the automatic Teams syncs (with their banner),
 * applies pending setting changes when due, runs the watchdog's check, and, once protection is
 * armed, the settings guard (layer 2), which backs out of this app's own pages in Settings.
 */
class FocusService : AccessibilityService() {

    private lateinit var graph: AppGraph
    private lateinit var labels: GuardRules.Labels
    private lateinit var banner: CountdownBanner
    /**
     * The work of one connection. A failure in one piece of it is logged, never allowed to bring
     * the blocker down. Cancelled when the connection ends; a new one starts its own.
     */
    private var scope = newScope()

    private fun newScope() = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> Log.e(TAG, "Focus service work failed", error) },
    )

    /**
     * Connected and set up. Android can connect one instance twice (after a crash it can hold two
     * connections to it) and connect it again after a disconnection, so both are guarded.
     */
    private var active = false

    /** Events arrive on the main thread; deferred work runs there too. */
    private val handler = Handler(Looper.getMainLooper())

    private var lastBlockAt = Long.MIN_VALUE / 2
    private var lastBlockedName: String? = null
    private var lastPipRelaunchAt = Long.MIN_VALUE / 2

    /** A blocked app allowed on earned time, and since when (elapsed realtime). */
    private var spending: Pair<Focus.Target, Long>? = null

    private var lastUrlCheckAt = 0L
    private var urlCheckPending: String? = null

    // The settings guard's pacing, as Phase 0 tuned it.
    private var lastGuardAt = 0L
    private var lastBackAt = Long.MIN_VALUE / 2
    private var lookPending: String? = null
    private var emptyLooks = 0

    /** When the phone was last unlocked (elapsed realtime), for the first-unlock Teams sync. */
    private var unlockedAt = Long.MIN_VALUE / 2

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_OFFER_TEAMS_SYNC -> offerTeamsSync(force = true)
                Intent.ACTION_USER_PRESENT -> {
                    unlockedAt = SystemClock.elapsedRealtime()
                    handler.post { tick() }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // Locked, nothing is being used: stop, so the time asleep isn't spent.
                    stopSpending()
                    banner.cancel()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        hosted = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (active) {
            Log.i(TAG, "Focus service connected again")
            return
        }
        active = true
        if (!scope.isActive) scope = newScope()
        graph = AppGraph.get(this)
        labels = GuardRules.Labels(
            app = getString(R.string.app_name),
            service = getString(R.string.focus_service_label),
            serviceDescriptionStart = getString(R.string.focus_service_description).substringBefore("."),
        )
        banner = CountdownBanner(this)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(ACTION_OFFER_TEAMS_SYNC)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        _connected.value = true
        Log.i(TAG, "Focus service connected")
        closeBlockedPictureInPicture()
        frontPackage()?.let { onFront(it, firstLook = true) }
        handler.post(ticker)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // The windows on screen changed: a blocked app may have come forward before its own
                // window-state event (they can be 0.6 s late), or be visible beside another.
                closeBlockedPictureInPicture()
                coverBlockedSideWindows()
                frontPackage()?.let { onFront(it, firstLook = false) }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg == packageName) return
                // A blocked app's late events can come after it has gone (Phase 0): judge what's in front.
                val front = frontPackage()
                onFront(if (front != null && front != pkg) front else pkg, firstLook = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (graph.focus.isCheckedBrowser(pkg)) checkAddressLater(pkg)
                if (GuardRules.watches(pkg)) guard(pkg, firstLook = false)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClick(event)
            else -> Unit
        }
    }

    /** [pkg] owns the window in use: block it, let it spend free time, or leave it. */
    private fun onFront(pkg: String, firstLook: Boolean) {
        if (pkg == packageName) {
            // Time in this app isn't time on the blocked one.
            stopSpending()
            return
        }
        val target = graph.focus.target(pkg)
        when {
            target != null -> act(target)
            graph.focus.isCheckedBrowser(pkg) -> checkAddress(pkg)
            else -> stopSpending()
        }
        if (GuardRules.watches(pkg)) guard(pkg, firstLook)
    }

    /** What [target] meets now. */
    private fun act(target: Focus.Target) {
        when (val verdict = graph.focus.verdict()) {
            is BlockPolicy.Verdict.Block -> {
                stopSpending()
                block(target, verdict.reason)
            }
            BlockPolicy.Verdict.Spend -> startSpending(target)
            is BlockPolicy.Verdict.Allow -> stopSpending()
        }
    }

    /**
     * Covers [target] with the block screen. NO_USER_ACTION tells Android this isn't the user
     * leaving it, so a playing video gets no onUserLeaveHint and doesn't float off into
     * picture-in-picture (8 Oct). Window changes come in bursts as it opens: one block per burst.
     */
    private fun block(target: Focus.Target, reason: BlockPolicy.Reason) {
        val now = SystemClock.uptimeMillis()
        if (target.name == lastBlockedName && now - lastBlockAt < BLOCK_GAP_MS) return
        lastBlockAt = now
        lastBlockedName = target.name
        Log.i(TAG, "Blocking ${target.name}: $reason")
        startActivity(
            BlockedActivity.intent(this, target, reason)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        )
        scope.launch { graph.focus.recordBlock(target, reason) }
    }

    // --- Free time ---

    private fun startSpending(target: Focus.Target) {
        if (spending?.first == target) return
        commitSpending()
        spending = target to SystemClock.elapsedRealtime()
        // Block again the moment it runs out.
        handler.removeCallbacks(creditCheck)
        handler.postDelayed(creditCheck, graph.focus.creditLeftMs().coerceIn(1_000L, SPEND_TICK_MS))
    }

    private fun stopSpending() {
        commitSpending()
        spending = null
        handler.removeCallbacks(creditCheck)
    }

    /** Takes the time spent so far off the credit. */
    private fun commitSpending() {
        val (target, since) = spending ?: return
        val now = SystemClock.elapsedRealtime()
        spending = target to now
        // The app's scope: this connection's may be ending.
        graph.scope.launch { graph.focus.spend(now - since) }
    }

    /**
     * While free time is being spent: gone from the front, it stops; out of time, or with work
     * now due, it's blocked; otherwise it looks again later. The time left is worked out here,
     * since the stored credit catches up a moment after each [commitSpending].
     */
    private val creditCheck: Runnable = Runnable {
        val (target, since) = spending ?: return@Runnable
        val front = frontPackage()
        val stillThere = when (target) {
            is Focus.Target.Site -> front == target.browser
            else -> front == target.name
        }
        if (!stillThere) return@Runnable stopSpending()
        val left = graph.focus.creditLeftMs() - (SystemClock.elapsedRealtime() - since)
        val verdict = graph.focus.verdict()
        when {
            left <= 0 -> {
                stopSpending()
                block(target, BlockPolicy.Reason.NoFreeTime)
            }
            verdict is BlockPolicy.Verdict.Block -> {
                stopSpending()
                block(target, verdict.reason)
            }
            else -> handler.postDelayed(creditCheck, left.coerceIn(1_000L, SPEND_TICK_MS))
        }
    }

    // --- Sites in Chrome and Brave ---

    /** Address bars change with every keystroke and scroll: read at most every [URL_CHECK_MS]. */
    private fun checkAddressLater(browser: String) {
        val wait = URL_CHECK_MS - (SystemClock.uptimeMillis() - lastUrlCheckAt)
        if (wait <= 0) return checkAddress(browser)
        if (urlCheckPending == null) handler.postDelayed({ urlCheckPending?.let(::checkAddress); urlCheckPending = null }, wait)
        urlCheckPending = browser
    }

    private fun checkAddress(browser: String) {
        lastUrlCheckAt = SystemClock.uptimeMillis()
        if (frontPackage() != browser) return
        val root = windows.mapNotNull { it.root }.firstOrNull { it.packageName == browser } ?: rootInActiveWindow ?: return
        val text = root.findAccessibilityNodeInfosByViewId(Blocklist.urlBarId(browser)).firstOrNull()?.text?.toString()
        val site = graph.focus.siteTarget(browser, text)
        if (site != null) act(site) else if (spending?.first is Focus.Target.Site) stopSpending()
    }

    // --- Windows other than the one in use ---

    /**
     * A blocked app in picture-in-picture is never the window in use, so the active-window check
     * misses it. One UI's floating window offers no dismiss, so the app is brought back to full
     * screen, as tapping its icon does, where the next window change covers it (Phase 0).
     */
    private fun closeBlockedPictureInPicture() {
        for (window in windows) {
            if (!window.isInPictureInPictureMode) continue
            val pkg = window.root?.packageName?.toString() ?: continue
            val browser = graph.focus.target(pkg) == null && graph.focus.isCheckedBrowser(pkg)
            if ((graph.focus.target(pkg) == null && !browser) || graph.focus.verdict() !is BlockPolicy.Verdict.Block) continue
            // A browser's video: its address, where Android still shows it, says whether it's a
            // blocked site; where it doesn't, the browser is brought back and its page checked.
            if (browser) {
                val address = window.root?.findAccessibilityNodeInfosByViewId(Blocklist.urlBarId(pkg))?.firstOrNull()?.text?.toString()
                if (address != null && graph.focus.siteTarget(pkg, address) == null) continue
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastPipRelaunchAt < PIP_RELAUNCH_GAP_MS) continue
            lastPipRelaunchAt = now
            Log.i(TAG, "Picture-in-picture: $pkg, bringing it back to cover it")
            val dismiss = AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS
            val root = window.root ?: continue
            if (dismiss in root.actionList && root.performAction(dismiss.id)) continue
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** A blocked app beside another (split screen, a pop-up window): leave both, and cover it. */
    private fun coverBlockedSideWindows() {
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.isInPictureInPictureMode }
        if (appWindows.size < 2) return
        val blocked = appWindows.firstOrNull { !it.isActive && it.root?.packageName?.toString()?.let(graph.focus::target) != null } ?: return
        val pkg = blocked.root?.packageName?.toString() ?: return
        val verdict = graph.focus.verdict()
        if (verdict !is BlockPolicy.Verdict.Block) return
        Log.i(TAG, "$pkg is on screen beside another app: leaving both")
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({ graph.focus.target(pkg)?.let { block(it, verdict.reason) } }, 300)
    }

    /** The package of the window the user is using (its active window), or null if there's none to read. */
    private fun frontPackage(): String? =
        (windows.firstOrNull { it.isActive }?.root ?: rootInActiveWindow)?.packageName?.toString()

    // --- The settings guard (layer 2), armed only ---

    /**
     * Looks at [pkg]'s window and presses Back if it's one of this app's pages. At most once per
     * [GUARD_INTERVAL_MS] and never within [BACK_COOLDOWN_MS] of pressing Back; a change that
     * arrives meanwhile is looked at once the wait is over, since the last of a burst often fills
     * the page in. A window showing nothing yet is looked at again shortly (Phase 0's tuning).
     */
    private fun guard(pkg: String, firstLook: Boolean) {
        if (!graph.settings.value.armed) return
        if (firstLook) emptyLooks = 0
        val now = SystemClock.uptimeMillis()
        val wait = maxOf(if (firstLook) 0L else GUARD_INTERVAL_MS - (now - lastGuardAt), BACK_COOLDOWN_MS - (now - lastBackAt))
        if (wait > 0) return lookLater(pkg, wait)
        lastGuardAt = now
        val texts = screenTexts(pkg)
        if (texts.isEmpty()) {
            if (emptyLooks++ < MAX_EMPTY_LOOKS) lookLater(pkg, GUARD_INTERVAL_MS)
            return
        }
        emptyLooks = 0
        val verdict = GuardRules.decide(pkg, texts, labels)
        if (verdict is GuardRules.Verdict.Back) {
            // Back goes to the window in use: beside another app, wait until Settings is the one.
            val front = frontPackage()
            if (front != null && front != pkg) return
            lastBackAt = now
            Log.i(TAG, "Guard: Back, from ${verdict.reason}")
            // Android can refuse Back (a window mid-transition): Home leaves the page all the same.
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
                Log.i(TAG, "Guard: Back refused, Home instead")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    private fun lookLater(pkg: String, delayMs: Long) {
        if (lookPending == null) handler.postDelayed(deferredLook, delayMs)
        lookPending = pkg
    }

    private val deferredLook = Runnable {
        val pkg = lookPending ?: return@Runnable
        lookPending = null
        guard(pkg, firstLook = false)
    }

    /**
     * A tap on this service's name in Settings while a shortcut page is showing: putting it on an
     * accessibility shortcut would let a key press switch it off (Phase 0's finding), so it's
     * undone at once; the watchdog takes it off any shortcut too.
     */
    private fun onClick(event: AccessibilityEvent) {
        if (!graph.settings.value.armed || event.packageName?.toString() != GuardRules.SETTINGS) return
        if (event.text.none { labels.service in it.toString() }) return
        if (screenTexts(GuardRules.SETTINGS).none { "shortcut" in it.lowercase() }) return
        Log.i(TAG, "Guard: the focus service was tapped on a shortcut page")
        performGlobalAction(GLOBAL_ACTION_BACK)
        scope.launch { Watchdog.check(this@FocusService, repair = true) }
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

    // --- Every half minute ---

    private var lastWatchdogAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (active) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun tick() {
        // The connection is gone though this instance lives on: switched off while a stale
        // connection keeps it bound (no onUnbind comes), or, after a crash and restart, reset by
        // that stale connection while Android still counts it bound. Both seen on the phone; in
        // the second Android delivers it nothing, so the watchdog has to restart it.
        if (serviceInfo == null) {
            disconnect("its connection is gone")
            return
        }
        // An app already in front when blocking begins (16:45, the end of free time, new work due)
        // sends no event of its own, nor does a blocked site already open in Chrome or Brave: look
        // at whatever is in front now. (While free time is being spent, its own check does this.)
        frontPackage()?.takeIf { it != packageName && spending == null }?.let { front ->
            when {
                graph.focus.target(front) != null -> onFront(front, firstLook = false)
                graph.focus.isCheckedBrowser(front) -> checkAddress(front)
            }
        }
        scope.launch {
            // A session that has run its time.
            graph.focus.session?.takeIf { graph.clock.now() >= it.endsAt }?.let { Sessions.end(this@FocusService, early = false) }
            graph.applyDueChanges()
            val now = SystemClock.elapsedRealtime()
            if (now - lastWatchdogAt >= WATCHDOG_MS) {
                lastWatchdogAt = now
                Watchdog.check(this@FocusService, repair = true)
            }
            offerTeamsSync()
        }
    }

    /** The automatic Teams sync (Q21), when due, while the phone is unlocked and in use; with [force], now (a test hook). */
    private fun offerTeamsSync(force: Boolean = false) {
        if (banner.isShowing) return
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        if (power?.isInteractive != true || keyguard?.isKeyguardLocked == true) return
        if (getSystemService(AudioManager::class.java)?.mode != AudioManager.MODE_NORMAL) return
        val runtime = graph.runtime.value
        val trigger = TeamsAutoSync.due(
            now = graph.clock.now(),
            zone = graph.clock.zone(),
            settings = graph.settings.value,
            state = runtime.teamsAuto,
            teamsSyncedAt = graph.tasks.value.status(Source.Teams).dataAsOf,
            unlocked = SystemClock.elapsedRealtime() - unlockedAt < UNLOCK_WINDOW_MS,
        ) ?: if (force) TeamsAutoSync.Trigger.Delayed else return
        Log.i(TAG, "Offering an automatic Teams sync ($trigger)")
        banner.show(
            message = "Syncing Teams in %d s",
            seconds = BANNER_SECONDS,
            onCancel = { scope.launch { recordOffer() } },
            onDelay = { scope.launch { graph.runtime.update { it.copy(teamsAuto = TeamsAutoSync.delayed(it.teamsAuto, graph.clock.now())) } } },
            onTimeout = {
                scope.launch {
                    recordOffer()
                    graph.requestTeamsSync()?.let { Log.w(TAG, "Automatic Teams sync didn't start: $it") }
                }
            },
        )
    }

    private suspend fun recordOffer() {
        graph.runtime.update { it.copy(teamsAuto = TeamsAutoSync.offered(it.teamsAuto, graph.clock.now(), graph.clock.zone(), graph.settings.value)) }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect("unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect("destroyed")
        hosted = false
        super.onDestroy()
    }

    private fun disconnect(why: String) {
        if (!active) return
        active = false
        commitSpending()
        spending = null
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        banner.cancel()
        _connected.value = false
        Log.i(TAG, "Focus service disconnected: $why")
        // Off is a protection problem: the watchdog says so at once rather than at its next run,
        // and, once armed, switches the service back on.
        val app = applicationContext
        graph.scope.launch { Watchdog.check(app, repair = true) }
        scope.cancel()
    }

    companion object {
        private const val TAG = AppGraph.TAG
        private const val BLOCK_GAP_MS = 500L
        private const val PIP_RELAUNCH_GAP_MS = 2_000L
        private const val URL_CHECK_MS = 400L
        private const val SPEND_TICK_MS = 30_000L
        private const val GUARD_INTERVAL_MS = 250L
        private const val BACK_COOLDOWN_MS = 1_000L
        private const val MAX_EMPTY_LOOKS = 4
        private const val MAX_NODES = 400
        private const val TICK_MS = 30_000L
        private const val WATCHDOG_MS = 5 * 60_000L
        private const val UNLOCK_WINDOW_MS = 90_000L
        private const val BANNER_SECONDS = 10
        const val ACTION_OFFER_TEAMS_SYNC = "com.thomaswcode.decrastination.action.OFFER_TEAMS_SYNC"

        private val _connected = MutableStateFlow(false)

        /** This process's instance has a working connection: events reach it. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        /** An instance lives in this process (Android created it and hasn't destroyed it). */
        @Volatile
        private var hosted = false

        /**
         * Whether the service is really running. Here, where it lives, its own connection says;
         * Android can count an instance bound that it no longer sends anything (after a crash).
         * From a process without it, Android's list of bound services is all there is.
         */
        fun isRunning(context: Context): Boolean = if (hosted) _connected.value else isBound(context)

        /**
         * Whether Android has this service bound. [AccessibilityManager.getEnabledAccessibilityServiceList]
         * lists the bound services, not the setting's enabled ones: a crashed service, still in the
         * setting, isn't in it (a crash on the phone showed exactly that, 9 Oct).
         */
        fun isBound(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val info = it.resolveInfo.serviceInfo
                info.packageName == context.packageName && info.name == FocusService::class.java.name
            }
        }
    }
}
