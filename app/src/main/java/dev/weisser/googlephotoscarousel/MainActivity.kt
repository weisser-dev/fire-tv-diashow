package dev.weisser.googlephotoscarousel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val Ink = Color(0xFF10141B)
private val SurfaceCard = Color(0xFF1B222C)
private val Accent = Color(0xFFE8C98C)
class MainActivity : ComponentActivity() {
    private val ambientRepo by lazy { AmbientRepository(this) }
    private var googleError by mutableStateOf<String?>(null)
    private var pickerUri by mutableStateOf<String?>(null)
    private var googleUserCode by mutableStateOf("")
    private var isImporting by mutableStateOf(false)
    private var importStatus by mutableStateOf("")
    private var importedFiles by mutableStateOf<List<String>>(emptyList())
    private var localPhotoUris by mutableStateOf<List<String>>(emptyList())
    private var importJob: Job? = null
    private var googleFlowJob: Job? = null
    private var remoteKeyHandler: ((Int) -> Boolean)? = null
    private var updateRelease by mutableStateOf<AppRelease?>(null)
    private var downloadedUpdate by mutableStateOf<File?>(null)
    private var updateBusy by mutableStateOf(false)
    private var updateStatus by mutableStateOf<String?>(null)
    private var displayDirectionForKeys = 0

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val mapped = mapRemoteDirection(event.keyCode, displayDirectionForKeys)
        if (mapped == event.keyCode) return super.dispatchKeyEvent(event)
        val remappedEvent = KeyEvent(
            event.downTime, event.eventTime, event.action, mapped, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source
        )
        return super.dispatchKeyEvent(remappedEvent)
    }

    private fun mapRemoteDirection(keyCode: Int, rotation: Int): Int {
        if (rotation == 90) return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_RIGHT
            KeyEvent.KEYCODE_DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> keyCode
        }
        if (rotation == 270) return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_DPAD_RIGHT
            KeyEvent.KEYCODE_DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_UP
            else -> keyCode
        }
        return keyCode
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (remoteKeyHandler?.invoke(keyCode) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    private val photoChooser = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val prefs = getSharedPreferences("carousel", MODE_PRIVATE)
        val stored = prefs.getStringSet("local_photos", emptySet()).orEmpty().toMutableSet()
        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            stored.add(uri.toString())
        }
        prefs.edit().putStringSet("local_photos", stored).apply()
        localPhotoUris = stored.toList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val photoPrefs = getSharedPreferences("carousel", MODE_PRIVATE)
        displayDirectionForKeys = photoPrefs.getInt("display_rotation", 0)
        localPhotoUris = photoPrefs.getStringSet("local_photos", emptySet()).orEmpty().toList()
        importedFiles = photoPrefs.getStringSet("google_photos", emptySet()).orEmpty().toList()
        setContent {
            val prefs = remember { getSharedPreferences("carousel", MODE_PRIVATE) }
            val localPhotos = localPhotoUris
            var seconds by remember { mutableIntStateOf(prefs.getInt("seconds", 8)) }
            var shuffle by remember { mutableStateOf(prefs.getBoolean("shuffle", false)) }
            var orientation by remember { mutableIntStateOf(prefs.getInt("orientation", 0)) }
            var displayRotation by remember { mutableIntStateOf(prefs.getInt("display_rotation", 0)) }
            var orientationChosen by remember { mutableStateOf(prefs.contains("display_rotation")) }
            var panZoom by remember { mutableStateOf(prefs.getBoolean("pan_zoom", false)) }
            var playing by remember { mutableStateOf(false) }
            var index by remember { mutableIntStateOf(0) }
            var showSettings by remember { mutableStateOf(false) }
            var message by remember { mutableStateOf<String?>(null) }
            val context = LocalContext.current
            val images = remember(localPhotos, importedFiles) { (localPhotos.map { "uri:$it" } + importedFiles.map { "file:$it" }).distinct() }

            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = SurfaceCard, onBackground = Color.White, onSurface = Color.White)) {
                DisposableEffect(playing, images.size) {
                    remoteKeyHandler = { code ->
                        if (!playing || images.isEmpty()) false else when (code) {
                            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> { index = (index + 1) % images.size; true }
                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { index = (index - 1 + images.size) % images.size; true }
                            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { playing = !playing; true }
                            else -> false
                        }
                    }
                    onDispose { remoteKeyHandler = null }
                }
                LaunchedEffect(images.size, playing, seconds, shuffle) {
                    if (playing && images.size > 1) {
                        while (true) {
                            delay(seconds * 1000L)
                            index = if (shuffle) {
                                if (images.size <= 1) 0 else (images.indices.filter { it != index }.random())
                            } else (index + 1) % images.size
                        }
                    }
                }
                DisposableEffect(playing) {
                    if (playing) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                BackHandler(playing) { playing = false }
                BackHandler(showSettings) { showSettings = false }
                BackHandler(pickerUri != null) { cancelGooglePicker() }

                BoxWithConstraints(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
                    val swapsDimensions = displayRotation == 90 || displayRotation == 270
                    Box(
                        Modifier
                            .width(if (swapsDimensions) maxHeight else maxWidth)
                            .height(if (swapsDimensions) maxWidth else maxHeight)
                            .graphicsLayer { rotationZ = displayRotation.toFloat() }
                            .background(Ink)
                    ) {
                    if (playing && images.isNotEmpty()) {
                        PlaybackView(images, index, orientation, panZoom, seconds, playing, onPause = { playing = false }, onNext = { index = (index + 1) % images.size }, onPrevious = { index = (index - 1 + images.size) % images.size })
                    } else {
                        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
                            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("STILLFRAME", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                                    Text("Deine Fotos. Ganz groß.", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                                }
                                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Tune, "Einstellungen", tint = Color.White) }
                            }
                            if (updateRelease != null || updateBusy || updateStatus != null) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF263240)).padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(updateStatus ?: "Neue Version verfügbar · ${updateRelease?.tag}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        if (updateBusy) Text("Update wird geladen …", color = Color(0xFFB8C1CC), fontSize = 12.sp)
                                    }
                                    if (!updateBusy) {
                                        Button(onClick = { downloadOrInstallUpdate() }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) {
                                            Text(if (downloadedUpdate == null) "Update" else "Installieren")
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(22.dp))
                            Text("QUELLE WÄHLEN", color = Color(0xFF9DA8B6), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SourceCard("Local images", "Fotos von diesem Gerät", Icons.Default.FolderOpen, Modifier.weight(1f), count = localPhotos.size, onClick = { photoChooser.launch(arrayOf("image/*")) })
                                SourceCard("Google Fotos", "Sicher per QR auswählen", Icons.Default.Cloud, Modifier.weight(1f), count = importedFiles.size, onClick = { requestGoogleAccess() })
                            }
                            if (isImporting) {
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp), color = Accent)
                                Text(importStatus, color = Color(0xFFB8C1CC), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            }
                            Spacer(Modifier.height(24.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("DEINE PRÄSENTATION", color = Color(0xFF9DA8B6), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                    Text(if (images.isEmpty()) "Bilder hinzufügen, um zu starten" else "${images.size} Bilder bereit", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                }
                                if (images.isNotEmpty()) TextButton(onClick = { clearPhotos() }) { Text("Leeren", color = Color(0xFFADB7C4)) }
                            }
                            Spacer(Modifier.height(12.dp))
                            if (images.isEmpty()) EmptyState(onLocal = { photoChooser.launch(arrayOf("image/*")) }, onGoogle = { requestGoogleAccess() })
                            else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                                    itemsIndexed(images) { i, model ->
                                        AsyncImage(model = model.removePrefix("uri:").removePrefix("file:"), contentDescription = null, modifier = Modifier.size(128.dp, 86.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceCard).clickable { index = i; playing = true }, contentScale = ContentScale.Crop)
                                    }
                                }
                                Spacer(Modifier.height(20.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(onClick = { playing = true }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink), modifier = Modifier.height(54.dp)) {
                                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Abspielen", fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(onClick = { index = (index - 1 + images.size) % images.size }, modifier = Modifier.height(54.dp)) { Icon(Icons.Default.SkipPrevious, "Vorheriges Bild") }
                                    OutlinedButton(onClick = { index = (index + 1) % images.size }, modifier = Modifier.height(54.dp)) { Icon(Icons.Default.SkipNext, "Nächstes Bild") }
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${seconds}s pro Bild", color = Color(0xFFC1C8D2), fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { shuffle = !shuffle; prefs.edit().putBoolean("shuffle", shuffle).apply() }) { Icon(Icons.Default.Shuffle, "Zufall", tint = if (shuffle) Accent else Color.Gray) }
                                    Text(if (shuffle) "Shuffle an" else "Shuffle aus", color = Color(0xFFC1C8D2), fontSize = 13.sp)
                                }
                                Slider(value = seconds.toFloat(), onValueChange = { seconds = it.toInt().coerceIn(3, 60); prefs.edit().putInt("seconds", seconds).apply() }, valueRange = 3f..60f, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                            }
                            Spacer(Modifier.weight(1f))
                            Text("Bilddrehung: ${orientation}° · Display: ${displayDirectionName(displayRotation)}", color = Color(0xFF788390), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp), textAlign = TextAlign.Center)
                        }
                    }
                    if (!orientationChosen) {
                        DisplayDirectionSetup(
                            onSelect = { degrees ->
                                displayRotation = degrees
                                displayDirectionForKeys = degrees
                                prefs.edit().putInt("display_rotation", degrees).apply()
                                orientationChosen = true
                            }
                        )
                    }
                    pickerUri?.let { url ->
                        PickerQrDialog(url, googleUserCode, importStatus, onCancel = { cancelGooglePicker() }, onDone = { pickerUri = null })
                    }
                    (googleError ?: message)?.let { text -> InlineMessageDialog(text) { googleError = null; message = null } }
                    if (showSettings) {
                        SettingsDialog(
                            seconds, orientation, panZoom, displayRotation,
                            onSeconds = { seconds = it; prefs.edit().putInt("seconds", it).apply() },
                            onOrientation = { orientation = it; prefs.edit().putInt("orientation", it).apply() },
                            onPanZoom = { panZoom = it; prefs.edit().putBoolean("pan_zoom", it).apply() },
                            onDisplayRotation = { degrees ->
                                displayRotation = degrees
                                displayDirectionForKeys = degrees
                                prefs.edit().putInt("display_rotation", degrees).apply()
                            },
                            onClose = { showSettings = false }
                        )
                    }
                    }
                }
            }
        }
        checkForUpdates()
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            val prefs = getSharedPreferences("carousel", MODE_PRIVATE)
            val cachedTag = prefs.getString("latest_release_tag", null)
            if (cachedTag != null && ReleaseUpdater.isNewer(cachedTag, BuildConfig.VERSION_NAME)) {
                updateRelease = AppRelease(cachedTag, "https://github.com/weisser-dev/google-photos-carousel/releases/download/$cachedTag/app-release.apk")
            }
            val now = System.currentTimeMillis()
            val lastCheck = prefs.getLong("latest_release_checked_at", 0L)
            if (now - lastCheck < 6 * 60 * 60 * 1000L) return@launch
            val release = runCatching { withContext(Dispatchers.IO) { ReleaseUpdater.latest() } }.getOrNull()
            if (release != null && ReleaseUpdater.isNewer(release.tag, BuildConfig.VERSION_NAME)) {
                updateRelease = release
                updateStatus = null
            }
            if (release != null) {
                prefs.edit().putString("latest_release_tag", release.tag).putLong("latest_release_checked_at", now).apply()
            }
        }
    }

    private fun downloadOrInstallUpdate() {
        val readyApk = downloadedUpdate
        if (readyApk != null) {
            installUpdate(readyApk)
            return
        }
        val release = updateRelease ?: return
        lifecycleScope.launch {
            updateBusy = true
            updateStatus = null
            runCatching {
                val apk = withContext(Dispatchers.IO) {
                    ReleaseUpdater.download(release, File(cacheDir, "updates/app-release.apk"))
                }
                val info = packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                    ?: error("Die heruntergeladene Datei ist keine gültige APK.")
                val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
                if (info.packageName != packageName || versionCode <= BuildConfig.VERSION_CODE) {
                    error("Die APK passt nicht zu dieser App oder enthält kein neueres Update.")
                }
                downloadedUpdate = apk
            }.onFailure { error ->
                updateStatus = error.localizedMessage ?: "Das Update konnte nicht geladen werden."
            }
            updateBusy = false
        }
    }

    private fun installUpdate(apk: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                updateStatus = "Erlaube Photo Carousel die Installation unbekannter Apps und tippe erneut auf Installieren."
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (error: Exception) {
            updateStatus = error.localizedMessage ?: "Der Android-Installationsdialog konnte nicht geöffnet werden."
        }
    }

    private fun requestGoogleAccess() {
        isImporting = true
        importStatus = "Google Ambient-Anmeldung wird vorbereitet …"
        googleFlowJob?.cancel()
        googleFlowJob = lifecycleScope.launch {
            runCatching {
                val auth = ambientRepo.requestDeviceCode()
                pickerUri = auth.verificationUrl
                googleUserCode = auth.userCode
                importStatus = "Scanne den Code und gib den angezeigten Code ein …"
                val token = ambientRepo.authorize(auth) { importStatus = it }
                importStatus = "Google Fotos Gerät wird eingerichtet …"
                val device = ambientRepo.createDevice(token)
                pickerUri = device.settingsUri
                googleUserCode = ""
                importStatus = "Wähle auf dem Handy ein Album aus …"
                ambientRepo.waitForSources(token, device) { importStatus = it }
            }.onSuccess { paths ->
                importedFiles = paths
                getSharedPreferences("carousel", MODE_PRIVATE).edit().putStringSet("google_photos", paths.toSet()).apply()
                isImporting = false; importStatus = "${paths.size} Fotos übernommen."; pickerUri = null
            }.onFailure {
                isImporting = false; pickerUri = null; googleError = it.localizedMessage ?: "Google Fotos konnte nicht verbunden werden."
            }
        }
    }

    private fun cancelGooglePicker() {
        googleFlowJob?.cancel()
        googleFlowJob = null
        pickerUri = null
        isImporting = false
    }

    private fun clearPhotos() {
        val photos = localPhotoUris
        localPhotoUris = emptyList()
        importedFiles = emptyList()
        getSharedPreferences("carousel", MODE_PRIVATE).edit().remove("local_photos").remove("google_photos").apply()
        photos.forEach { value ->
            runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        File(filesDir, "google-photos").listFiles()?.forEach { it.delete() }
    }

}

@Composable
private fun SourceCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, count: Int, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(SurfaceCard).clickable(onClick = onClick).padding(16.dp)) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(subtitle, color = Color(0xFFAAB4C0), fontSize = 12.sp, minLines = 1)
        if (count > 0) Text("$count ausgewählt", color = Accent, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun EmptyState(onLocal: () -> Unit, onGoogle: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp).clip(RoundedCornerShape(20.dp)).background(SurfaceCard).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.PhotoLibrary, null, tint = Accent, modifier = Modifier.size(50.dp))
        Spacer(Modifier.height(12.dp))
        Text("Deine Erinnerungen verdienen\neine große Leinwand.", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Text("Wähle lokale Bilder oder stelle eine Auswahl in Google Fotos zusammen.", color = Color(0xFFADB7C4), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onLocal) { Text("Local images", color = Accent) }
            TextButton(onClick = onGoogle) { Text("Google Fotos", color = Accent) }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun PlaybackView(images: List<String>, index: Int, rotation: Int, panZoom: Boolean, seconds: Int, playing: Boolean, onPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val model = images[index].removePrefix("uri:").removePrefix("file:")
        var sourceLandscape by remember(model) { mutableStateOf(false) }
        val panX = remember { Animatable(0f) }
        val density = LocalDensity.current
        val panEnabled = panZoom && sourceLandscape && (rotation == 90 || rotation == 270)
        LaunchedEffect(index, panEnabled, playing, seconds, maxWidth) {
            if (panEnabled && playing) {
                val travel = with(density) { maxWidth.toPx() * 0.06f }
                val start = if (index % 2 == 0) -travel else travel
                panX.snapTo(start)
                panX.animateTo(-start, tween(maxOf(seconds * 3, 15) * 1000, easing = LinearEasing))
            } else panX.snapTo(0f)
        }
        AnimatedContent(targetState = model, transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) }, label = "photo-fade") { photo ->
            AsyncImage(
                model = photo,
                contentDescription = "Slideshow Foto",
                onSuccess = { state -> sourceLandscape = state.result.drawable.intrinsicWidth > state.result.drawable.intrinsicHeight },
                modifier = Modifier.fillMaxSize().then(if (panEnabled) Modifier.clipToBounds() else Modifier.padding(18.dp))
                    .graphicsLayer {
                        rotationZ = rotation.toFloat()
                        if (panEnabled) {
                            scaleX = 1.08f
                            scaleY = 1.08f
                            translationX = panX.value
                        }
                    },
                contentScale = if (panEnabled) ContentScale.Crop else ContentScale.Fit
            )
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clip(RoundedCornerShape(24.dp)).background(Color(0x990E1116)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Vorheriges Bild", tint = Color.White) }
            IconButton(onClick = onPause) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Pause", tint = Accent) }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Nächstes Bild", tint = Color.White) }
            Text("${index + 1} / ${images.size}", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

@Composable
private fun SettingsDialog(
    seconds: Int,
    orientation: Int,
    panZoom: Boolean,
    displayRotation: Int,
    onSeconds: (Int) -> Unit,
    onOrientation: (Int) -> Unit,
    onPanZoom: (Boolean) -> Unit,
    onDisplayRotation: (Int) -> Unit,
    onClose: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 620.dp).heightIn(max = 700.dp), color = SurfaceCard, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text("Bild & Bildschirm", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(18.dp))
                Text("Displayrichtung", fontWeight = FontWeight.SemiBold)
                Text("Dreht Menü, Wiedergabe und Fernbedienungssteuerung gemeinsam.", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 90, 270).forEach { deg ->
                        FilterChip(selected = displayRotation == deg, onClick = { onDisplayRotation(deg) }, label = { Text(displayDirectionName(deg)) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Bilddrehung", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 90, 180, 270).forEach { deg ->
                        FilterChip(selected = orientation == deg, onClick = { onOrientation(deg) }, label = { Text("${deg}°") })
                    }
                }
                Text("Dreht die Fotos, ohne Originaldateien zu verändern.", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Schwenken & Zoomen", fontWeight = FontWeight.SemiBold)
                        Text("Dezenter Schwenk: Bei 5 s Bilddauer bewegt sich das Bild nur leicht. Eine ganze Schwenkbewegung dauert mindestens 15 s.", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(checked = panZoom, onCheckedChange = onPanZoom)
                }
                Spacer(Modifier.height(14.dp))
                Text("Bilddauer · ${seconds}s", fontWeight = FontWeight.SemiBold)
                Slider(value = seconds.toFloat(), onValueChange = { onSeconds(it.toInt().coerceIn(3, 60)) }, valueRange = 3f..60f)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onClose) { Text("Fertig") }
                }
            }
        }
    }
}

@Composable
private fun DisplayDirectionSetup(onSelect: (Int) -> Unit) {
    val firstOption = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstOption.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 680.dp), color = SurfaceCard, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Displayausrichtung", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(10.dp))
                Text("Wie ist dein Fernseher montiert?", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("Menü, Bilder und Fernbedienung werden passend ausgerichtet.", color = Color(0xFFADB7C4), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onSelect(0) }, modifier = Modifier.weight(1f).focusRequester(firstOption)) { Text("Querformat") }
                    Button(onClick = { onSelect(90) }, modifier = Modifier.weight(1f)) { Text("Hochformat rechts herum") }
                    Button(onClick = { onSelect(270) }, modifier = Modifier.weight(1f)) { Text("Hochformat links herum") }
                }
            }
        }
    }
}

private fun displayDirectionName(degrees: Int): String = when (degrees) {
    90 -> "Hochformat rechts"
    270 -> "Hochformat links"
    180 -> "Querformat 180°"
    else -> "Querformat"
}

@Composable
private fun InlineMessageDialog(message: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 520.dp), color = SurfaceCard, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Hinweis", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(message, color = Color(0xFFCFD5DD), modifier = Modifier.padding(vertical = 16.dp), textAlign = TextAlign.Center)
                Button(onClick = onClose) { Text("OK") }
            }
        }
    }
}

@Composable
private fun PickerQrDialog(uri: String, userCode: String, status: String, onCancel: () -> Unit, onDone: () -> Unit) {
    val bitmap = remember(uri) {
        runCatching {
            val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 640, 640)
            android.graphics.Bitmap.createBitmap(640, 640, android.graphics.Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until 640) for (y in 0 until 640) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }.asImageBitmap()
        }.getOrNull()
    }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp).heightIn(max = 900.dp), color = SurfaceCard, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Mit Google Fotos verbinden", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(if (userCode.isNotBlank()) "Scanne den Code am Handy und gib dort diesen Code ein:" else "Scanne den Code mit deinem Handy und wähle in Google Fotos das Album für diesen Fernseher.", textAlign = TextAlign.Center, color = Color(0xFFCFD5DD), modifier = Modifier.padding(top = 12.dp))
                if (userCode.isNotBlank()) Text(userCode, color = Accent, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(14.dp))
                if (bitmap != null) Image(bitmap, "QR-Code für Google Photos Ambient", modifier = Modifier.size(220.dp))
                Spacer(Modifier.height(8.dp))
                Text(status.ifBlank { "Nach der Auswahl tippe auf Fertig." }, fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Abbrechen") }
                    Button(onClick = onDone) { Text("Fertig") }
                }
            }
        }
    }
}
