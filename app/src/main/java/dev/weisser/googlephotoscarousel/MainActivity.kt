package dev.weisser.googlephotoscarousel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.ui.window.Dialog

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
        localPhotoUris = photoPrefs.getStringSet("local_photos", emptySet()).orEmpty().toList()
        importedFiles = photoPrefs.getStringSet("google_photos", emptySet()).orEmpty().toList()
        setContent {
            val prefs = remember { getSharedPreferences("carousel", MODE_PRIVATE) }
            val localPhotos = localPhotoUris
            var seconds by remember { mutableIntStateOf(prefs.getInt("seconds", 8)) }
            var shuffle by remember { mutableStateOf(prefs.getBoolean("shuffle", false)) }
            var orientation by remember { mutableIntStateOf(prefs.getInt("orientation", 0)) }
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
                        @Suppress("DEPRECATION")
                        window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        @Suppress("DEPRECATION")
                        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                    }
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                BackHandler(playing) { playing = false }

                Box(Modifier.fillMaxSize().background(Ink)) {
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
                            Text("Hält den Bildschirm während der Wiedergabe aktiv · Ausrichtung: ${orientation}°", color = Color(0xFF788390), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp), textAlign = TextAlign.Center)
                        }
                    }
                    pickerUri?.let { url ->
                    PickerQrDialog(url, googleUserCode, importStatus, onCancel = { cancelGooglePicker() }, onDone = { pickerUri = null })
                    }
                    (googleError ?: message)?.let { text -> AlertDialog(onDismissRequest = { googleError = null; message = null }, title = { Text("Hinweis") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { googleError = null; message = null }) { Text("OK") } }) }
                    if (showSettings) {
                        SettingsDialog(seconds, orientation, panZoom, onSeconds = { seconds = it; prefs.edit().putInt("seconds", it).apply() }, onOrientation = { orientation = it; prefs.edit().putInt("orientation", it).apply() }, onPanZoom = { panZoom = it; prefs.edit().putBoolean("pan_zoom", it).apply() }, onClose = { showSettings = false })
                    }
                }
            }
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
private fun SettingsDialog(seconds: Int, orientation: Int, panZoom: Boolean, onSeconds: (Int) -> Unit, onOrientation: (Int) -> Unit, onPanZoom: (Boolean) -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("Bild & Bildschirm") }, text = {
        Column {
            Text("Bildrichtung", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 90, 180, 270).forEach { deg ->
                    FilterChip(selected = orientation == deg, onClick = { onOrientation(deg) }, label = { Text("${deg}°") })
                }
            }
            Text("Drehung für die Anzeige; Originaldateien bleiben unverändert.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Schwenken & Zoomen", fontWeight = FontWeight.SemiBold)
                    Text("Dezenter Schwenk: Bei 5 s Bilddauer bewegt sich das Bild nur leicht. Eine ganze Schwenkbewegung dauert mindestens 15 s.", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(checked = panZoom, onCheckedChange = onPanZoom)
            }
            Spacer(Modifier.height(18.dp))
            Text("Bilddauer · ${seconds}s", fontWeight = FontWeight.SemiBold)
            Slider(value = seconds.toFloat(), onValueChange = { onSeconds(it.toInt().coerceIn(3, 60)) }, valueRange = 3f..60f)
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Fertig") } })
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
    AlertDialog(onDismissRequest = onCancel, title = { Text("Mit Google Fotos verbinden") }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (userCode.isNotBlank()) "Scanne den Code am Handy und gib dort diesen Code ein:" else "Scanne den Code mit deinem Handy und wähle in Google Fotos das Album für diesen Fernseher.", textAlign = TextAlign.Center)
            if (userCode.isNotBlank()) Text(userCode, color = Accent, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(14.dp))
            if (bitmap != null) Image(bitmap, "QR-Code für Google Photos Ambient", modifier = Modifier.size(220.dp))
            Spacer(Modifier.height(8.dp))
            Text(status.ifBlank { "Nach der Auswahl tippe auf Fertig." }, fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
        }
    }, confirmButton = { TextButton(onClick = onDone) { Text("Fertig") } }, dismissButton = { TextButton(onClick = onCancel) { Text("Abbrechen") } })
}
