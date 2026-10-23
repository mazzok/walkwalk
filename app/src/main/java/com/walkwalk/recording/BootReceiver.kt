package com.walkwalk.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.walkwalk.WalkwalkApp
import kotlinx.coroutines.launch

object AutoResume {
    /** Longer than this since the last point (e.g. battery died overnight): ask the user instead of resuming. */
    const val MAX_GAP_MS = 60 * 60_000L

    fun shouldResume(lastPointTime: Long?, now: Long, startTime: Long = 0): Boolean =
        now - (lastPointTime ?: startTime) <= MAX_GAP_MS
}

/**
 * After a reboot or app update, brings recording back if a hike was active recently. A stale hike is left
 * for the app's resume-or-save offer. Does nothing when no hike is active.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as WalkwalkApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                val hike = app.repository.activeHike() ?: return@launch
                val last = app.repository.points(hike.id).lastOrNull()?.time
                if (AutoResume.shouldResume(last, System.currentTimeMillis(), hike.startTime)) RecordingService.send(context, null)
            } finally {
                pending.finish()
            }
        }
    }
}
