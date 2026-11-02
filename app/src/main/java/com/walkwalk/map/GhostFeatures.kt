package com.walkwalk.map

import com.walkwalk.domain.TrackPoint
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** "#RRGGBB" for a MapLibre color property; the alpha channel is dropped. */
fun colorHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

data class GhostMarker(val id: Long, val colorArgb: Int, val lat: Double, val lon: Double)

data class GhostTrail(val id: Long, val colorArgb: Int, val points: List<TrackPoint>)

/** One point per ghost; `id` drives hit testing, `color` the dot fill. */
fun ghostDotFeatures(ghosts: List<GhostMarker>): FeatureCollection =
    FeatureCollection.fromFeatures(ghosts.map {
        Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).also { f ->
            f.addNumberProperty("id", it.id)
            f.addStringProperty("color", colorHex(it.colorArgb))
        }
    })

/** One line per segment with at least two points, in trail order. */
fun ghostTrailFeatures(trails: List<GhostTrail>): FeatureCollection =
    FeatureCollection.fromFeatures(trails.flatMap { t ->
        t.points.groupBy { it.segment }.values.filter { it.size >= 2 }.map { seg ->
            Feature.fromGeometry(LineString.fromLngLats(seg.map { Point.fromLngLat(it.lon, it.lat) })).also { f ->
                f.addNumberProperty("id", t.id)
                f.addStringProperty("color", colorHex(t.colorArgb))
            }
        }
    })
