package com.walkwalk.recording

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.location.altitude.AltitudeConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Location fixes every ~2 s, with sea-level altitude added where possible. Uses the fused provider
 * (GPS plus Wi-Fi/cell) so the first fix comes quickly, also indoors; GPS alone needs open sky.
 */
class GpsLocationSource(private val context: Context) : LocationSource {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val converter = AltitudeConverter()

    @SuppressLint("MissingPermission") // permission is checked before recording starts
    override fun fixes(): Flow<Fix> = callbackFlow {
        val listener = LocationListener { trySend(it) }
        val request = LocationRequest.Builder(MIN_TIME_MS).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
        manager.requestLocationUpdates(providerOf(manager), request, context.mainExecutor, listener)
        awaitClose { manager.removeUpdates(listener) }
    }.buffer(64).map { it.toFix() }.flowOn(Dispatchers.IO)

    private fun Location.toFix(): Fix {
        if (hasAltitude() && !hasMslAltitude()) {
            try { converter.addMslAltitudeToLocation(context, this) } catch (e: IOException) { /* leave MSL empty */ }
        }
        return Fix(
            time = time,
            lat = latitude,
            lon = longitude,
            mslAltitude = if (hasMslAltitude()) mslAltitudeMeters else null,
            accuracy = if (hasAccuracy()) accuracy else null,
        )
    }

    companion object {
        const val MIN_TIME_MS = 2_000L

        /** The fused provider where the phone has one, otherwise the GPS chip. */
        fun providerOf(manager: LocationManager): String =
            if (manager.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER
            else LocationManager.GPS_PROVIDER
    }
}

/** Keeps the latest barometer reading while recording; returns null on phones without a pressure sensor. */
class BaroPressureSource(context: Context) : PressureSource, SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)
    @Volatile private var latest: Double? = null

    override fun latestHpa(): Double? = latest

    override fun start() {
        sensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun stop() {
        sensors.unregisterListener(this)
        latest = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        latest = event.values[0].toDouble()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
