package com.thomaswcode.decrastination.protect

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.block.FocusService
import com.thomaswcode.decrastination.block.Sessions
import com.thomaswcode.decrastination.data.ProtectionRecord
import com.thomaswcode.decrastination.data.ProtectionState
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.sync.SyncWorker
import com.thomaswcode.decrastination.ui.MainActivity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Layer 5 of the anti-tamper (docs/scheduler.md §6): checks that the focus service is on, on no
 * accessibility shortcut, and, once armed, that the device admin is active. Anything wrong shows
 * as a notification and on the widget, and is logged. Once armed, and with WRITE_SECURE_SETTINGS
 * (granted over adb, Q19), it switches the service back on and takes it off the shortcuts
 * itself. v1 sends no email (Q17): the alert stays on the phone.
 *
 * Runs from the focus service every five minutes, as a job every 15, at boot, and the moment the
 * service is switched off.
 *
 * A service that's switched on but not running has crashed, and Android won't bind it again until
 * it's switched off and on (seen on the phone, 9 Oct). The watchdog does that after a minute's
 * grace, armed or not, at most once in ten minutes; except that a restart that leaves it stopped
 * within a minute is tried once more at once. After a crash Android keeps the crashed connection
 * bound, hands the restarted service to it as well, and that connection then resets it: the
 * second restart, with the service already up, gets a clean connection (seen on the phone).
 */
object Watchdog {
    /**
     * How soon after a restart to look again: inside the minute a restart is given to settle (with
     * the alarm's own margin, about 40 seconds), so a restart that didn't take is tried again at once.
     */
    private const val RESTART_VERIFY_MS = 25_000L

    private const val NOTIFICATION_ID = 3001
    private const val TAG = AppGraph.TAG
    private const val RESTART_PAUSE_MS = 1_500L

    fun service(context: Context): ComponentName = ComponentName(context, FocusService::class.java)
    fun admin(context: Context): ComponentName = ComponentName(context, AdminReceiver::class.java)

    fun canRepair(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isAdminActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(admin(context)) == true

    /**
     * A secure setting (null when unset), or a failure if this app may not read it: Android 12+
     * refuses hidden keys to apps (`accessibility_qs_targets` threw on the phone, 9 Oct, and took
     * the service down).
     */
    private fun read(resolver: android.content.ContentResolver, key: String): Result<String?> =
        runCatching { Settings.Secure.getString(resolver, key) }

    fun report(context: Context): ProtectionCheck.Report {
        val resolver = context.contentResolver
        val pkg = context.packageName
        val cls = FocusService::class.java.name
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val on = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        val readings = ProtectionCheck.SHORTCUT_KEYS.associateWith { read(resolver, it) }
        val shortcuts = readings.filterValues { value ->
            value.getOrNull().let { ProtectionCheck.entries(it).any { entry -> ProtectionCheck.matches(entry, pkg, cls) } }
        }.keys.toList()
        return ProtectionCheck.Report(
            serviceEnabled = ProtectionCheck.isEnabled(enabled, pkg, cls),
            accessibilityOn = on,
            onShortcuts = shortcuts,
            unreadableShortcuts = readings.filterValues { it.isFailure }.keys.toList(),
            adminActive = isAdminActive(context),
            canRepair = canRepair(context),
            serviceRunning = FocusService.isRunning(context),
            alertsShown = Notify.shown(context, Channels.PROTECTION),
        )
    }

    /**
     * Switches a stopped service off and on again. Android lets a crashed service be bound again
     * once the setting stops listing it; the pause lets it see the list without the service before
     * the service is put back.
     */
    private suspend fun restart(context: Context): Boolean {
        if (!canRepair(context)) return false
        val resolver = context.contentResolver
        val pkg = context.packageName
        val cls = FocusService::class.java.name
        return runCatching {
            val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ProtectionCheck.withoutService(enabled, pkg, cls))
            delay(RESTART_PAUSE_MS)
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ProtectionCheck.withService(current, pkg, cls))
            true
        }.onFailure { Log.w(TAG, "Couldn't restart the focus service", it) }.getOrDefault(false)
    }

    /**
     * Puts the service back on and takes it off every shortcut. Needs WRITE_SECURE_SETTINGS;
     * returns whether it could.
     */
    fun repair(context: Context): Boolean {
        if (!canRepair(context)) return false
        val resolver = context.contentResolver
        val pkg = context.packageName
        val cls = FocusService::class.java.name
        return runCatching {
            val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ProtectionCheck.withService(enabled, pkg, cls))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            for (key in ProtectionCheck.SHORTCUT_KEYS) {
                // One that can't be read can't be filtered; switching off is undone instead.
                val value = read(resolver, key).getOrElse { continue }
                if (ProtectionCheck.entries(value).any { ProtectionCheck.matches(it, pkg, cls) }) {
                    runCatching { Settings.Secure.putString(resolver, key, ProtectionCheck.withoutService(value, pkg, cls)) }
                        .onFailure { Log.w(TAG, "Couldn't take the service off $key", it) }
                }
            }
            true
        }.onFailure { Log.w(TAG, "Couldn't repair the accessibility settings", it) }.getOrDefault(false)
    }

    /**
     * Checks, repairs if armed (and [repair] and able), restarts a stopped service (if [repair]
     * and able), records the finding, and says so in a notification while anything is wrong.
     */
    suspend fun check(context: Context, repair: Boolean) {
        val graph = AppGraph.get(context)
        val armed = graph.settings.value.armed
        var report = report(context)
        var problems = ProtectionCheck.problems(report, armed)
        // What was found, before any repair: a lapse put right at once is still a lapse.
        val found = problems
        var repaired = false
        if (problems.isNotEmpty() && armed && repair && (!report.serviceEnabled || !report.accessibilityOn || report.onShortcuts.isNotEmpty())) {
            repaired = repair(context)
            if (repaired) {
                report = report(context)
                problems = ProtectionCheck.problems(report, armed)
            }
        }
        val now = graph.clock.now()
        val before = graph.runtime.value.protection
        var stoppedSince = if (ProtectionCheck.stopped(report)) before.stoppedSince ?: now else null
        var restartedAt = before.restartedAt
        var tries = if (stoppedSince == null) 0 else before.restartTries
        val restartAt = ProtectionCheck.restartAt(report, stoppedSince, restartedAt, tries)
        if (repair && restartAt != null && now >= restartAt) {
            restartedAt = now
            tries += 1
            // A fresh start: found stopped again from here, it's this restart that didn't take.
            stoppedSince = null
            val restarted = restart(context)
            Log.i(TAG, "Protection: the focus service had stopped; ${if (restarted) "switched it off and on" else "couldn't restart it"}")
            // Back, it runs the watchdog as it connects. If not, a look once it should have settled
            // finds it stopped again, and the second try follows at once.
            WatchdogReceiver.checkIn(context, RESTART_VERIFY_MS)
        } else if (restartAt != null) {
            // Look again when the restart is due, rather than at the next periodic run.
            WatchdogReceiver.checkIn(context, restartAt - now)
        }
        graph.runtime.update {
            it.copy(
                protection = ProtectionState(
                    problems = problems,
                    offSince = if (problems.isEmpty()) null else before.offSince ?: now,
                    checkedAt = now,
                    stoppedSince = stoppedSince,
                    restartedAt = restartedAt,
                    restartTries = tries,
                ),
            )
        }
        // Each change, both ways, so the log shows how long each lapse lasted; and each repair, with
        // what it put right, even when the check ends where the last one did.
        if (repaired || problems != before.problems) {
            val recorded = if (repaired) found else problems
            graph.log.update { it.copy(protection = it.protection + ProtectionRecord(now, recorded, repaired)).trimmed(now) }
            Log.i(TAG, "Protection: ${recorded.ifEmpty { listOf("all well") }}${if (repaired) " (repaired)" else ""}")
        }
        if (problems.isEmpty()) Notify.cancel(context, NOTIFICATION_ID) else alert(context, problems, armed)
    }

    private fun alert(context: Context, problems: List<String>, armed: Boolean) {
        val open = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.OPEN_SETUP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        Notify.post(
            context,
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, Channels.PROTECTION)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle(if (armed) "PROTECTION OFF" else "Blocking is off")
                .setContentText(problems.first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(problems.joinToString("\n")))
                .setContentIntent(open)
                .setOngoing(true)
                .build(),
        )
    }
}

/** Layer 3: an active device admin can't be uninstalled until it's deactivated. It asks for no powers. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Protection is armed: switching this off lets Decrastination be uninstalled. Disarm it in the app instead."
}

/** The watchdog every 15 minutes, and pending setting changes applied when due. */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppGraph.get(applicationContext).applyDueChanges()
        // A session whose time is up, should neither the focus service nor its alarm have ended it.
        Sessions.end(applicationContext, early = false)
        Watchdog.check(applicationContext, repair = true)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "watchdog",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

    }
}

/**
 * A check at a set time: when a stopped service's restart falls due. An alarm, so each time asked
 * for replaces the last; every check works it out from the same saved state, so the latest is right.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Watchdog.check(context, repair = true)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_CHECK = "com.thomaswcode.decrastination.action.WATCHDOG_CHECK"
        private const val SLACK_MS = 15_000L

        fun checkIn(context: Context, delayMs: Long) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val at = System.currentTimeMillis() + delayMs.coerceAtLeast(0L) + SLACK_MS
            // Exact where allowed: a look meant for inside a restart's minute mustn't drift past it.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
            }
        }

        private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WatchdogReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** At boot and after an update: the watchdog at once, and the periodic work in place. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                SyncWorker.schedule(context)
                WatchdogWorker.schedule(context)
                Daily.schedule(context)
                Sessions.restore(context)
                Watchdog.check(context, repair = true)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
