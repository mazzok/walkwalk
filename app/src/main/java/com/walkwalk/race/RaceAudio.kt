package com.walkwalk.race

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Turns the race into sound: beep rate = closeness, single/double = rival ahead/behind, pitch bend = gap
 * closing/opening. Music is ducked while the rival is close. All sounds are synthesised here.
 */
class RaceAudio(context: Context, private val scope: CoroutineScope) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
    private var job: Job? = null
    private var ducking = false

    fun start(state: StateFlow<RaceState>) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            try {
                loop(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // sound is a bonus: the recording carries on without it
                Log.w("walkwalk", "race audio failed", e)
            } finally {
                setDucking(false)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun CoroutineScope.loop(state: StateFlow<RaceState>) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(RATE) // half a second of 16-bit mono
            .build()
        track.play()
        try {
            val doppler = DopplerTracker()
            val duck = DuckGate()
            var seq = 0
            var sampledAt = -1L
            var bend = 0.0
            while (isActive) {
                val s = state.value
                if (s.eventSeq != seq) {
                    seq = s.eventSeq
                    when (s.event) {
                        RaceEvent.NoRival -> track.write(NO_RIVAL, 0, NO_RIVAL.size)
                        is RaceEvent.Switched -> track.write(SWITCH, 0, SWITCH.size)
                        null -> {}
                    }
                }
                val gap = if (s.paused) null else s.gapMs
                setDucking(duck.update(gap))
                if (gap == null) {
                    doppler.update(s.timeMs, null)
                    sampledAt = -1
                    delay(IDLE_MS)
                    continue
                }
                // the gap only changes with a new fix; sample the trend once per fix
                if (s.timeMs != sampledAt) {
                    bend = doppler.update(s.timeMs, gap)
                    sampledAt = s.timeMs
                }
                val sound = beep(beepPattern(gap), bend)
                track.write(sound, 0, sound.size)
                delay(beepIntervalMs(gap))
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    private fun setDucking(on: Boolean) {
        if (on == ducking) return
        ducking = on
        if (on) audio.requestAudioFocus(focus) else audio.abandonAudioFocusRequest(focus)
    }

    private companion object {
        const val RATE = 44_100
        const val BASE_HZ = 1_000.0
        const val BEEP_MS = 60
        const val DOUBLE_GAP_MS = 70
        const val IDLE_MS = 250L

        val SWITCH = tone(660.0, 80) + tone(880.0, 80) + tone(1_100.0, 120)
        val NO_RIVAL = tone(400.0, 300)

        fun beep(pattern: BeepPattern, semitones: Double): ShortArray {
            val one = tone(BASE_HZ * 2.0.pow(semitones / 12), BEEP_MS)
            return if (pattern == BeepPattern.SINGLE) one else one + ShortArray(RATE * DOUBLE_GAP_MS / 1000) + one
        }

        /** A sine with 5 ms fade in and out, so beeps don't click. */
        fun tone(hz: Double, ms: Int): ShortArray {
            val n = RATE * ms / 1000
            val fade = RATE * 5 / 1000
            return ShortArray(n) { i ->
                val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                (sin(2 * PI * hz * i / RATE) * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
            }
        }
    }
}
