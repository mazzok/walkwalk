package com.walkwalk.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoResumeTest {
    @Test
    fun recentHikeResumesAfterReboot() = assertTrue(AutoResume.shouldResume(lastPointTime = 1_000_000, now = 1_000_000 + 10 * 60_000))

    @Test
    fun staleHikeWaitsForTheUser() = assertFalse(AutoResume.shouldResume(lastPointTime = 1_000_000, now = 1_000_000 + 3 * 60 * 60_000))

    @Test
    fun hikeWithoutPointsUsesStartTime() = assertTrue(AutoResume.shouldResume(lastPointTime = null, now = 5_000, startTime = 0))
}
