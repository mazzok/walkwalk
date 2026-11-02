package com.walkwalk.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.walkwalk.ghost.GhostSession
import com.walkwalk.map.GhostMarker
import com.walkwalk.map.GhostTrail
import kotlinx.coroutines.CancellationException
import android.location.LocationManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.walkwalk.data.HikeEntity
import com.walkwalk.data.HikeRepository
import com.walkwalk.data.HikeStatus
import com.walkwalk.map.TopoMap
import com.walkwalk.ui.landmarks.LandmarkLayer
import com.walkwalk.ui.landmarks.LandmarkMapArgs
import com.walkwalk.ui.landmarks.LandmarksViewModel
import com.walkwalk.recording.RecordingController
import kotlinx.coroutines.delay

/** Most recent position any provider still remembers, used to place the map before the first fix. */
private fun lastKnownPosition(context: Context): Pair<Double, Double>? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    return try {
        manager.allProviders
            .mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
            ?.let { it.latitude to it.longitude }
    } catch (e: SecurityException) {
        null
    }
}

@Composable
fun RecordingScreen(
    controller: RecordingController,
    repo: HikeRepository,
    onBack: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onContinueUnfinished: () -> Unit,
    onSaveUnfinished: (Long) -> Unit,
    landmarks: LandmarksViewModel?,
    ghosts: GhostSession,
    trackColor: Int,
) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    var follow by remember { mutableStateOf(true) }
    var confirmStop by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // re-checked on every resume: the battery dialog and Settings answer after this screen is already open
    var atRisk by remember { mutableStateOf(Permissions.pocketRecordingAtRisk(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { atRisk = Permissions.pocketRecordingAtRisk(context) }
    var riskDismissed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(1_000) }
    }

    val s = state
    if (s == null) {
        // not recording in this process: an unfinished hike means the recording was interrupted
        val unfinished by produceState<HikeEntity?>(null) { value = repo.activeHike() }
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            val hike = unfinished
            if (hike == null) {
                CircularProgressIndicator()
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Unfinished hike", style = MaterialTheme.typography.titleMedium)
                        Text("Recording of \"${hike.name}\" was interrupted. Continue recording, or save it as it is?")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { onSaveUnfinished(hike.id) }, modifier = Modifier.weight(1f)) { Text("Save hike") }
                            Button(onClick = onContinueUnfinished, modifier = Modifier.weight(1f)) { Text("Continue") }
                        }
                    }
                }
            }
        }
        return
    }
    val last = s.track.lastOrNull()
    val lastKnown = remember { lastKnownPosition(context) }
    val recording = s.status == HikeStatus.RECORDING
    // elapsed keeps ticking between fixes while recording
    val elapsed = s.elapsedMs + if (recording && last != null) (now - last.time).coerceIn(0, 60_000) else 0

    // ghosts only read; any failure here is logged and must never reach the recording screen or the recording
    val latestS by rememberUpdatedState(s)
    val latestElapsed by rememberUpdatedState(elapsed)
    LaunchedEffect(now, s.hikeId) {
        val cur = latestS
        try {
            ghosts.update(cur.hikeId, cur.track, latestElapsed, cur.distanceM)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("walkwalk", "ghost update failed", e)
        }
    }
    val ghostViews by ghosts.ghosts.collectAsState()
    var openTrails by remember { mutableStateOf(setOf<Long>()) }
    val toggleTrail = { id: Long -> openTrails = if (id in openTrails) openTrails - id else openTrails + id }

    // the picker needs the hike's start time; a live hike always has times
    val hike by produceState<HikeEntity?>(null, s.hikeId) { value = repo.hike(s.hikeId) }

    @Composable
    fun Screen(args: LandmarkMapArgs?) = Box(Modifier.fillMaxSize()) {
        TopoMap(
            modifier = Modifier.fillMaxSize(),
            track = s.track,
            marker = last?.let { it.lat to it.lon },
            follow = follow,
            fitTrackOnFirstLayout = false,
            initialCenter = if (last == null) lastKnown else null,
            onUserPan = { follow = false },
            landmarks = args?.landmarks.orEmpty(),
            selectedLandmarkId = args?.selectedLandmarkId,
            onLandmarkClick = args?.onLandmarkClick ?: {},
            onMapClick = { openTrails = emptySet(); args?.onMapClick?.invoke() },
            onLongPress = args?.onLongPress,
            selectedOverlay = args?.selectedOverlay,
            trackColor = trackColor,
            ghosts = ghostViews.map { GhostMarker(it.id, it.colorArgb, it.lat, it.lon) },
            ghostTrails = ghostViews.filter { it.id in openTrails }.map { GhostTrail(it.id, it.colorArgb, ghosts.trail(it.id)) },
            onGhostClick = toggleTrail,
        )
        Column(Modifier.safeDrawingPadding().padding(12.dp).fillMaxWidth()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to hikes, keep recording")
                        }
                        Text(
                            if (recording) "Recording · ${Format.duration(elapsed)}" else "Paused · ${Format.duration(elapsed)}",
                            Modifier.padding(start = 6.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    if (last == null && recording) {
                        Row(
                            Modifier.padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text("Waiting for GPS…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    StatsRow(
                        "km" to Format.km2(s.distanceM),
                        "m alt" to Format.meters(s.altitudeM),
                        "km/h" to Format.speed(s.speedKmh),
                        "m ↑" to Format.meters(s.ascentM),
                    )
                }
            }
            if (ghostViews.isNotEmpty()) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        ghostViews.forEach { g ->
                            Row(
                                Modifier.fillMaxWidth().clickable { toggleTrail(g.id) }.padding(vertical = 4.dp),
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
            if (atRisk && !riskDismissed) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Recording may stop when the phone is in your pocket — allow location all the time and disable battery optimisation.",
                            Modifier.weight(1f).padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB00020),
                        )
                        IconButton(onClick = { riskDismissed = true }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }
        }
        Column(
            Modifier.align(Alignment.CenterEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (landmarks != null) {
                SmallFloatingActionButton(
                    // no fix yet means nowhere to put the pin
                    // re-centre too: the menu hangs off the pin, which is hidden while the fix is off screen
                    onClick = { last?.let { follow = true; landmarks.addHere(it.lat, it.lon, it.time) } },
                    containerColor = if (last != null) FloatingActionButtonDefaults.containerColor else MaterialTheme.colorScheme.surfaceVariant,
                ) { Icon(Icons.Default.PushPin, contentDescription = "Add landmark here") }
            }
            if (!follow) {
                SmallFloatingActionButton(onClick = { follow = true }) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Re-centre")
                }
            }
        }
        Card(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp).fillMaxWidth()) {
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = if (recording) onPause else onResume, modifier = Modifier.weight(1f)) {
                    Text(if (recording) "Pause" else "Resume")
                }
                Button(
                    onClick = { confirmStop = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020)),
                ) { Text("Stop") }
            }
        }
    }

    if (landmarks != null) {
        LandmarkLayer(landmarks, hike, canCamera = s.status == HikeStatus.RECORDING || s.status == HikeStatus.PAUSED) { Screen(it) }
    } else {
        Screen(null)
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            text = { Text("Stop and save this hike?") },
            confirmButton = { TextButton(onClick = { confirmStop = false; onStop() }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep recording") } },
        )
    }
}

@Composable
fun StatsRow(vararg items: Pair<String, String>) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        items.forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, style = MaterialTheme.typography.titleMedium)
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
