package com.thomaswcode.decrastination.protect

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.FocusService
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.ui.AppTheme
import com.thomaswcode.decrastination.ui.Format
import com.thomaswcode.decrastination.ui.SetupItem
import kotlinx.coroutines.launch

/**
 * Protection (docs/scheduler.md §6): what's in place, arming and disarming it, the changes
 * waiting their 24 hours, and the parent code that applies one at once. Built but left unarmed
 * (Q20): arming asks for the device admin and your dad's scan of a QR code into his
 * authenticator app (Q17), which can be skipped; until he scans one there's no override, only
 * the wait.
 */
class ProtectionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        setContent { AppTheme { Screen(graph) } }
    }

    private enum class Step { None, Explain, ParentCode, Confirm }

    @Composable
    private fun Screen(graph: AppGraph) {
        val settings by graph.settings.state.collectAsStateWithLifecycle()
        val runtime by graph.runtime.state.collectAsStateWithLifecycle()
        val secrets by graph.secrets.present.collectAsStateWithLifecycle()
        val connected by FocusService.connected.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var refresh by remember { mutableIntStateOf(0) }
        var step by remember { mutableStateOf(Step.None) }
        var codeFor by remember { mutableStateOf<CodeTarget?>(null) }
        val report = remember(refresh, runtime.protection) { Watchdog.report(this) }
        val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refresh++
            if (step == Step.Explain && Watchdog.isAdminActive(this)) step = Step.ParentCode
        }
        val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
        val hasParentCode = Secret.TotpSecret in secrets
        val now = graph.clock.now()

        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
                Text("Protection", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (runtime.protection.problems.isEmpty()) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (settings.armed) "Armed" else "Not armed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (settings.armed) {
                                "Changes that loosen blocking wait ${settings.loosenDelayHours} hours. The app's own pages in Settings are guarded, " +
                                    "and switching the focus service off puts it straight back on."
                            } else {
                                "Blocking works, but nothing stops it being switched off or changed. Arm it when you're ready."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        runtime.protection.problems.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (settings.armed) {
                                OutlinedButton(onClick = { scope.launch { graph.changeSettings(settings.copy(armed = false)) } }) { Text("Disarm") }
                                if (hasParentCode) {
                                    OutlinedButton(onClick = { codeFor = CodeTarget.Unblock }) { Text("Unblock for an hour") }
                                }
                            } else {
                                Button(onClick = { step = Step.Explain }) { Text("Arm protection") }
                            }
                        }
                    }
                }

                if (runtime.overrideUntil?.let { it > now } == true) {
                    Text("Unblocked by a parent code until ${Format.at(runtime.overrideUntil!!, now, graph.clock.zone())}", modifier = Modifier.padding(16.dp))
                }

                if (runtime.pending.isNotEmpty()) {
                    Text("Waiting", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
                    runtime.pending.forEach { change ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(change.description, style = MaterialTheme.typography.bodyLarge)
                                Text("Applies ${Format.at(change.applyAt, now, graph.clock.zone())}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (hasParentCode) TextButton(onClick = { codeFor = CodeTarget.Change(change.id) }) { Text("Parent code") }
                        }
                    }
                }

                Text("What's in place", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                SetupItem(
                    title = "Focus service",
                    done = report.serviceEnabled && report.accessibilityOn,
                    detail = when {
                        report.serviceEnabled && connected -> "On: blocked apps are covered."
                        report.serviceEnabled -> "On, starting."
                        else -> "Off: nothing is blocked. Settings → Accessibility → Installed apps → Decrastination focus."
                    },
                    action = if (report.serviceEnabled) null else "Open" to { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                )
                SetupItem(
                    title = "Self-repair",
                    done = report.canRepair,
                    detail = if (report.canRepair) {
                        "Granted: once armed, the watchdog switches the service back on and off any shortcut."
                    } else {
                        "Needs one command from a PC: adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
                    },
                )
                SetupItem(
                    title = "Device admin",
                    done = report.adminActive,
                    detail = if (report.adminActive) "Active: the app can't be uninstalled until it's deactivated." else "Not active. Arming asks for it.",
                )
                SetupItem(
                    title = "On no accessibility shortcut",
                    done = report.onShortcuts.isEmpty() && report.unreadableShortcuts.isEmpty(),
                    detail = ProtectionCheck.shortcutsDetail(report),
                )
                val notifications = remember(refresh) { Notify.allowed(this@ProtectionActivity) }
                SetupItem(
                    title = "Notifications",
                    done = notifications,
                    detail = "For focus sessions and protection alerts.",
                    action = if (notifications) null else "Allow" to {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                        }
                    },
                )
                val unrestricted = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
                SetupItem(
                    title = "Battery: unrestricted",
                    done = unrestricted,
                    detail = if (unrestricted) "One UI won't put the app to sleep." else "One UI may stop the app in the background.",
                    action = if (unrestricted) null else "Allow" to { requestUnrestricted() },
                )
                SetupItem(
                    title = "Parent code",
                    done = hasParentCode,
                    detail = if (hasParentCode) "Set: a code from your dad's authenticator applies one waiting change." else "Not set: only the 24-hour wait.",
                )
            }
        }

        when (step) {
            Step.None -> Unit
            Step.Explain -> AlertDialog(
                onDismissRequest = { step = Step.None },
                title = { Text("Arm protection?") },
                text = {
                    Text(
                        "Once armed:\n" +
                            "• Changes that loosen blocking (removing an app, longer quiet hours, disarming) wait ${settings.loosenDelayHours} hours.\n" +
                            "• Settings backs out of this app's pages: its accessibility switch, App info, storage, device admin and uninstall.\n" +
                            "• The device admin stops it being uninstalled.\n" +
                            "• The watchdog switches the focus service back on.\n\n" +
                            "Next: the device admin, then your dad's authenticator app.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (Watchdog.isAdminActive(this)) {
                            step = Step.ParentCode
                        } else {
                            adminLauncher.launch(
                                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Watchdog.admin(this))
                                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "So Decrastination can't be uninstalled while protection is armed."),
                            )
                        }
                    }) { Text("Continue") }
                },
                dismissButton = { TextButton(onClick = { step = Step.None }) { Text("Cancel") } },
            )
            Step.ParentCode -> ParentCodeDialog(
                graph = graph,
                onDone = { step = Step.Confirm },
                onCancel = { step = Step.None },
            )
            Step.Confirm -> AlertDialog(
                onDismissRequest = { step = Step.None },
                title = { Text("Arm now?") },
                text = { Text(if (hasParentCode) "Your dad's code is set." else "No parent code: loosening will always wait the full ${settings.loosenDelayHours} hours.") },
                confirmButton = {
                    TextButton(onClick = {
                        step = Step.None
                        scope.launch {
                            graph.changeSettings(graph.settings.value.copy(armed = true))
                            Watchdog.check(this@ProtectionActivity, repair = true)
                        }
                    }) { Text("Arm") }
                },
                dismissButton = { TextButton(onClick = { step = Step.None }) { Text("Not yet") } },
            )
        }

        codeFor?.let { target ->
            CodeDialog(graph, target, onClose = { codeFor = null })
        }
    }

    private sealed interface CodeTarget {
        data class Change(val id: String) : CodeTarget
        data object Unblock : CodeTarget
    }

    /** Shows a new secret as a QR code for your dad's authenticator; saved once a code from it checks out. */
    @Composable
    private fun ParentCodeDialog(graph: AppGraph, onDone: () -> Unit, onCancel: () -> Unit) {
        val secret = remember { Totp.newSecret() }
        val qr = remember(secret) { qrBitmap(Totp.uri(secret), 720) }
        var code by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Your dad's authenticator") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ask him to scan this in his authenticator app (Google Authenticator, Microsoft Authenticator…). It's shown only now.", style = MaterialTheme.typography.bodyMedium)
                    Image(qr.asImageBitmap(), contentDescription = "QR code for the authenticator", modifier = Modifier.size(220.dp).background(androidx.compose.ui.graphics.Color.White))
                    Text(Totp.readable(secret), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        code,
                        { code = it.filter(Char::isDigit).take(Totp.DIGITS); error = null },
                        label = { Text("The code his app shows") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = code.length == Totp.DIGITS, onClick = {
                    val step = Totp.matchingStep(secret, code, graph.clock.now())
                    if (step == null) {
                        error = "That isn't the code. Check his app has the new entry."
                    } else {
                        scope.launch {
                            graph.secrets.put(Secret.TotpSecret, Totp.base32(secret))
                            // Only the code checked is used up: the next one his app shows works.
                            graph.runtime.update { it.copy(codeLock = CodeLock(lastUsedStep = step)) }
                            onDone()
                        }
                    }
                }) { Text("Check") }
            },
            dismissButton = {
                // No parent code means none: one kept from an earlier arming would still unblock.
                TextButton(onClick = { scope.launch { graph.secrets.put(Secret.TotpSecret, null); onDone() } }) { Text("Skip: no parent code") }
            },
        )
    }

    /** A code from your dad's app, for one waiting change or an hour's unblock. */
    @Composable
    private fun CodeDialog(graph: AppGraph, target: CodeTarget, onClose: () -> Unit) {
        var code by remember { mutableStateOf("") }
        var message by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(if (target == CodeTarget.Unblock) "Unblock for an hour" else "Apply now") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ask your dad for the code his authenticator app shows for Decrastination.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        code,
                        { code = it.filter(Char::isDigit).take(Totp.DIGITS) },
                        label = { Text("Code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = code.length == Totp.DIGITS, onClick = {
                    scope.launch {
                        val result = applyWithCode(graph, target, code)
                        if (result == null) onClose() else message = result
                    }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        )
    }

    /** Checks [code] and, if it's right, does what it was for. Returns what went wrong, or null. */
    private suspend fun applyWithCode(graph: AppGraph, target: CodeTarget, code: String): String? {
        val secret = graph.secrets[Secret.TotpSecret]?.let(Totp::fromBase32) ?: return "No parent code is set"
        val now = graph.clock.now()
        var outcome: CodeLock.Result = CodeLock.Result.Reused
        // The code is used up first (with the unblock, if that's what it was for), then the change
        // it allows is applied.
        graph.runtime.update { state ->
            val (result, lock) = state.codeLock.attempt(secret, code, now)
            outcome = result
            if (result is CodeLock.Result.Accepted && target == CodeTarget.Unblock) {
                state.copy(codeLock = lock, overrideUntil = now + UNBLOCK_MS)
            } else {
                state.copy(codeLock = lock)
            }
        }
        if (outcome is CodeLock.Result.Accepted && target is CodeTarget.Change) graph.applyNow(target.id)
        return when (val result = outcome) {
            is CodeLock.Result.Accepted -> null
            is CodeLock.Result.Wrong -> "Wrong code: ${result.triesLeft} ${if (result.triesLeft == 1) "try" else "tries"} left"
            is CodeLock.Result.Locked -> "Too many wrong codes: try again ${Format.at(result.until, now, graph.clock.zone())}"
            CodeLock.Result.Reused -> "That code has been used: wait for the next one"
        }
    }

    /** A sideloaded app, never on Play, whose blocker One UI mustn't put to sleep. */
    @SuppressLint("BatteryLife")
    private fun requestUnrestricted() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = createBitmap(size, size)
        for (x in 0 until size) for (y in 0 until size) bitmap[x, y] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        return bitmap
    }

    private companion object {
        const val UNBLOCK_MS = 60 * 60_000L
    }
}
