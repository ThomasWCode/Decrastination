package com.thomaswcode.decrastination.protect

import android.Manifest
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.thomaswcode.decrastination.data.ProtectionRecord
import com.thomaswcode.decrastination.data.ProtectionState
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.sync.SyncWorker
import com.thomaswcode.decrastination.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Layer 5 of the anti-tamper (docs/scheduler.md §6): checks that the focus service is on, on no
 * accessibility shortcut, and, once armed, that the device admin is active. Anything wrong shows
 * as a notification and on the widget, and is logged. Once armed, and with WRITE_SECURE_SETTINGS
 * (granted over adb, Q19), it switches the service back on and takes it off the shortcuts
 * itself. v1 sends no email (Q17): the alert stays on the phone.
 *
 * Runs from the focus service every five minutes, as a job every 15, at boot, and the moment the
 * service is switched off.
 */
object Watchdog {
    private const val NOTIFICATION_ID = 3001
    private const val TAG = AppGraph.TAG

    fun service(context: Context): ComponentName = ComponentName(context, FocusService::class.java)
    fun admin(context: Context): ComponentName = ComponentName(context, AdminReceiver::class.java)

    fun canRepair(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isAdminActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(admin(context)) == true

    /**
     * A secure setting, or null if this app may not read it: Android 12+ refuses hidden keys to
     * apps (`accessibility_qs_targets` threw on the phone, 9 Oct, and took the service down).
     */
    private fun readable(resolver: android.content.ContentResolver, key: String): String? =
        runCatching { Settings.Secure.getString(resolver, key) }.getOrNull()

    fun report(context: Context): ProtectionCheck.Report {
        val resolver = context.contentResolver
        val pkg = context.packageName
        val cls = FocusService::class.java.name
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val on = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        val shortcuts = ProtectionCheck.SHORTCUT_KEYS.filter { key ->
            ProtectionCheck.entries(readable(resolver, key)).any { ProtectionCheck.matches(it, pkg, cls) }
        }
        return ProtectionCheck.Report(
            serviceEnabled = ProtectionCheck.isEnabled(enabled, pkg, cls),
            accessibilityOn = on,
            onShortcuts = shortcuts,
            adminActive = isAdminActive(context),
            canRepair = canRepair(context),
        )
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
                val value = readable(resolver, key)
                if (ProtectionCheck.entries(value).any { ProtectionCheck.matches(it, pkg, cls) }) {
                    runCatching { Settings.Secure.putString(resolver, key, ProtectionCheck.withoutService(value, pkg, cls)) }
                        .onFailure { Log.w(TAG, "Couldn't take the service off $key", it) }
                }
            }
            true
        }.onFailure { Log.w(TAG, "Couldn't repair the accessibility settings", it) }.getOrDefault(false)
    }

    /**
     * Checks, repairs if armed (and [repair] and able), records the finding, and says so in a
     * notification while anything is wrong.
     */
    suspend fun check(context: Context, repair: Boolean) {
        val graph = AppGraph.get(context)
        val armed = graph.settings.value.armed
        var report = report(context)
        var problems = ProtectionCheck.problems(report, armed)
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
        graph.runtime.update {
            it.copy(protection = ProtectionState(problems = problems, offSince = if (problems.isEmpty()) null else before.offSince ?: now, checkedAt = now))
        }
        if (problems != before.problems && (problems.isNotEmpty() || repaired)) {
            graph.log.update { it.copy(protection = it.protection + ProtectionRecord(now, problems, repaired)).trimmed(now) }
            Log.i(TAG, "Protection: ${problems.ifEmpty { listOf("all well") }}${if (repaired) " (repaired)" else ""}")
        }
        if (problems.isEmpty()) Notify.cancel(context, NOTIFICATION_ID) else alert(context, problems, armed)
    }

    private fun alert(context: Context, problems: List<String>, armed: Boolean) {
        val open = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETUP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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

/** At boot and after an update: the watchdog at once, and the periodic work in place. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                SyncWorker.schedule(context)
                WatchdogWorker.schedule(context)
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
