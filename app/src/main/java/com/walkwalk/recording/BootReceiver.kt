package com.walkwalk.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.walkwalk.WalkwalkApp
import kotlinx.coroutines.launch

/** After a reboot or app update, brings recording back if a hike was still active. Does nothing otherwise. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as WalkwalkApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                if (app.repository.activeHike() != null) RecordingService.send(context, null)
            } finally {
                pending.finish()
            }
        }
    }
}
