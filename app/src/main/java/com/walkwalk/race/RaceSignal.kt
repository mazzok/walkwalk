package com.walkwalk.race

import kotlin.math.abs

const val FASTEST_BEEP_MS = 250L
const val SLOWEST_BEEP_MS = 4_000L
const val SLOWEST_AT_GAP_MS = 120_000L
const val MAX_BEND_SEMITONES = 3.0
/** Semitones of bend per second of gap gained or lost per second. */
const val BEND_PER_SLOPE = 15.0
const val DUCK_BELOW_MS = 30_000L
const val UNDUCK_ABOVE_MS = 40_000L

/** Single beep: the rival is ahead. Double beep: the rival is behind. */
enum class BeepPattern { SINGLE, DOUBLE }

/** [gapMs] as from aheadBehindMs: positive = user behind, so the rival is ahead. */
fun beepPattern(gapMs: Long): BeepPattern = if (gapMs > 0) BeepPattern.SINGLE else BeepPattern.DOUBLE

/** Time between beeps: fastest at a gap of 0, slowest from [SLOWEST_AT_GAP_MS] on, linear in between. */
fun beepIntervalMs(gapMs: Long): Long {
    val f = abs(gapMs).coerceAtMost(SLOWEST_AT_GAP_MS).toDouble() / SLOWEST_AT_GAP_MS
    return FASTEST_BEEP_MS + ((SLOWEST_BEEP_MS - FASTEST_BEEP_MS) * f).toLong()
}

/**
 * Doppler-style pitch bend from how fast |gap| changes over the last [windowMs]: raised while it shrinks,
 * lowered while it grows. The trend is a least-squares slope of the signed gap, so GPS jitter averages out
 * and the pitch drops the moment the gap changes sign at a pass. Uses timestamps, and keeps the newest
 * sample from before the window so a sparse fix still has something to compare with.
 */
class DopplerTracker(private val windowMs: Long = 10_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun update(timeMs: Long, gapMs: Long?): Double {
        if (gapMs == null) {
            samples.clear()
            return 0.0
        }
        samples.addLast(timeMs to gapMs)
        val cutoff = timeMs - windowMs
        while (samples.size > 1 && samples[1].first <= cutoff) samples.removeFirst()
        if (timeMs - samples.first().first < 2_000) return 0.0
        val tMean = samples.sumOf { it.first.toDouble() } / samples.size
        val gMean = samples.sumOf { it.second.toDouble() } / samples.size
        val sxy = samples.sumOf { (it.first - tMean) * (it.second - gMean) }
        val sxx = samples.sumOf { (it.first - tMean) * (it.first - tMean) }
        // d|gap|/dt: the signed slope, seen from the side the rival is on now
        val side = if (gapMs > 0) 1.0 else -1.0
        val slope = side * sxy / sxx
        return (-BEND_PER_SLOPE * slope).coerceIn(-MAX_BEND_SEMITONES, MAX_BEND_SEMITONES)
    }
}

/** Whether music should be ducked: on below [DUCK_BELOW_MS], off again only above [UNDUCK_ABOVE_MS]. */
class DuckGate {
    private var ducked = false

    fun update(gapMs: Long?): Boolean {
        ducked = when {
            gapMs == null -> false
            abs(gapMs) < DUCK_BELOW_MS -> true
            abs(gapMs) > UNDUCK_ABOVE_MS -> false
            else -> ducked
        }
        return ducked
    }
}
