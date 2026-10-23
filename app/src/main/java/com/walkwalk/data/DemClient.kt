package com.walkwalk.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

interface ElevationApi {
    /** Terrain heights in metres for (lat, lon) pairs; throws [IOException] on failure. */
    suspend fun elevations(coords: List<Pair<Double, Double>>): List<Double>
}

/** Open-Meteo elevation API (Copernicus DEM), at most 100 coordinates per request. */
class OpenMeteoElevationApi(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.open-meteo.com/",
) : ElevationApi {
    override suspend fun elevations(coords: List<Pair<Double, Double>>): List<Double> = withContext(Dispatchers.IO) {
        coords.chunked(BATCH).flatMap { batch -> fetch(batch) }
    }

    private fun fetch(batch: List<Pair<Double, Double>>): List<Double> {
        val url = baseUrl.trimEnd('/') + "/v1/elevation?latitude=" + batch.joinToString(",") { it.first.toString() } +
            "&longitude=" + batch.joinToString(",") { it.second.toString() }
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("elevation API HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("empty elevation response")
            try {
                val arr = JSONObject(body).getJSONArray("elevation")
                if (arr.length() != batch.size) throw IOException("elevation count ${arr.length()} != ${batch.size}")
                return List(arr.length()) { arr.getDouble(it) }
            } catch (e: JSONException) {
                throw IOException("bad elevation response", e)
            }
        }
    }

    companion object {
        const val BATCH = 100
    }
}

/** Shifts a hike's altitudes so they sit on the terrain model, or fills them from it when missing. */
class AltitudeCorrector(private val api: ElevationApi, private val repo: HikeRepository) {
    suspend fun correct(hikeId: Long): Boolean {
        val points = repo.points(hikeId)
        if (points.isEmpty()) return false
        val corrected = try {
            if (points.all { it.altitude == null }) {
                api.elevations(points.map { it.lat to it.lon })
            } else {
                val withAlt = points.indices.filter { points[it].altitude != null }
                val step = maxOf(1, withAlt.size / SAMPLES)
                val samples = withAlt.filterIndexed { n, _ -> n % step == 0 }.take(SAMPLES)
                val dem = api.elevations(samples.map { points[it].lat to points[it].lon })
                val offset = median(samples.mapIndexed { n, i -> dem[n] - points[i].altitude!! })
                // points without a reading keep the nearest earlier altitude so the profile has no holes
                var last = points.first { it.altitude != null }.altitude!!
                points.map { p -> (p.altitude ?: last).also { last = it } + offset }
            }
        } catch (e: IOException) {
            return false
        }
        repo.setCorrectedAltitudes(hikeId, corrected)
        repo.recompute(hikeId)
        return true
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    companion object {
        const val SAMPLES = 100
    }
}
