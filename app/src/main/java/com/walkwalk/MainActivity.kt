package com.walkwalk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.walkwalk.data.HikeEntity
import com.walkwalk.domain.GpxException
import com.walkwalk.domain.GpxParser
import com.walkwalk.domain.GpxWriter
import com.walkwalk.recording.RecordingService
import com.walkwalk.ui.HikeListScreen
import com.walkwalk.ui.RecordingScreen
import com.walkwalk.ui.ReplayScreen
import com.walkwalk.ui.ReplayViewModel
import com.walkwalk.ui.rememberPermissionFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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
                    importGpx(uri, nav, snackbar)
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
        val pickGpx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch { importGpx(uri, nav, snackbar) }
        }

        NavHost(nav, startDestination = LIST) {
            composable(LIST) {
                HikeListScreen(
                    repo = app.repository,
                    snackbar = snackbar,
                    onOpen = { nav.navigate("replay/$it") },
                    onStartHike = startWithPermissions,
                    onImport = { pickGpx.launch(arrayOf("*/*")) },
                    onExport = { hike -> scope.launch { exportGpx(hike) } },
                    onRename = { hike, name -> scope.launch { app.repository.rename(hike.id, name) } },
                    onDelete = { hike -> scope.launch { app.repository.delete(hike.id) } },
                )
            }
            composable(RECORDING) {
                RecordingScreen(
                    controller = app.recording,
                    repo = app.repository,
                    onPause = { RecordingService.send(this@MainActivity, RecordingService.ACTION_PAUSE) },
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
                )
            }
            composable("replay/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments!!.getLong("id")
                val vm = viewModel(key = "replay-$id") { ReplayViewModel(app.repository, id) }
                ReplayScreen(vm, onCorrectAltitude = {
                    scope.launch {
                        val ok = app.corrector.correct(id)
                        vm.reload()
                        if (!ok) snackbar.showSnackbar("Couldn't reach the elevation service — try again when online.")
                    }
                })
            }
        }
    }

    private suspend fun importGpx(uri: Uri, nav: NavHostController, snackbar: SnackbarHostState) {
        val id = try {
            withContext(Dispatchers.IO) {
                val parsed = contentResolver.openInputStream(uri)?.use { GpxParser.parse(it) } ?: throw IOException("cannot open")
                app.repository.importGpx(parsed, displayName(uri) ?: "Imported hike")
            }
        } catch (e: GpxException) {
            null
        } catch (e: IOException) {
            null
        }
        if (id == null) {
            snackbar.showSnackbar("Not a readable GPX file")
            return
        }
        app.appScope.launch { app.corrector.correct(id) }
        nav.navigate("replay/$id")
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private suspend fun exportGpx(hike: HikeEntity) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(cacheDir, "export").apply { mkdirs() }
            val safe = hike.name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifBlank { "hike" }
            File(dir, "$safe.gpx").also { f -> f.outputStream().use { GpxWriter.write(hike.name, app.repository.points(hike.id), it) } }
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
    }
}
