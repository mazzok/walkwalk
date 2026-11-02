package com.walkwalk.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.walkwalk.R
import com.walkwalk.data.Landmark
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
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
import com.walkwalk.domain.SpeedHeatmap
import com.walkwalk.domain.TrackPoint
import com.walkwalk.settings.DEFAULT_TRACK_COLOR
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
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
private const val HEAT_SRC = "heat"
private const val MARKER_SRC = "marker"
private const val STOPS_SRC = "stops"
private const val LANDMARKS_SRC = "landmarks"
private const val GHOST_TRAILS_SRC = "ghost-trails"
private const val GHOST_DOTS_SRC = "ghost-dots"
private const val GHOST_DOT_LAYER = "ghost-dot-layer"
/** Tap tolerance around a ghost dot, in dp each way. */
private const val GHOST_TAP_SLOP_DP = 20f
private const val PIN_LAYER = "landmark-pins"
private const val PIN_IMAGE = "pin"
private const val PIN_SELECTED_IMAGE = "pin-selected"

sealed interface TapTarget {
    data class Pin(val id: Long) : TapTarget
    data class Ghost(val id: Long) : TapTarget
    data object None : TapTarget
}

/** What a map tap acts on: an exact pin hit beats the wider ghost tap area, which beats a plain map tap. */
fun tapTarget(pinId: Long?, ghostId: Long?): TapTarget = when {
    pinId != null -> TapTarget.Pin(pinId)
    ghostId != null -> TapTarget.Ghost(ghostId)
    else -> TapTarget.None
}

/** One point per landmark; `id` and `selected` drive hit testing and the icon choice. */
fun landmarkFeatures(landmarks: List<Landmark>, selectedId: Long?): FeatureCollection =
    FeatureCollection.fromFeatures(landmarks.map {
        Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).also { f ->
            f.addNumberProperty("id", it.id)
            f.addBooleanProperty("selected", it.id == selectedId)
        }
    })

/** ic_pin has a pure red body and white details; the matrix swaps red for [color] and leaves white alone. */
private fun pinBitmap(context: Context, color: Int): Bitmap {
    val src = ContextCompat.getDrawable(context, R.drawable.ic_pin)!!.toBitmap()
    val tr = ((color shr 16) and 0xFF) / 255f
    val tg = ((color shr 8) and 0xFF) / 255f
    val tb = (color and 0xFF) / 255f
    val matrix = ColorMatrix(floatArrayOf(
        tr, 1 - tr, 0f, 0f, 0f,
        tg, 1 - tg, 0f, 0f, 0f,
        tb, 1 - tb, 0f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
    return out
}

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
    // where to look while there is no marker yet, e.g. the phone's last known position
    initialCenter: Pair<Double, Double>? = null,
    onUserPan: () -> Unit = {},
    landmarks: List<Landmark> = emptyList(),
    selectedLandmarkId: Long? = null,
    onLandmarkClick: (Long) -> Unit = {},
    onMapClick: () -> Unit = {},
    onLongPress: ((lat: Double, lon: Double) -> Unit)? = null,
    // drawn centred just below the selected pin
    selectedOverlay: (@Composable () -> Unit)? = null,
    trackColor: Int = DEFAULT_TRACK_COLOR,
    ghosts: List<GhostMarker> = emptyList(),
    ghostTrails: List<GhostTrail> = emptyList(),
    onGhostClick: (Long) -> Unit = {},
    // when set, replaces the plain track line with speed colours
    heatmap: SpeedHeatmap? = null,
) {
    val ghostClick by rememberUpdatedState(onGhostClick)
    val trackColorNow by rememberUpdatedState(trackColor)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var fitted by remember { mutableStateOf(false) }
    var bearing by remember { mutableFloatStateOf(0f) }
    val panCallback by rememberUpdatedState(onUserPan)
    val landmarkClick by rememberUpdatedState(onLandmarkClick)
    val mapClick by rememberUpdatedState(onMapClick)
    val longPress by rememberUpdatedState(onLongPress)
    val selectedLandmark by rememberUpdatedState(landmarks.firstOrNull { it.id == selectedLandmarkId })
    // screen position (px) of the selected pin's tip, null when there is none or it is off screen
    var overlayPos by remember { mutableStateOf<IntOffset?>(null) }
    val density = LocalDensity.current
    val updateOverlay = remember(mapView) {
        {
            val m = map
            val l = selectedLandmark
            overlayPos = if (m == null || l == null) null else {
                val p = m.projection.toScreenLocation(LatLng(l.lat, l.lon))
                if (p.x in 0f..mapView.width.toFloat() && p.y in 0f..mapView.height.toFloat()) {
                    IntOffset(p.x.toInt(), p.y.toInt())
                } else null
            }
        }
    }
    val updateOverlayNow by rememberUpdatedState(updateOverlay)

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
            // the built-in compass sits under the screens' top cards; ours is drawn below
            m.uiSettings.isCompassEnabled = false
            m.addOnCameraMoveListener {
                bearing = m.cameraPosition.bearing.toFloat()
                updateOverlayNow()
            }
            m.addOnMapClickListener { latLng ->
                val screen = m.projection.toScreenLocation(latLng)
                // an exact hit on a landmark pin wins over the ghost tap slop
                val pinHit = if (m.style?.getLayer(PIN_LAYER) != null) {
                    m.queryRenderedFeatures(screen, PIN_LAYER).firstOrNull()
                } else null
                val pinId = pinHit?.getNumberProperty("id")?.toLong()
                // ghost dots are small; accept taps within about a fingertip of one, nearest dot wins
                val ghostHit = if (pinId == null && m.style?.getLayer(GHOST_DOT_LAYER) != null) {
                    val slop = GHOST_TAP_SLOP_DP * mapView.resources.displayMetrics.density
                    val box = RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop)
                    m.queryRenderedFeatures(box, GHOST_DOT_LAYER).minByOrNull { f ->
                        val p = f.geometry() as? Point ?: return@minByOrNull Float.MAX_VALUE
                        val at = m.projection.toScreenLocation(LatLng(p.latitude(), p.longitude()))
                        (at.x - screen.x) * (at.x - screen.x) + (at.y - screen.y) * (at.y - screen.y)
                    }
                } else null
                val ghostId = ghostHit?.getNumberProperty("id")?.toLong()
                when (val t = tapTarget(pinId, ghostId)) {
                    is TapTarget.Pin -> landmarkClick(t.id)
                    is TapTarget.Ghost -> ghostClick(t.id)
                    TapTarget.None -> mapClick()
                }
                true
            }
            m.addOnMapLongClickListener { latLng ->
                val handler = longPress
                handler?.invoke(latLng.latitude, latLng.longitude)
                handler != null
            }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) panCallback()
            }
            m.setStyle(Style.Builder().fromJson(TopoStyle.json())) { s ->
                s.addSource(GeoJsonSource(TRACK_SRC))
                s.addSource(GeoJsonSource(HEAT_SRC))
                s.addSource(GeoJsonSource(STOPS_SRC))
                s.addSource(GeoJsonSource(MARKER_SRC))
                s.addSource(GeoJsonSource(GHOST_TRAILS_SRC))
                s.addSource(GeoJsonSource(GHOST_DOTS_SRC))
                s.addLayer(LineLayer("ghost-trails", GHOST_TRAILS_SRC).withProperties(
                    PropertyFactory.lineColor(Expression.get("color")), PropertyFactory.lineOpacity(0.5f),
                    PropertyFactory.lineWidth(3f),
                    PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"),
                ))
                s.addLayer(LineLayer("track-line", TRACK_SRC).withProperties(
                    PropertyFactory.lineColor(colorHex(trackColorNow)), PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"),
                ))
                s.addLayer(LineLayer("heat-line", HEAT_SRC).withProperties(
                    PropertyFactory.lineColor(Expression.get("color")), PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"),
                    PropertyFactory.visibility(Property.NONE),
                ))
                s.addLayer(CircleLayer("stops-dot", STOPS_SRC).withProperties(
                    PropertyFactory.circleColor("#E0A030"), PropertyFactory.circleRadius(5f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1.5f),
                ))
                s.addSource(GeoJsonSource(LANDMARKS_SRC))
                s.addImage(PIN_IMAGE, pinBitmap(context, android.graphics.Color.parseColor("#0066CC")))
                s.addImage(PIN_SELECTED_IMAGE, pinBitmap(context, android.graphics.Color.parseColor("#E0A030")))
                s.addLayer(SymbolLayer(PIN_LAYER, LANDMARKS_SRC).withProperties(
                    PropertyFactory.iconImage(Expression.switchCase(
                        Expression.toBool(Expression.get("selected")), Expression.literal(PIN_SELECTED_IMAGE),
                        Expression.literal(PIN_IMAGE),
                    )),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ))
                s.addLayer(CircleLayer(GHOST_DOT_LAYER, GHOST_DOTS_SRC).withProperties(
                    PropertyFactory.circleColor(Expression.get("color")), PropertyFactory.circleOpacity(0.8f),
                    PropertyFactory.circleRadius(5f),
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

    LaunchedEffect(style, trackColor) {
        val s = style ?: return@LaunchedEffect
        s.getLayerAs<LineLayer>("track-line")?.setProperties(PropertyFactory.lineColor(colorHex(trackColor)))
    }

    LaunchedEffect(style, heatmap) {
        val s = style ?: return@LaunchedEffect
        if (heatmap != null) s.getSourceAs<GeoJsonSource>(HEAT_SRC)?.setGeoJson(heatmapFeatures(heatmap))
        s.getLayerAs<LineLayer>("heat-line")?.setProperties(PropertyFactory.visibility(if (heatmap != null) Property.VISIBLE else Property.NONE))
        s.getLayerAs<LineLayer>("track-line")?.setProperties(PropertyFactory.visibility(if (heatmap != null) Property.NONE else Property.VISIBLE))
    }

    LaunchedEffect(style, stopMarkers) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(STOPS_SRC)?.setGeoJson(
            FeatureCollection.fromFeatures(stopMarkers.map { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)) }),
        )
    }

    LaunchedEffect(style, landmarks, selectedLandmarkId) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(LANDMARKS_SRC)?.setGeoJson(landmarkFeatures(landmarks, selectedLandmarkId))
        updateOverlay()
    }

    LaunchedEffect(style, ghosts) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(GHOST_DOTS_SRC)?.setGeoJson(ghostDotFeatures(ghosts))
    }

    LaunchedEffect(style, ghostTrails) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(GHOST_TRAILS_SRC)?.setGeoJson(ghostTrailFeatures(ghostTrails))
    }

    LaunchedEffect(style, initialCenter) {
        val m = map ?: return@LaunchedEffect
        if (style == null || initialCenter == null || marker != null) return@LaunchedEffect
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(initialCenter.first, initialCenter.second), 14.0))
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

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        val pos = overlayPos
        if (selectedOverlay != null && pos != null) {
            Box(Modifier.layout { measurable, c ->
                val pl = measurable.measure(Constraints(maxWidth = c.maxWidth, maxHeight = c.maxHeight))
                layout(pl.width, pl.height) { pl.place(pos.x - pl.width / 2, pos.y + (4 * density.density).toInt()) }
            }) { selectedOverlay() }
        }
        // shown once the map is turned; the arrow points north, a tap turns the map north-up and flat again
        if (bearing.absoluteValue > 0.5f && bearing.absoluteValue < 359.5f) {
            SmallFloatingActionButton(
                onClick = {
                    val m = map ?: return@SmallFloatingActionButton
                    m.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder(m.cameraPosition).bearing(0.0).tilt(0.0).build(),
                    ), 300)
                },
                modifier = Modifier.align(Alignment.CenterEnd).offset(y = (-56).dp).padding(12.dp),
            ) {
                Icon(Icons.Default.Navigation, contentDescription = "Face north", Modifier.rotate(-bearing))
            }
        }
    }
}
