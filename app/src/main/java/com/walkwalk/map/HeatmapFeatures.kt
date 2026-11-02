package com.walkwalk.map

import com.walkwalk.domain.SpeedHeatmap
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Resting stretches stay visible but take no part in the speed colours. */
const val HEATMAP_REST_HEX = "#9E9E9E"

/** One two-point line per edge, `color` set from the speed against the heatmap's range. */
fun heatmapFeatures(heat: SpeedHeatmap): FeatureCollection =
    FeatureCollection.fromFeatures(heat.edges.map { e ->
        val line = LineString.fromLngLats(listOf(Point.fromLngLat(e.from.lon, e.from.lat), Point.fromLngLat(e.to.lon, e.to.lat)))
        Feature.fromGeometry(line).also { f ->
            f.addStringProperty("color", e.speedKmh?.let { colorHex(SpeedHeatmap.colorFor(it, heat.minKmh, heat.maxKmh)) } ?: HEATMAP_REST_HEX)
        }
    })
