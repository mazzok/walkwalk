package com.walkwalk.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

object Permissions {
    fun hasLocation(c: Context) = granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasBackgroundLocation(c: Context) = granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun ignoresBatteryOptimisation(c: Context) =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    /** True when a pocket recording could be cut short by Android. */
    fun pocketRecordingAtRisk(c: Context) = !hasBackgroundLocation(c) || !ignoresBatteryOptimisation(c)

    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}

private enum class Step { IDLE, LOCATION, NOTIFICATIONS, BACKGROUND_EXPLAIN, BACKGROUND, BATTERY_EXPLAIN }

/**
 * Walks through the permissions needed for a reliable recording, in order, each with a short explanation.
 * Returns a function that starts the flow; [onDone] gets whether recording can start at all (fine location).
 */
@Composable
fun rememberPermissionFlow(onDone: (canRecord: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    var step by remember { mutableStateOf(Step.IDLE) }

    fun next(after: Step) {
        step = when (after) {
            Step.LOCATION -> Step.NOTIFICATIONS
            Step.NOTIFICATIONS -> if (Permissions.hasBackgroundLocation(context)) Step.BATTERY_EXPLAIN else Step.BACKGROUND_EXPLAIN
            Step.BACKGROUND -> Step.BATTERY_EXPLAIN
            else -> Step.IDLE
        }
        if (step == Step.BATTERY_EXPLAIN && Permissions.ignoresBatteryOptimisation(context)) {
            step = Step.IDLE
            onDone(true)
        }
    }

    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next(Step.BACKGROUND) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next(Step.NOTIFICATIONS) }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            step = Step.NOTIFICATIONS
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            step = Step.IDLE
            onDone(false)
        }
    }

    when (step) {
        Step.BACKGROUND_EXPLAIN -> ExplainDialog(
            text = "To keep recording while the phone is in your pocket, choose \"Allow all the time\" on the next screen.",
            onOk = { step = Step.BACKGROUND; background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
            onSkip = { next(Step.BACKGROUND) },
        )
        Step.BATTERY_EXPLAIN -> ExplainDialog(
            text = "Android may stop the recording to save battery. Allow walkwalk to run without battery restrictions.",
            onOk = {
                step = Step.IDLE
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                )
                onDone(true)
            },
            onSkip = { step = Step.IDLE; onDone(true) },
        )
        else -> Unit
    }

    return {
        if (Permissions.hasLocation(context)) {
            step = Step.NOTIFICATIONS
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            step = Step.LOCATION
            location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}

@Composable
private fun ExplainDialog(text: String, onOk: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSkip,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onOk) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Not now") } },
    )
}
