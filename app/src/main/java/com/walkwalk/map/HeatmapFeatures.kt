package com.walkwalk.map

import com.walkwalk.domain.AccelArrow
import com.walkwalk.domain.SpeedHeatmap
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Resting stretches stay visible but take no part in the speed colours. */
const val HEATMAP_REST_HEX = "#9E9E9E"

/** One two-point line per edge, `color` set from the speed against [range] (the heatmap's own by default). */
fun heatmapFeatures(heat: SpeedHeatmap, range: ClosedFloatingPointRange<Double> = heat.minKmh..heat.maxKmh): FeatureCollection =
    FeatureCollection.fromFeatures(heat.edges.map { e ->
        val line = LineString.fromLngLats(listOf(Point.fromLngLat(e.from.lon, e.from.lat), Point.fromLngLat(e.to.lon, e.to.lat)))
        Feature.fromGeometry(line).also { f ->
            f.addStringProperty("color", e.speedKmh?.let { colorHex(SpeedHeatmap.colorFor(it, range.start, range.endInclusive)) } ?: HEATMAP_REST_HEX)
        }
    })

/** One point per arrowhead; `bearing` turns the icon along the walk and `size` scales it. */
fun arrowFeatures(arrows: List<AccelArrow>): FeatureCollection =
    FeatureCollection.fromFeatures(arrows.map { a ->
        Feature.fromGeometry(Point.fromLngLat(a.lon, a.lat)).also { f ->
            f.addNumberProperty("bearing", a.bearingDeg)
            f.addNumberProperty("size", a.size)
        }
    })

/** The edges of several heatmaps in one collection, all coloured against the same [range]. */
fun ghostHeatFeatures(heats: List<SpeedHeatmap>, range: ClosedFloatingPointRange<Double>): FeatureCollection =
    FeatureCollection.fromFeatures(heats.flatMap { heatmapFeatures(it, range).features().orEmpty() })
