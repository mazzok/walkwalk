package com.walkwalk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.walkwalk.map.GhostMarker
import com.walkwalk.map.TopoMap
import com.walkwalk.ui.landmarks.LandmarkLayer
import com.walkwalk.ui.landmarks.LandmarksViewModel

/** Replay layout B: full-screen map, floating numbers at the top, profile and scrubber at the bottom. */
@Composable
fun ReplayScreen(vm: ReplayViewModel, landmarks: LandmarksViewModel, onBack: () -> Unit, onCorrectAltitude: () -> Unit, trackColor: Int) {
    val hike by vm.hike.collectAsState()
    val points by vm.points.collectAsState()
    val frame by vm.frame.collectAsState()
    val replayMs by vm.replayMs.collectAsState()
    val playing by vm.playing.collectAsState()
    val speed by vm.speedFactor.collectAsState()
    val canPlay by vm.canPlay.collectAsState()
    val profile by vm.profile.collectAsState()
    val stopPositions by vm.stopPositions.collectAsState()
    val heatmap by vm.heatmap.collectAsState()
    val showHeatmap by vm.showHeatmap.collectAsState()
    val arrows by vm.arrows.collectAsState()
    val showArrows by vm.showArrows.collectAsState()
    val ghostViews by vm.ghostViews.collectAsState()
    val shownTrails by vm.shownTrails.collectAsState()
    val ghostHeatmaps by vm.ghostHeatmaps.collectAsState()
    val heatRange by vm.heatRange.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var recenterTick by remember { mutableStateOf(0) }

    LandmarkLayer(landmarks, hike, canCamera = false) { args -> Box(Modifier.fillMaxSize()) {
        TopoMap(
            modifier = Modifier.fillMaxSize(),
            track = points,
            marker = frame?.let { it.lat to it.lon },
            follow = false,
            fitTrackOnFirstLayout = true,
            stopMarkers = stopPositions,
            landmarks = args.landmarks,
            selectedLandmarkId = args.selectedLandmarkId,
            onLandmarkClick = args.onLandmarkClick,
            onMapClick = args.onMapClick,
            onLongPress = args.onLongPress,
            selectedOverlay = args.selectedOverlay,
            trackColor = trackColor,
            heatmap = if (showHeatmap) heatmap else null,
            ghosts = ghostViews.map { GhostMarker(it.id, it.colorArgb, it.lat, it.lon) },
            ghostTrails = shownTrails,
            onGhostClick = vm::toggleTrail,
            ghostHeatmaps = ghostHeatmaps,
            heatRange = heatRange,
            recenterTick = recenterTick,
            arrows = if (showArrows) arrows else emptyList(),
        )

        if (frame != null) {
            SmallFloatingActionButton(
                onClick = { recenterTick++ },
                modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Re-centre")
            }
        }

        val h = hike
        val f = frame
        if (h != null && f != null) Column(Modifier.safeDrawingPadding().padding(12.dp).fillMaxWidth()) {
            Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to hikes")
                        }
                        Text(h.name, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.titleSmall)
                    }
                    if (canPlay) {
                        Text(
                            "${Format.clock(f.clockTime)} · ${Format.duration(f.elapsedMs)} elapsed" + if (f.inStop) " · resting" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    StatsRow(
                        "m alt" to Format.meters(f.altitude),
                        "km/h" to Format.speed(f.speedKmh),
                        "km" to Format.km2(f.distanceM),
                        "m ↑" to Format.meters(f.ascentM),
                    )
                    heatRange?.takeIf { showHeatmap }?.let { SpeedLegend(it.start, it.endInclusive) }
                    if (expanded) {
                        Text(
                            "Total ${Format.km(h.distanceM)} · ${Format.duration(h.totalMs)} total · ${Format.duration(h.movingMs)} moving\n" +
                                "↑ ${Format.meters(h.ascentM)} m · ↓ ${Format.meters(h.descentM)} m · " +
                                "${Format.meters(h.minAltM)}–${Format.meters(h.maxAltM)} m\n" +
                                "Ø ${Format.speed(h.avgSpeedKmh)} km/h · max ${Format.speed(h.maxSpeedKmh)} km/h",
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Show speed heatmap", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Switch(checked = showHeatmap, onCheckedChange = { vm.toggleHeatmap() })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Show acceleration arrows", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Switch(checked = showArrows, onCheckedChange = { vm.toggleArrows() })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (h.demCorrected) "Altitude corrected" else "Altitude not yet corrected",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (!h.demCorrected) TextButton(onClick = onCorrectAltitude) { Text("Correct now") }
                        }
                    }
                }
            }
            if (ghostViews.isNotEmpty()) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        ghostViews.forEach { g ->
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.toggleTrail(g.id) }.padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(g.colorArgb)))
                                Text(g.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                g.finishedMs?.let { Text("finished · ${Format.duration(it)}", style = MaterialTheme.typography.bodySmall) }
                                g.deltaMs?.let { Text(Format.delta(it), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }

        Card(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp).fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                profile?.let { p ->
                    ElevationProfile(
                        profile = p,
                        cursorDistanceM = f?.distanceM ?: 0.0,
                        onSeekDistance = vm::seekToDistance,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    )
                }
                if (canPlay) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = vm::togglePlay) {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
                        }
                        Slider(
                            value = replayMs.toFloat(),
                            onValueChange = { vm.seek(it.toLong()) },
                            valueRange = 0f..vm.durationMs.coerceAtLeast(1).toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                        AssistChip(onClick = vm::cycleSpeed, label = { Text("×$speed") }, modifier = Modifier.padding(start = 6.dp))
                    }
                } else if (h != null) {
                    Text("Route without timestamps — no replay", Modifier.padding(6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    } }
}

/** Blue-to-red bar with the slowest and fastest moving speed at its ends. */
@Composable
private fun SpeedLegend(minKmh: Double, maxKmh: Double) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Format.speed(minKmh), style = MaterialTheme.typography.labelSmall)
        Box(
            Modifier.padding(horizontal = 6.dp).weight(1f).height(8.dp)
                .background(Brush.horizontalGradient(listOf(Color.Blue, Color.Red))),
        )
        Text("${Format.speed(maxKmh)} km/h", style = MaterialTheme.typography.labelSmall)
    }
}
