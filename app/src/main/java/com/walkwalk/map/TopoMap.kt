package com.walkwalk.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.walkwalk.domain.TrackPoint
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

const val AMBIENT_CACHE_BYTES = 500L * 1024 * 1024

object TopoStyle {
    const val OTM_ATTRIBUTION =
        "Map data © OpenStreetMap contributors, SRTM | Map style © OpenTopoMap (CC-BY-SA)"
    const val WMT_ATTRIBUTION = "© waymarkedtrails.org, OpenStreetMap contributors"
    const val BACKGROUND = "#E9EFE4"

    fun json(): String {
        val otm = JSONObject()
            .put("type", "raster")
            .put("tiles", JSONArray().put("https://tile.opentopomap.org/{z}/{x}/{y}.png"))
            .put("tileSize", 256)
            .put("maxzoom", 17)
            .put("attribution", OTM_ATTRIBUTION)
        val wmt = JSONObject()
            .put("type", "raster")
            .put("tiles", JSONArray().put("https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png"))
            .put("tileSize", 256)
            .put("maxzoom", 18)
            .put("attribution", WMT_ATTRIBUTION)
        val layers = JSONArray()
            .put(JSONObject().put("id", "background").put("type", "background").put("paint", JSONObject().put("background-color", BACKGROUND)))
            .put(JSONObject().put("id", "otm").put("type", "raster").put("source", "otm"))
            .put(JSONObject().put("id", "wmt").put("type", "raster").put("source", "wmt"))
        return JSONObject()
            .put("version", 8)
            .put("sources", JSONObject().put("otm", otm).put("wmt", wmt))
            .put("layers", layers)
            .toString()
    }
}

object MapSetup {
    /** Call once from Application.onCreate: tile requests carry our User-Agent and viewed tiles are cached. */
    fun init(context: Context, client: OkHttpClient) {
        MapLibre.getInstance(context)
        HttpRequestUtil.setOkHttpClient(client)
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(
            AMBIENT_CACHE_BYTES,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = Unit
                override fun onError(message: String) = Unit
            },
        )
    }
}

private const val TRACK_SRC = "track"
private const val MARKER_SRC = "marker"
private const val STOPS_SRC = "stops"

/**
 * Topographic map with the hiking-trail overlay, a track line (one line per segment) and a position marker.
 * Track and marker are updated through GeoJSON sources so moving the marker stays cheap.
 */
@Composable
fun TopoMap(
    modifier: Modifier,
    track: List<TrackPoint>,
    marker: Pair<Double, Double>?,
    follow: Boolean,
    fitTrackOnFirstLayout: Boolean,
    stopMarkers: List<Pair<Double, Double>> = emptyList(),
    onUserPan: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var fitted by remember { mutableStateOf(false) }
    val panCallback by rememberUpdatedState(onUserPan)

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // leaving the screen while the activity is still visible: MapLibre needs pause/stop before destroy
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            map = m
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) panCallback()
            }
            m.setStyle(Style.Builder().fromJson(TopoStyle.json())) { s ->
                s.addSource(GeoJsonSource(TRACK_SRC))
                s.addSource(GeoJsonSource(STOPS_SRC))
                s.addSource(GeoJsonSource(MARKER_SRC))
                s.addLayer(LineLayer("track-line", TRACK_SRC).withProperties(
                    PropertyFactory.lineColor("#CC3333"), PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"),
                ))
                s.addLayer(CircleLayer("stops-dot", STOPS_SRC).withProperties(
                    PropertyFactory.circleColor("#E0A030"), PropertyFactory.circleRadius(5f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1.5f),
                ))
                s.addLayer(CircleLayer("marker-dot", MARKER_SRC).withProperties(
                    PropertyFactory.circleColor("#0066CC"), PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f),
                ))
                style = s
            }
        }
    }

    LaunchedEffect(style, track) {
        val s = style ?: return@LaunchedEffect
        val lines = track.groupBy { it.segment }.values.filter { it.size >= 2 }.map { seg ->
            Feature.fromGeometry(LineString.fromLngLats(seg.map { Point.fromLngLat(it.lon, it.lat) }))
        }
        s.getSourceAs<GeoJsonSource>(TRACK_SRC)?.setGeoJson(FeatureCollection.fromFeatures(lines))
        val m = map ?: return@LaunchedEffect
        if (fitTrackOnFirstLayout && !fitted && track.size >= 2) {
            val bounds = LatLngBounds.Builder().includes(track.map { LatLng(it.lat, it.lon) }).build()
            mapView.post { m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80)) }
            fitted = true
        }
    }

    LaunchedEffect(style, stopMarkers) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(STOPS_SRC)?.setGeoJson(
            FeatureCollection.fromFeatures(stopMarkers.map { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)) }),
        )
    }

    LaunchedEffect(style, marker, follow) {
        val s = style ?: return@LaunchedEffect
        val src = s.getSourceAs<GeoJsonSource>(MARKER_SRC) ?: return@LaunchedEffect
        if (marker == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return@LaunchedEffect
        }
        src.setGeoJson(Point.fromLngLat(marker.second, marker.first))
        val m = map ?: return@LaunchedEffect
        if (follow) {
            val zoom = if (m.cameraPosition.zoom < 12) 15.0 else m.cameraPosition.zoom
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(marker.first, marker.second), zoom), 500)
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
