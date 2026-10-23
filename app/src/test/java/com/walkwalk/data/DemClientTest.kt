package com.walkwalk.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.walkwalk.domain.ParsedGpx
import com.walkwalk.domain.p
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class DemClientTest {
    private lateinit var db: WalkwalkDb
    private lateinit var repo: HikeRepository
    private val server = MockWebServer()

    private val plus40 = object : ElevationApi {
        var calls = 0
        override suspend fun elevations(coords: List<Pair<Double, Double>>): List<Double> {
            calls++
            return coords.map { 1040.0 }
        }
    }
    private val failingApi = object : ElevationApi {
        override suspend fun elevations(coords: List<Pair<Double, Double>>): List<Double> = throw IOException("offline")
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalkwalkDb::class.java)
            .allowMainThreadQueries().build()
        repo = HikeRepository(db)
        server.start()
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun json(values: List<Double>) =
        MockResponse().setBody("""{"elevation":[${values.joinToString(",")}]}""")

    private suspend fun recordedHikeAt1000(): Long {
        val id = repo.startHike(0)
        (0 until 10).forEach { repo.appendPoint(id, p(it * 2000L, 47.0 + it * 1e-5, gpsAlt = 1000.0)) }
        repo.finish(id, 20_000)
        return id
    }

    @Test
    fun requestsAreBatchedBy100() = runTest {
        server.enqueue(json(List(100) { 500.0 }))
        server.enqueue(json(List(50) { 500.0 }))
        val api = OpenMeteoElevationApi(OkHttpClient(), server.url("/").toString())
        assertEquals(150, api.elevations(List(150) { 47.0 to 11.0 }).size)
        assertEquals(2, server.requestCount)
        val path = server.takeRequest().path!!
        assertTrue(path, path.startsWith("/v1/elevation?latitude=47.0,"))
    }

    @Test
    fun httpErrorThrowsIOException() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val api = OpenMeteoElevationApi(OkHttpClient(), server.url("/").toString())
        try {
            api.elevations(listOf(47.0 to 11.0))
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun offsetAppliedFromMedian() = runTest {
        val id = recordedHikeAt1000()
        assertTrue(AltitudeCorrector(plus40, repo).correct(id))
        assertEquals(1040.0, repo.points(id).first().altitude!!, 0.5)
        assertTrue(repo.hike(id)!!.demCorrected)
        assertEquals(1040.0, repo.hike(id)!!.maxAltM!!, 0.5)
    }

    @Test
    fun correctionSkippedWhenApiFails() = runTest {
        val id = recordedHikeAt1000()
        assertFalse(AltitudeCorrector(failingApi, repo).correct(id))
        assertFalse(repo.hike(id)!!.demCorrected)
        assertEquals(1000.0, repo.points(id).first().altitude!!, 0.01)
    }

    @Test
    fun fillsMissingElevationFromDem() = runTest {
        val id = repo.importGpx(ParsedGpx(null, listOf(p(0), p(1000, 47.001)), true), "x.gpx")
        assertTrue(AltitudeCorrector(plus40, repo).correct(id))
        assertNotNull(repo.points(id).first().altitude)
        assertEquals(1040.0, repo.points(id).last().altitude!!, 0.01)
    }
}
