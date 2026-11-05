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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.walkwalk.data.HikeEntity
import com.walkwalk.data.HikeStatus
import com.walkwalk.routes.HikeListEntry
import com.walkwalk.routes.RouteGroups

/** The hike that is still open (recording in the background, paused, or interrupted); [status] null = interrupted. */
data class ActiveHike(val name: String, val status: HikeStatus?, val distanceM: Double, val elapsedMs: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HikeListScreen(
    routeGroups: RouteGroups,
    snackbar: SnackbarHostState,
    active: ActiveHike?,
    onOpenActive: () -> Unit,
    // stops and saves the active hike, then runs the given start
    onReplaceActive: (startNew: () -> Unit) -> Unit,
    onOpen: (Long) -> Unit,
    onStartHike: () -> Unit,
    onStartRace: () -> Unit,
    // debug builds only: replays a bundled route instead of GPS
    onStartDemo: (() -> Unit)?,
    onStartRaceDemo: (() -> Unit)?,
    onImport: () -> Unit,
    onExport: (HikeEntity) -> Unit,
    onSend: (HikeEntity) -> Unit,
    onRename: (HikeEntity, String) -> Unit,
    onNameRoute: (List<HikeEntity>, String) -> Unit,
    onDelete: (HikeEntity) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // null until the first grouping is done, so "No hikes yet" never flashes
    val entries by remember(routeGroups) { routeGroups.observe() }.collectAsState(initial = null)
    // groups are keyed by their oldest hike, which stays the same as new hikes join
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    var renaming by remember { mutableStateOf<HikeEntity?>(null) }
    var namingRoute by remember { mutableStateOf<HikeListEntry.Group?>(null) }
    var deleting by remember { mutableStateOf<HikeEntity?>(null) }
    // a new hike ends the active one, so it is confirmed first
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun startOrConfirm(start: () -> Unit) = if (active != null) pendingStart = start else start()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("walkwalk") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { startOrConfirm(onStartHike) }, modifier = Modifier.weight(1f)) { Text("Start hike") }
                OutlinedButton(onClick = { startOrConfirm(onStartRace) }) { Text("Race") }
                onStartDemo?.let { OutlinedButton(onClick = { startOrConfirm(it) }) { Text("Demo") } }
                onStartRaceDemo?.let { OutlinedButton(onClick = { startOrConfirm(it) }) { Text("Race demo") } }
                OutlinedButton(onClick = onImport) { Text("Import") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            active?.let { ActiveHikeCard(it, onOpenActive) }
            val list = entries
            if (list == null) {
                Box(Modifier.fillMaxWidth().weight(1f))
            } else if (list.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (active == null) Text("No hikes yet. Press Start hike when you set off.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    @Composable
                    fun hikeRow(hike: HikeEntity, indent: Boolean) {
                        HikeRow(hike, indent, onClick = { onOpen(hike.id) }, onRename = { renaming = hike }, onExport = { onExport(hike) }, onSend = { onSend(hike) }, onDelete = { deleting = hike })
                        HorizontalDivider()
                    }
                    list.forEach { entry ->
                        when (entry) {
                            is HikeListEntry.Single -> item(key = "h${entry.hike.id}") { hikeRow(entry.hike, indent = false) }
                            is HikeListEntry.Group -> {
                                val groupKey = entry.hikes.last().id
                                val open = groupKey in expanded
                                item(key = "g$groupKey") {
                                    RouteRow(entry, open, onClick = { expanded = if (open) expanded - groupKey else expanded + groupKey }, onName = { namingRoute = entry })
                                    HorizontalDivider()
                                }
                                if (open) items(entry.hikes, key = { "h${it.id}" }) { hikeRow(it, indent = true) }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingStart?.let { start ->
        AlertDialog(
            onDismissRequest = { pendingStart = null },
            title = { Text("Start a new hike?") },
            text = { Text("\"${active?.name}\" is still open. It will be stopped and saved.") },
            confirmButton = { TextButton(onClick = { pendingStart = null; onReplaceActive(start) }) { Text("Stop and start new") } },
            dismissButton = { TextButton(onClick = { pendingStart = null }) { Text("Cancel") } },
        )
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

    namingRoute?.let { group ->
        var name by remember(group.hikes.last().id) { mutableStateOf(group.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { namingRoute = null },
            title = { Text("Name route") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("e.g. Home to work") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { onNameRoute(group.hikes, name.trim()); namingRoute = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { namingRoute = null }) { Text("Cancel") } },
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
private fun ActiveHikeCard(hike: ActiveHike, onClick: () -> Unit) {
    val (icon, tint, title) = when (hike.status) {
        HikeStatus.RECORDING -> Triple(Icons.Default.FiberManualRecord, Color(0xFFB00020), "Recording now")
        HikeStatus.PAUSED -> Triple(Icons.Default.Pause, MaterialTheme.colorScheme.primary, "Paused")
        else -> Triple(Icons.Default.Warning, Color(0xFFB00020), "Interrupted — continue or save")
    }
    Card(Modifier.fillMaxWidth().padding(12.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    if (hike.status == null) hike.name
                    else "${hike.name} · ${Format.km(hike.distanceM)} · ${Format.duration(hike.elapsedMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open")
        }
    }
}

@Composable
private fun RouteRow(group: HikeListEntry.Group, open: Boolean, onClick: () -> Unit, onName: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand")
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(group.name ?: "Unnamed route", fontWeight = FontWeight.SemiBold)
            Text("${group.hikes.size} hikes · last ${Format.date(group.newest.startTime)}", style = MaterialTheme.typography.bodySmall)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Name route") }, onClick = { menu = false; onName() })
            }
        }
    }
}

@Composable
private fun HikeRow(hike: HikeEntity, indent: Boolean, onClick: () -> Unit, onRename: () -> Unit, onExport: () -> Unit, onSend: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = if (indent) 52.dp else 16.dp, top = 12.dp, bottom = 12.dp),
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
                DropdownMenuItem(text = { Text("Send hike") }, onClick = { menu = false; onSend() })
                DropdownMenuItem(text = { Text("Export GPX") }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
