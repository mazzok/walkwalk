package com.walkwalk.ui

import com.walkwalk.domain.TrackPoint

/**
 * Elapsed time shown live: the recorded elapsed time plus the time since the last fix, so the clock keeps
 * ticking between fixes (capped at 60 s). Only a fix of the [segment] being recorded counts: right after a
 * resume or restore the last fix is from before the pause, and that gap is not walking time.
 */
fun liveElapsedMs(elapsedMs: Long, recording: Boolean, last: TrackPoint?, segment: Int, now: Long): Long =
    elapsedMs + if (recording && last != null && last.segment == segment) (now - last.time).coerceIn(0, 60_000) else 0
