package com.walkwalk.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.walkwalk.MainActivity
import com.walkwalk.WalkwalkApp
import com.walkwalk.data.HikeStatus
import com.walkwalk.race.RaceAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service that keeps GPS recording alive while a hike is active. Sticky: if Android kills it,
 * it comes back and continues the unfinished hike. It only ever runs between Start and Stop.
 */
class RecordingService : LifecycleService() {
    private val app get() = application as WalkwalkApp
    private var lastNotified = 0L
    private var observing = false
    private val raceAudio by lazy { RaceAudio(this, lifecycleScope) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            startForeground(NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: SecurityException) {
            return interrupted() // location permission missing for a background restart
        } catch (e: IllegalStateException) {
            return interrupted() // ForegroundServiceStartNotAllowedException: background start not allowed
        }
        val controller = app.recording
        lifecycleScope.launch {
            when (intent?.action) {
                ACTION_START -> controller.start().let { id ->
                    // continuing an unfinished race keeps racing; any other hike is a normal one
                    if (app.settings.raceHikeId.value != id) app.settings.setRaceHikeId(null)
                }
                ACTION_START_RACE -> app.settings.setRaceHikeId(controller.start())
                ACTION_PAUSE -> controller.pause()
                ACTION_RESUME -> controller.resume()
                else -> controller.restoreActive()
            }
            // a late Pause/Resume after Stop, or nothing to restore: never keep running without a hike
            if (controller.state.value == null && !controller.restoreActive()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        if (!observing) {
            observing = true
            lifecycleScope.launch {
                // ignore the initial null until start/restore has published; afterwards null means Stop
                controller.state.dropWhile { it == null }.collectLatest { s ->
                    if (s == null) {
                        // Stop, or detach() from onDestroy: either way silence now. The race flag is cleared
                        // only by an explicit Stop (WalkwalkApp.stopRecording), so a restart keeps racing.
                        raceAudio.stop()
                        app.race.reset()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        if (s.status == HikeStatus.PAUSED || System.currentTimeMillis() - lastNotified >= NOTIFY_EVERY_MS) {
                            lastNotified = System.currentTimeMillis()
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
                        }
                        race(s)
                    }
                }
            }
        }
        return START_STICKY
    }

    /**
     * Feeds a race hike's progress to the race and keeps its sound going. Driven from here, not the screen,
     * so it carries on with the screen off; a failure is logged and never reaches the recording.
     */
    private suspend fun race(s: LiveState) {
        if (s.hikeId != app.settings.raceHikeId.value) return
        try {
            app.race.update(s.hikeId, s.track, s.elapsedMs, s.distanceM, s.status == HikeStatus.PAUSED)
            raceAudio.start(app.race.state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("walkwalk", "race update failed", e)
        }
    }

    /**
     * Recording could not be continued in the background. The hike stays unfinished; a notification
     * leads back into the app, which offers to resume or save it.
     */
    private fun interrupted(): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            INTERRUPTED_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Hike interrupted")
                .setContentText("Tap to continue or save your hike")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // the race flag stays, so a restarted service carries on racing
        raceAudio.stop()
        // Stopping the GPS is fine; the hike itself stays active until the user presses Stop.
        if (app.recording.state.value != null) app.recording.detach()
        super.onDestroy()
    }

    private fun notification(s: LiveState?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val paused = s?.status == HikeStatus.PAUSED
        val text = s?.let {
            String.format(Locale.ROOT, "%s · %.2f km", formatElapsed(it.elapsedMs), it.distanceM / 1000)
        } ?: "Starting GPS…"
        val toggle = if (paused) ACTION_RESUME else ACTION_PAUSE
        val toggleIntent = PendingIntent.getService(this, 1, intent(this, toggle), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (paused) "Hike paused" else "Recording hike")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause", toggleIntent).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.walkwalk.START"
        const val ACTION_START_RACE = "com.walkwalk.START_RACE"
        const val ACTION_PAUSE = "com.walkwalk.PAUSE"
        const val ACTION_RESUME = "com.walkwalk.RESUME"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1
        private const val INTERRUPTED_ID = 2
        private const val NOTIFY_EVERY_MS = 5_000L

        fun intent(context: Context, action: String?) = Intent(context, RecordingService::class.java).setAction(action)

        fun send(context: Context, action: String?) = context.startForegroundService(intent(context, action))

        fun formatElapsed(ms: Long): String {
            val min = ms / 60_000
            return String.format(Locale.ROOT, "%d:%02d", min / 60, min % 60)
        }
    }
}
