package com.walkwalk

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.FileProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.walkwalk.data.HikeEntity
import com.walkwalk.domain.GpxParser
import com.walkwalk.domain.GpxWaypoint
import com.walkwalk.domain.GpxWriter
import com.walkwalk.recording.RecordingService
import com.walkwalk.share.BundleImporter
import com.walkwalk.share.HikeBundle
import com.walkwalk.share.HikeSender
import com.walkwalk.share.ImportKind
import com.walkwalk.share.importKind
import com.walkwalk.ui.ActiveHike
import com.walkwalk.ui.HikeListScreen
import com.walkwalk.ui.RecordingScreen
import com.walkwalk.ui.ReplayScreen
import com.walkwalk.ui.ReplayViewModel
import com.walkwalk.ui.SettingsScreen
import com.walkwalk.ui.landmarks.LandmarksViewModel
import com.walkwalk.ui.rememberPermissionFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val app get() = application as WalkwalkApp

    /** A GPX file handed to us by another app (open with / share), waiting to be imported. */
    private val incomingGpx = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { MaterialTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) incomingGpx.value = uri
    }

    @androidx.compose.runtime.Composable
    private fun App() {
        val nav = rememberNavController()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        // a bundle over the email size limit, waiting for the user to confirm sending it
        var bigSend by remember { mutableStateOf<File?>(null) }
        bigSend?.let { file ->
            AlertDialog(
                onDismissRequest = { bigSend = null },
                title = { Text("Send hike") },
                text = { Text("This hike is ${"%.1f".format(Locale.US, file.length() / 1_048_576.0)} MB. Email may refuse files over 25 MB; messengers accept it.") },
                confirmButton = { TextButton(onClick = { bigSend = null; launchSend(file) }) { Text("Send anyway") } },
                dismissButton = { TextButton(onClick = { bigSend = null }) { Text("Cancel") } },
            )
        }

        // An unfinished hike: show it live if still recording, otherwise the screen offers resume or save.
        LaunchedEffect(Unit) {
            if (app.recording.state.value != null || app.repository.activeHike() != null) {
                nav.navigate(RECORDING) { launchSingleTop = true }
            }
        }

        LaunchedEffect(Unit) {
            incomingGpx.collect { uri ->
                if (uri != null) {
                    incomingGpx.value = null
                    importFile(uri, nav, snackbar)
                }
            }
        }

        val startWithPermissions = rememberPermissionFlow { canRecord ->
            if (canRecord) {
                RecordingService.send(this, RecordingService.ACTION_START)
                nav.navigate(RECORDING) { launchSingleTop = true }
            } else {
                scope.launch { snackbar.showSnackbar("Location permission is needed to record a hike.") }
            }
        }
        // the demo still runs the location foreground service, so it needs the same permissions
        val startDemo = rememberPermissionFlow { canRecord ->
            if (canRecord) {
                app.demo.enable()
                RecordingService.send(this, RecordingService.ACTION_START)
                nav.navigate(RECORDING) { launchSingleTop = true }
            } else {
                scope.launch { snackbar.showSnackbar("Location permission is needed to record a hike.") }
            }
        }
        val startRace = rememberPermissionFlow { canRecord ->
            if (canRecord) {
                RecordingService.send(this, RecordingService.ACTION_START_RACE)
                nav.navigate(RECORDING) { launchSingleTop = true }
            } else {
                scope.launch { snackbar.showSnackbar("Location permission is needed to record a hike.") }
            }
        }
        val startRaceDemo = rememberPermissionFlow { canRecord ->
            if (canRecord) {
                app.demo.enable()
                RecordingService.send(this, RecordingService.ACTION_START_RACE)
                nav.navigate(RECORDING) { launchSingleTop = true }
            } else {
                scope.launch { snackbar.showSnackbar("Location permission is needed to record a hike.") }
            }
        }
        val pickGpx =rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch { importFile(uri, nav, snackbar) }
        }

        NavHost(nav, startDestination = LIST) {
            composable(LIST) {
                val live by app.recording.state.collectAsState()
                // re-read when a hike starts or ends, not on every fix
                val unfinished by produceState<HikeEntity?>(null, live?.hikeId) { value = app.repository.activeHike() }
                val l = live
                val u = unfinished
                HikeListScreen(
                    routeGroups = app.routeGroups,
                    snackbar = snackbar,
                    active = when {
                        l != null -> ActiveHike(u?.name ?: "Hike", l.status, l.distanceM, l.elapsedMs)
                        u != null -> ActiveHike(u.name, null, 0.0, 0)
                        else -> null
                    },
                    onOpenActive = { nav.navigate(RECORDING) { launchSingleTop = true } },
                    onReplaceActive = { startNew ->
                        scope.launch {
                            if (app.recording.state.value != null) app.stopRecording()
                            else app.repository.activeHike()?.let { app.saveUnfinished(it.id) }
                            startNew()
                        }
                    },
                    onOpen = { nav.navigate("replay/$it") },
                    onStartHike = startWithPermissions,
                    onStartRace = startRace,
                    onStartDemo = if (BuildConfig.DEBUG) startDemo else null,
                    onStartRaceDemo = if (BuildConfig.DEBUG) startRaceDemo else null,
                    onImport = { pickGpx.launch(arrayOf("*/*")) },
                    onExport = { hike -> scope.launch { exportGpx(hike) } },
                    onSend = { hike -> scope.launch { sendHike(hike, snackbar) { bigSend = it } } },
                    onRename = { hike, name -> scope.launch { app.repository.rename(hike.id, name) } },
                    onNameRoute = { hikes, name -> scope.launch { app.repository.nameRoute(hikes.map { it.id }, name) } },
                    onOpenSettings = { nav.navigate(SETTINGS) { launchSingleTop = true } },
                    onDelete = { hike ->
                        scope.launch {
                            app.repository.delete(hike.id)
                            app.photos.deletePrivate(hike.id)
                        }
                    },
                )
            }
            composable(SETTINGS) {
                SettingsScreen(app.settings, onBack = { nav.popBackStack() })
            }
            composable(RECORDING) {
                val live by app.recording.state.collectAsState()
                val trackColor by app.settings.trackColor.collectAsState()
                // exists only while a hike is live; the unfinished-hike prompt needs no landmarks
                val landmarks = live?.hikeId?.let { landmarksFor(it) }
                RecordingScreen(
                    controller = app.recording,
                    repo = app.repository,
                    // the hike keeps recording in the background; the list shows it as active
                    onBack = { nav.popBackStack(LIST, inclusive = false) },
                    onPause ={ RecordingService.send(this@MainActivity, RecordingService.ACTION_PAUSE) },
                    onResume = { RecordingService.send(this@MainActivity, RecordingService.ACTION_RESUME) },
                    onStop = {
                        scope.launch {
                            val id = app.stopRecording()
                            nav.popBackStack(LIST, inclusive = false)
                            if (id != null) nav.navigate("replay/$id")
                        }
                    },
                    // Start continues the unfinished hike rather than creating a new one
                    onContinueUnfinished = startWithPermissions,
                    onSaveUnfinished = { id ->
                        scope.launch {
                            app.saveUnfinished(id)
                            nav.popBackStack(LIST, inclusive = false)
                            nav.navigate("replay/$id")
                        }
                    },
                    landmarks = landmarks,
                    ghosts = app.ghosts,
                    race = app.race,
                    trackColor = trackColor,
                )
            }
            composable("replay/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments!!.getLong("id")
                val vm = viewModel(key = "replay-$id") { ReplayViewModel(app.repository, id, app.settings) }
                val trackColor by app.settings.trackColor.collectAsState()
                ReplayScreen(vm, landmarksFor(id), onBack = { nav.popBackStack(LIST, inclusive = false) }, trackColor = trackColor, onCorrectAltitude = {
                    scope.launch {
                        val ok = app.corrector.correct(id)
                        vm.reload()
                        if (!ok) snackbar.showSnackbar("Couldn't reach the elevation service — try again when online.")
                    }
                })
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun landmarksFor(hikeId: Long): LandmarksViewModel =
        viewModel(key = "landmarks-$hikeId") { LandmarksViewModel(app.repository, app.photos, hikeId, createSavedStateHandle()) }

    /** Imports a .walkwalk bundle or a GPX file, told apart by the zip magic bytes. */
    private suspend fun importFile(uri: Uri, nav: NavHostController, snackbar: SnackbarHostState) {
        val result = try {
            withContext(Dispatchers.IO) {
                val fallbackName = displayName(uri) ?: "Imported hike"
                val head = contentResolver.openInputStream(uri)?.use { it.readNBytes(4) } ?: throw IOException("cannot open")
                when (importKind(head)) {
                    ImportKind.BUNDLE -> {
                        val open = { contentResolver.openInputStream(uri) ?: throw IOException("cannot open") }
                        BundleImporter(app.repository, app.photos).import(open, fallbackName).let { it.hikeId to false to it.existing }
                    }
                    ImportKind.GPX -> {
                        val parsed = contentResolver.openInputStream(uri)?.use { GpxParser.parse(it) } ?: throw IOException("cannot open")
                        app.repository.importGpx(parsed, fallbackName) to true to false
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a bad file or a revoked URI can fail in many ways; all of them mean "can't read it"
            null
        }
        if (result == null) {
            snackbar.showSnackbar("Not a readable GPX or walkwalk file")
            return
        }
        val (idAndGpx, existing) = result
        val (id, isGpx) = idAndGpx
        if (isGpx) app.appScope.launch { app.corrector.correct(id) }
        nav.navigate("replay/$id")
        // a Toast, not the snackbar: the snackbar lives on the list screen we just navigated away from
        if (existing) Toast.makeText(this, "You already have this hike", Toast.LENGTH_LONG).show()
    }

    private suspend fun sendHike(hike: HikeEntity, snackbar: SnackbarHostState, confirm: (File) -> Unit) {
        val sent = try {
            HikeSender(this, app).build(hike)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            snackbar.showSnackbar("Couldn't prepare the hike for sending")
            return
        }
        val file = sent.file
        val bytes = file.length()
        val size = if (bytes < 100 * 1024) "${(bytes + 1023) / 1024} KB" else "%.1f MB".format(Locale.US, bytes / 1_048_576.0)
        val left = if (sent.skipped > 0) " · ${sent.skipped} photo${if (sent.skipped == 1) "" else "s"} couldn't be included" else ""
        Toast.makeText(this, "${file.name} · $size$left", Toast.LENGTH_LONG).show()
        if (file.length() > HikeBundle.WARN_BYTES) confirm(file) else launchSend(file)
    }

    private fun launchSend(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Send hike"))
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private suspend fun exportGpx(hike: HikeEntity) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(cacheDir, "export").apply { mkdirs() }
            val safe = hike.name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifBlank { "hike" }
            File(dir, "$safe.gpx").also { f -> f.outputStream().use { out ->
                val waypoints = app.repository.landmarks(hike.id).map { GpxWaypoint(it.lat, it.lon, it.time) }
                GpxWriter.write(hike.name, app.repository.points(hike.id), out, waypoints)
            } }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Export GPX"))
    }

    companion object {
        private const val LIST = "list"
        private const val RECORDING = "recording"
        private const val SETTINGS = "settings"
    }
}
