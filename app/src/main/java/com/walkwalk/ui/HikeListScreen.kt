package com.walkwalk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.walkwalk.data.HikeEntity
import com.walkwalk.data.HikeRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HikeListScreen(
    repo: HikeRepository,
    snackbar: SnackbarHostState,
    onOpen: (Long) -> Unit,
    onStartHike: () -> Unit,
    // debug builds only: replays a bundled route instead of GPS
    onStartDemo: (() -> Unit)?,
    onImport: () -> Unit,
    onExport: (HikeEntity) -> Unit,
    onRename: (HikeEntity, String) -> Unit,
    onDelete: (HikeEntity) -> Unit,
) {
    val hikes by repo.observeFinishedHikes().collectAsState(initial = emptyList())
    var renaming by remember { mutableStateOf<HikeEntity?>(null) }
    var deleting by remember { mutableStateOf<HikeEntity?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("walkwalk") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStartHike, modifier = Modifier.weight(1f)) { Text("Start hike") }
                onStartDemo?.let { OutlinedButton(onClick = it) { Text("Demo") } }
                OutlinedButton(onClick = onImport) { Text("Import GPX") }
            }
        },
    ) { padding ->
        if (hikes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No hikes yet. Press Start hike when you set off.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(hikes, key = { it.id }) { hike ->
                    HikeRow(hike, onClick = { onOpen(hike.id) }, onRename = { renaming = hike }, onExport = { onExport(hike) }, onDelete = { deleting = hike })
                    HorizontalDivider()
                }
            }
        }
    }

    renaming?.let { hike ->
        var name by remember(hike.id) { mutableStateOf(hike.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename hike") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { onRename(hike, name.trim()); renaming = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { hike ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete hike?") },
            text = { Text("\"${hike.name}\" will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { onDelete(hike); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HikeRow(hike: HikeEntity, onClick: () -> Unit, onRename: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(hike.name, fontWeight = FontWeight.SemiBold)
            Text(
                "${Format.date(hike.startTime)} · ${Format.km(hike.distanceM)} · ${Format.duration(hike.totalMs)} · ${Format.meters(hike.ascentM)} m ↑",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Export GPX") }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
