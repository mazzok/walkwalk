package com.walkwalk

import android.app.Application
import com.walkwalk.data.AltitudeCorrector
import com.walkwalk.data.HikeRepository
import com.walkwalk.data.OpenMeteoElevationApi
import com.walkwalk.data.WalkwalkDb
import com.walkwalk.recording.BaroPressureSource
import com.walkwalk.recording.GpsLocationSource
import com.walkwalk.recording.RecordingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class WalkwalkApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
            .build()
    }

    val repository: HikeRepository by lazy { HikeRepository(WalkwalkDb.create(this)) }

    val corrector: AltitudeCorrector by lazy { AltitudeCorrector(OpenMeteoElevationApi(httpClient), repository) }

    val recording: RecordingController by lazy {
        RecordingController(repository, GpsLocationSource(this), BaroPressureSource(this), appScope) { System.currentTimeMillis() }
    }

    /** Ends the hike (the user pressed Stop) and corrects its altitude in the background when online. */
    suspend fun stopRecording(): Long? {
        val id = recording.stop() ?: return null
        appScope.launch { corrector.correct(id) }
        return id
    }

    companion object {
        const val USER_AGENT = "walkwalk/0.1 (personal hiking app)"
    }
}
