package com.walkwalk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.walkwalk.data.HikeStatus
import com.walkwalk.map.TopoMap
import com.walkwalk.recording.RecordingController
import kotlinx.coroutines.delay

@Composable
fun RecordingScreen(
    controller: RecordingController,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    var follow by remember { mutableStateOf(true) }
    var confirmStop by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val atRisk = remember { Permissions.pocketRecordingAtRisk(context) }

    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(1_000) }
    }

    val s = state
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val last = s.track.lastOrNull()
    val recording = s.status == HikeStatus.RECORDING
    // elapsed keeps ticking between fixes while recording
    val elapsed = s.elapsedMs + if (recording && last != null) (now - last.time).coerceIn(0, 60_000) else 0

    Box(Modifier.fillMaxSize()) {
        TopoMap(
            modifier = Modifier.fillMaxSize(),
            track = s.track,
            marker = last?.let { it.lat to it.lon },
            follow = follow,
            fitTrackOnFirstLayout = false,
            onUserPan = { follow = false },
        )
        Column(Modifier.safeDrawingPadding().padding(12.dp).fillMaxWidth()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        if (recording) "Recording · ${Format.duration(elapsed)}" else "Paused · ${Format.duration(elapsed)}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    StatsRow(
                        "km" to Format.km2(s.distanceM),
                        "m alt" to Format.meters(s.altitudeM),
                        "km/h" to Format.speed(s.speedKmh),
                        "m ↑" to Format.meters(s.ascentM),
                    )
                }
            }
            if (atRisk) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(
                        "Recording may stop when the phone is in your pocket — allow location all the time and disable battery optimisation.",
                        Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB00020),
                    )
                }
            }
        }
        if (!follow) {
            SmallFloatingActionButton(
                onClick = { follow = true },
                modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
            ) { Icon(Icons.Default.MyLocation, contentDescription = "Re-centre") }
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
