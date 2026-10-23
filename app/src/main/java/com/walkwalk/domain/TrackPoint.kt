package com.walkwalk.domain

data class TrackPoint(
    val time: Long,
    val lat: Double,
    val lon: Double,
    /** Metres above sea level (MSL) when available. */
    val gpsAltitude: Double?,
    val pressureHpa: Double?,
    /** Horizontal accuracy in metres. */
    val accuracy: Float?,
    val segment: Int,
    /** Fused / corrected altitude, filled by [Altitude.fuse] or the DEM corrector. */
    val altitude: Double? = null,
)
