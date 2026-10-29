package com.walkwalk

import android.app.Application
import com.walkwalk.data.AltitudeCorrector
import com.walkwalk.data.HikeRepository
import com.walkwalk.data.OpenMeteoElevationApi
import com.walkwalk.data.WalkwalkDb
import com.walkwalk.map.MapSetup
import com.walkwalk.photos.PhotoStore
import com.walkwalk.recording.BaroPressureSource
import com.walkwalk.recording.DemoMode
import com.walkwalk.recording.GpsLocationSource
import com.walkwalk.recording.RecordingController
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class WalkwalkApp : Application() {
    // a failing background job (e.g. DEM correction) must never take the recording process down with it
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("walkwalk", "background job failed", e) },
    )

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
            .build()
    }

    val repository: HikeRepository by lazy { HikeRepository(WalkwalkDb.create(this)) }

    val corrector: AltitudeCorrector by lazy { AltitudeCorrector(OpenMeteoElevationApi(httpClient), repository) }

    val photos: PhotoStore by lazy { PhotoStore(this) }

    val demo: DemoMode by lazy { DemoMode(this) }

    val recording: RecordingController by lazy {
        RecordingController(
            repository, demo.location(GpsLocationSource(this)), demo.pressure(BaroPressureSource(this)), appScope,
        ) { demo.now() }
    }

    override fun onCreate() {
        super.onCreate()
        MapSetup.init(this, httpClient)
    }

    /** Ends the hike (the user pressed Stop) and corrects its altitude in the background when online. */
    suspend fun stopRecording(): Long? {
        // runs in the app scope so a screen going away mid-stop can't leave a half-finished hike
        val id = appScope.async { recording.stop().also { demo.disable() } }.await() ?: return null
        appScope.launch { corrector.correct(id) }
        return id
    }

    /** Saves an unfinished hike whose recording could not be continued (the resume-or-save offer). */
    suspend fun saveUnfinished(hikeId: Long): Long {
        appScope.async {
            val end = repository.points(hikeId).lastOrNull()?.time ?: System.currentTimeMillis()
            repository.finish(hikeId, end)
            demo.disable()
        }.await()
        appScope.launch { corrector.correct(hikeId) }
        return hikeId
    }

    companion object {
        const val USER_AGENT = "walkwalk/0.1 (personal hiking app)"
    }
}
