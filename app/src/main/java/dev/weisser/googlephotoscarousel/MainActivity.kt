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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val Ink = Color(0xFF10141B)
private val SurfaceCard = Color(0xFF1B222C)
private val Accent = Color(0xFFE8C98C)
private val RemoteFocus = Color(0xFF42D9FF)
private val RemoteFocusSurface = Color(0xFF153744)
class MainActivity : ComponentActivity() {
    private val pickerRepo by lazy { PickerRepository(this) }
    private var googleError by mutableStateOf<String?>(null)
    private var pickerUri by mutableStateOf<String?>(null)
    private var googleUserCode by mutableStateOf("")
    private var pickerApiFlow by mutableStateOf(false)
    private var qrDoneSelected by mutableStateOf(false)
    private var isImporting by mutableStateOf(false)
    private var importStatus by mutableStateOf("")
    private var importedFiles by mutableStateOf<List<String>>(emptyList())
    private var localPhotoUris by mutableStateOf<List<String>>(emptyList())
    private var importJob: Job? = null
    private var googleFlowJob: Job? = null
    private var remoteKeyHandler: ((Int) -> Boolean)? = null
    private val remoteHandledKeys = mutableSetOf<Int>()
    private var updateRelease by mutableStateOf<AppRelease?>(null)
    private var downloadedUpdate by mutableStateOf<File?>(null)
    private var updateBusy by mutableStateOf(false)
    private var updateStatus by mutableStateOf<String?>(null)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && remoteKeyHandler?.invoke(keyCode) == true) {
            remoteHandledKeys += keyCode
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && remoteHandledKeys.remove(keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
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
            var displayRotation by remember { mutableIntStateOf(prefs.getInt("display_rotation", 0)) }
            var orientationChosen by remember { mutableStateOf(prefs.contains("display_rotation")) }
            var panZoom by remember { mutableStateOf(prefs.getBoolean("pan_zoom", false)) }
            var playing by remember { mutableStateOf(false) }
            var index by remember { mutableIntStateOf(0) }
            var showSettings by remember { mutableStateOf(false) }
            var setupConfirmSelected by remember { mutableStateOf(false) }
            var sourceCardFocus by remember { mutableIntStateOf(-1) }
            var focusedSettingsNode by remember { mutableIntStateOf(0) }
            var mainFocusNode by remember { mutableIntStateOf(1) }
            var focusRestoreNode by remember { mutableIntStateOf(1) }
            var rotationBeforeSettings by remember { mutableIntStateOf(displayRotation) }
            var message by remember { mutableStateOf<String?>(null) }
            val context = LocalContext.current
            val focusManager = LocalFocusManager.current
            val settingsFocusRequester = remember { FocusRequester() }
            val localSourceFocusRequester = remember { FocusRequester() }
            val googleSourceFocusRequester = remember { FocusRequester() }
            val updateCheckFocusRequester = remember { FocusRequester() }
            var settingsButtonFocused by remember { mutableStateOf(false) }
            var updateCheckFocused by remember { mutableStateOf(false) }
            val images = remember(localPhotos, importedFiles) { (localPhotos.map { "uri:$it" } + importedFiles.map { "file:$it" }).distinct() }
            val portraitUi = displayRotation == 90 || displayRotation == 270

            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = SurfaceCard, onBackground = Color.White, onSurface = Color.White)) {
                fun focusMainNode(node: Int) {
                    mainFocusNode = node
                    if (node in 1..3) focusRestoreNode = node
                    runCatching {
                        when (node) {
                            0 -> settingsFocusRequester.requestFocus()
                            1 -> localSourceFocusRequester.requestFocus()
                            2 -> googleSourceFocusRequester.requestFocus()
                            3 -> updateCheckFocusRequester.requestFocus()
                        }
                    }
                }
                fun navigateMainFocus(code: Int, from: Int = mainFocusNode): Boolean {
                    if (code !in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) return false
                    val next = if (portraitUi) {
                        when (from) {
                            0 -> if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else null
                            1 -> when (code) {
                                KeyEvent.KEYCODE_DPAD_UP -> 0
                                KeyEvent.KEYCODE_DPAD_DOWN -> 2
                                else -> null
                            }
                            2 -> when (code) {
                                KeyEvent.KEYCODE_DPAD_UP -> 1
                                KeyEvent.KEYCODE_DPAD_DOWN -> 3
                                else -> null
                            }
                            3 -> if (code == KeyEvent.KEYCODE_DPAD_UP) 2 else null
                            else -> null
                        }
                    } else {
                        when (from) {
                            0 -> if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else null
                            1 -> when (code) {
                                KeyEvent.KEYCODE_DPAD_RIGHT -> 2
                                KeyEvent.KEYCODE_DPAD_UP -> 0
                                KeyEvent.KEYCODE_DPAD_DOWN -> 3
                                else -> null
                            }
                            2 -> when (code) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> 1
                                KeyEvent.KEYCODE_DPAD_UP -> 0
                                KeyEvent.KEYCODE_DPAD_DOWN -> 3
                                else -> null
                            }
                            3 -> if (code == KeyEvent.KEYCODE_DPAD_UP) 2 else null
                            else -> null
                        }
                    }
                    if (next != null) focusMainNode(next) else if (from != mainFocusNode) focusMainNode(from)
                    return true
                }
                DisposableEffect(playing, images.size, orientationChosen, displayRotation, setupConfirmSelected, sourceCardFocus, focusedSettingsNode, mainFocusNode, showSettings, pickerUri, googleError, message) {
                    remoteKeyHandler = when {
                        !orientationChosen -> { code ->
                            when (code) {
                                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> { setupConfirmSelected = true; true }
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> { setupConfirmSelected = false; true }
                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                                    if (setupConfirmSelected) {
                                        prefs.edit().putInt("display_rotation", displayRotation).apply()
                                        orientationChosen = true
                                    } else {
                                        displayRotation = nextDisplayRotation(displayRotation)
                                        setupConfirmSelected = false
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        googleError != null || message != null -> { code ->
                            if (googleError == null && message == null) {
                                navigateMainFocus(code, focusRestoreNode)
                            } else when (code) {
                                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> true
                                KeyEvent.KEYCODE_BACK -> { googleError = null; message = null; true }
                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> { googleError = null; message = null; true }
                                else -> false
                            }
                        }
                        pickerUri != null -> { code ->
                            when (code) {
                                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> { qrDoneSelected = true; true }
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> { qrDoneSelected = false; true }
                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                                    if (qrDoneSelected) pickerUri = null else cancelGooglePicker()
                                    true
                                }
                                else -> false
                            }
                        }
                        showSettings -> { code ->
                            when (code) {
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    when (focusedSettingsNode) {
                                        0 -> focusedSettingsNode = 1
                                        3 -> seconds = (seconds + 1).coerceAtMost(60)
                                    }
                                    if (focusedSettingsNode == 3) prefs.edit().putInt("seconds", seconds).apply()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    when (focusedSettingsNode) {
                                        1 -> focusedSettingsNode = 0
                                        3 -> seconds = (seconds - 1).coerceAtLeast(3)
                                    }
                                    if (focusedSettingsNode == 3) prefs.edit().putInt("seconds", seconds).apply()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    focusedSettingsNode = if (portraitUi) {
                                        (focusedSettingsNode + 1).coerceAtMost(3)
                                    } else when (focusedSettingsNode) {
                                        0, 1 -> 2
                                        2 -> 3
                                        else -> 3
                                    }
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    focusedSettingsNode = if (portraitUi) {
                                        (focusedSettingsNode - 1).coerceAtLeast(0)
                                    } else when (focusedSettingsNode) {
                                        2 -> 0
                                        3 -> 2
                                        else -> 0
                                    }
                                    true
                                }
                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                                    when (focusedSettingsNode) {
                                        0 -> displayRotation = nextDisplayRotation(displayRotation)
                                        1 -> { prefs.edit().putInt("display_rotation", displayRotation).apply(); showSettings = false }
                                        2 -> { panZoom = !panZoom; prefs.edit().putBoolean("pan_zoom", panZoom).apply() }
                                        3 -> { seconds = if (seconds >= 60) 3 else seconds + 1; prefs.edit().putInt("seconds", seconds).apply() }
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        orientationChosen && !playing && mainFocusNode in 0..3 -> { code ->
                            when (code) {
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    navigateMainFocus(code)
                                }
                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                                    when (mainFocusNode) {
                                        0 -> { rotationBeforeSettings = displayRotation; focusedSettingsNode = 0; showSettings = true }
                                        1 -> photoChooser.launch(arrayOf("image/*"))
                                        2 -> requestGooglePickerAccess()
                                        3 -> if (updateRelease != null) downloadOrInstallUpdate() else checkForUpdates(force = true)
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        playing && images.isNotEmpty() -> { code ->
                            when {
                                (portraitUi && code == KeyEvent.KEYCODE_DPAD_DOWN) || (!portraitUi && code == KeyEvent.KEYCODE_DPAD_RIGHT) || code == KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                    index = (index + 1) % images.size
                                    true
                                }
                                (portraitUi && code == KeyEvent.KEYCODE_DPAD_UP) || (!portraitUi && code == KeyEvent.KEYCODE_DPAD_LEFT) || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                    index = (index - 1 + images.size) % images.size
                                    true
                                }
                                code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                    playing = !playing
                                    true
                                }
                                else -> false
                            }
                        }
                        else -> null
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
                BackHandler(showSettings) {
                    displayRotation = rotationBeforeSettings
                    showSettings = false
                }
                BackHandler(pickerUri != null) { cancelGooglePicker() }
                BackHandler(!orientationChosen) {
                    displayRotation = 0
                    prefs.edit().putInt("display_rotation", 0).apply()
                    orientationChosen = true
                }
                BackHandler(googleError != null || message != null) { googleError = null; message = null }
                LaunchedEffect(showSettings, pickerUri, googleError, message, orientationChosen) {
                    val modalVisible = showSettings || pickerUri != null || googleError != null || message != null || !orientationChosen
                    if (modalVisible) {
                        focusManager.clearFocus(force = true)
                    } else {
                        focusMainNode(focusRestoreNode)
                    }
                }

                BoxWithConstraints(
                    Modifier.fillMaxSize().background(Ink).padding(28.dp)
                        .border(2.dp, Color(0xFF394654)).padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val swapsDimensions = displayRotation == 90 || displayRotation == 270
                    Box(
                        Modifier
                            .requiredSize(
                                width = if (swapsDimensions) maxHeight else maxWidth,
                                height = if (swapsDimensions) maxWidth else maxHeight
                            )
                            .graphicsLayer { rotationZ = displayRotation.toFloat() }
                            .background(Ink)
                    ) {
                    val modalVisible = showSettings || pickerUri != null || googleError != null || message != null || !orientationChosen
                    if (playing && images.isNotEmpty()) {
                        PlaybackView(images, index, displayRotation, panZoom, seconds, playing, onPause = { playing = false }, onNext = { index = (index + 1) % images.size }, onPrevious = { index = (index - 1 + images.size) % images.size })
                    } else if (modalVisible) {
                        Spacer(Modifier.fillMaxSize().background(Ink))
                    } else {
                        Column(
                            Modifier.fillMaxSize().focusProperties { canFocus = !modalVisible }.padding(horizontal = 24.dp)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("STILLFRAME", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                                    Text("Deine Fotos. Ganz groß.", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                                }
                                IconButton(
                                    modifier = Modifier
                                        .focusRequester(settingsFocusRequester)
                                        .onFocusChanged { settingsButtonFocused = it.isFocused; if (it.isFocused) mainFocusNode = 0 else if (mainFocusNode == 0) mainFocusNode = -1 }
                                        .then(if (settingsButtonFocused) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(14.dp)) else Modifier),
                                    onClick = {
                                        rotationBeforeSettings = displayRotation
                                        focusedSettingsNode = 0
                                        showSettings = true
                                    }
                                ) { Icon(Icons.Default.Tune, "Einstellungen", tint = Color.White) }
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
                                    if (!updateBusy && updateRelease != null) {
                                        Button(onClick = { downloadOrInstallUpdate() }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) {
                                            Text(if (downloadedUpdate == null) "Update" else "Installieren")
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(22.dp))
                            Text("QUELLE WÄHLEN", color = Color(0xFF9DA8B6), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(10.dp))
                            if (portraitUi) {
                                Column(Modifier.fillMaxWidth().focusGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SourceCard("Dateien", "Fotos von diesem Gerät", Icons.Default.FolderOpen, Modifier.fillMaxWidth().focusRequester(localSourceFocusRequester), count = localPhotos.size, onClick = { photoChooser.launch(arrayOf("image/*")) }, onFocusChange = { if (it) { sourceCardFocus = 0; mainFocusNode = 1; focusRestoreNode = 1 } else if (sourceCardFocus == 0) sourceCardFocus = -1 })
                                    SourceCard("Google Fotos", "Picker · Verbindung pausiert", Icons.Default.Cloud, Modifier.fillMaxWidth().focusRequester(googleSourceFocusRequester), count = importedFiles.size, onClick = { requestGooglePickerAccess() }, onFocusChange = { if (it) { sourceCardFocus = 1; mainFocusNode = 2; focusRestoreNode = 2 } else if (sourceCardFocus == 1) sourceCardFocus = -1 })
                                }
                            } else {
                                Row(Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SourceCard("Dateien", "Fotos von diesem Gerät", Icons.Default.FolderOpen, Modifier.weight(1f).focusRequester(localSourceFocusRequester), count = localPhotos.size, onClick = { photoChooser.launch(arrayOf("image/*")) }, onFocusChange = { if (it) { sourceCardFocus = 0; mainFocusNode = 1; focusRestoreNode = 1 } else if (sourceCardFocus == 0) sourceCardFocus = -1 })
                                    SourceCard("Google Fotos", "Picker · Verbindung pausiert", Icons.Default.Cloud, Modifier.weight(1f).focusRequester(googleSourceFocusRequester), count = importedFiles.size, onClick = { requestGooglePickerAccess() }, onFocusChange = { if (it) { sourceCardFocus = 1; mainFocusNode = 2; focusRestoreNode = 2 } else if (sourceCardFocus == 1) sourceCardFocus = -1 })
                                }
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
                            if (images.isEmpty()) EmptyState()
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
                            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Text("Ausrichtung: ${displayRotation}° · Version: ${BuildConfig.VERSION_NAME}", color = Color(0xFF9AA6B4), fontSize = 12.sp)
                                TextButton(
                                    onClick = { checkForUpdates(force = true) },
                                    modifier = Modifier.focusRequester(updateCheckFocusRequester).onFocusChanged { updateCheckFocused = it.isFocused; if (it.isFocused) mainFocusNode = 3 else if (mainFocusNode == 3) mainFocusNode = -1 }
                                        .then(if (updateCheckFocused) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(10.dp)) else Modifier)
                                ) { Text("Nach Updates suchen", color = if (updateCheckFocused) Color.White else Accent, fontSize = 12.sp) }
                            }
                        }
                    }
                    if (!orientationChosen) {
                        DisplayDirectionSetup(
                            rotation = displayRotation,
                            portraitUi = portraitUi,
                            confirmSelected = setupConfirmSelected,
                            onConfirmSelected = { setupConfirmSelected = it },
                            onRotate = {
                                displayRotation = nextDisplayRotation(displayRotation)
                                setupConfirmSelected = false
                            },
                            onConfirm = {
                                prefs.edit().putInt("display_rotation", displayRotation).apply()
                                orientationChosen = true
                            }
                        )
                    }
                    pickerUri?.let { url ->
                        PickerQrDialog(url, googleUserCode, importStatus, qrDoneSelected, pickerApiFlow, onCancel = { cancelGooglePicker() }, onDone = { pickerUri = null })
                    }
                    (googleError ?: message)?.let { text -> InlineMessageDialog(text) { googleError = null; message = null } }
                    if (showSettings) {
                        SettingsDialog(
                            seconds, panZoom, displayRotation, focusedSettingsNode, portraitUi,
                            onSeconds = { seconds = it; prefs.edit().putInt("seconds", it).apply() },
                            onPanZoom = { panZoom = it; prefs.edit().putBoolean("pan_zoom", it).apply() },
                            onRotateDisplay = {
                                displayRotation = nextDisplayRotation(displayRotation)
                            },
                            onConfirmDisplay = {
                                prefs.edit().putInt("display_rotation", displayRotation).apply()
                                showSettings = false
                            }
                        )
                    }
                    }
                }
            }
        }
        checkForUpdates()
    }

    private fun checkForUpdates(force: Boolean = false) {
        lifecycleScope.launch {
            val prefs = getSharedPreferences("carousel", MODE_PRIVATE)
            val cachedTag = prefs.getString("latest_release_tag", null)
            if (cachedTag != null && ReleaseUpdater.isNewer(cachedTag, BuildConfig.VERSION_NAME)) {
                updateRelease = AppRelease(cachedTag, "https://github.com/weisser-dev/google-photos-carousel/releases/download/$cachedTag/app-release.apk")
            }
            val now = System.currentTimeMillis()
            val lastCheck = prefs.getLong("latest_release_checked_at", 0L)
            if (!force && now - lastCheck < 15 * 60 * 1000L) return@launch
            updateBusy = true
            if (force) updateStatus = "Suche nach Updates …"
            val release = runCatching { withContext(Dispatchers.IO) { ReleaseUpdater.latest() } }.getOrNull()
            if (release != null && ReleaseUpdater.isNewer(release.tag, BuildConfig.VERSION_NAME)) {
                updateRelease = release
                updateStatus = null
            } else if (force) {
                updateRelease = null
                updateStatus = if (release == null) "Update-Prüfung fehlgeschlagen. Später erneut versuchen." else "Du verwendest bereits die aktuelle Version."
            }
            if (release != null) {
                prefs.edit().putString("latest_release_tag", release.tag).putLong("latest_release_checked_at", now).apply()
            }
            updateBusy = false
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

    private fun requestGooglePickerAccess() {
        googleError = "Google Fotos ist über den TV-QR-Login derzeit nicht verfügbar. Google blockiert den Photos-Picker-Zugriff im Gerätecode-Flow. Ambient ist deaktiviert; vorhandene lokale und zwischengespeicherte Bilder funktionieren weiterhin."
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
private fun SourceCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, count: Int, onClick: () -> Unit, onFocusChange: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .clip(RoundedCornerShape(18.dp))
            .background(if (focused) RemoteFocusSurface else SurfaceCard)
            .clickable(onClick = onClick)
            .then(if (focused) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(18.dp)) else Modifier)
            .padding(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(subtitle, color = Color(0xFFAAB4C0), fontSize = 12.sp, minLines = 1)
        if (count > 0) Text("$count ausgewählt", color = Accent, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp).clip(RoundedCornerShape(20.dp)).background(SurfaceCard).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.PhotoLibrary, null, tint = Accent, modifier = Modifier.size(50.dp))
        Spacer(Modifier.height(12.dp))
        Text("Deine Erinnerungen verdienen\neine große Leinwand.", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Text("Wähle lokale Bilder oder stelle eine Auswahl in Google Fotos zusammen.", color = Color(0xFFADB7C4), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
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
    panZoom: Boolean,
    displayRotation: Int,
    focusedSettingsNode: Int,
    portraitUi: Boolean,
    onSeconds: (Int) -> Unit,
    onPanZoom: (Boolean) -> Unit,
    onRotateDisplay: () -> Unit,
    onConfirmDisplay: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).focusGroup(), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 620.dp).heightIn(max = 700.dp), color = SurfaceCard, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text("Bild & Bildschirm", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(18.dp))
                Text("Displayrichtung", fontWeight = FontWeight.SemiBold)
                Text("Vorschau: ${displayDirectionName(displayRotation)} · Menü und Fernbedienung drehen sofort mit.", fontSize = 12.sp, color = Color(0xFFCFD5DD))
                Spacer(Modifier.height(10.dp))
                val rotateButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                    OutlinedButton(
                        onClick = onRotateDisplay,
                        modifier = buttonModifier
                            .focusProperties { canFocus = false }
                            .then(if (focusedSettingsNode == 0) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(14.dp)) else Modifier),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (focusedSettingsNode == 0) RemoteFocusSurface else Color.Transparent,
                            contentColor = if (focusedSettingsNode == 0) Color.White else Accent
                        )
                    ) {
                        Icon(Icons.Default.Rotate90DegreesCw, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Drehen")
                    }
                }
                val confirmButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                    Button(
                        onClick = onConfirmDisplay,
                        modifier = buttonModifier
                            .focusProperties { canFocus = false }
                            .then(if (focusedSettingsNode == 1) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(14.dp)) else Modifier),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (focusedSettingsNode == 1) RemoteFocusSurface else Accent,
                            contentColor = if (focusedSettingsNode == 1) Color.White else Ink
                        )
                    ) { Text("Bestätigen") }
                }
                if (portraitUi) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        rotateButton(Modifier.fillMaxWidth())
                        confirmButton(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rotateButton(Modifier.weight(1f))
                        confirmButton(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Schwenken & Zoomen", fontWeight = FontWeight.SemiBold)
                        Text("Dezenter Schwenk: Bei 5 s Bilddauer bewegt sich das Bild nur leicht. Eine ganze Schwenkbewegung dauert mindestens 15 s.", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = panZoom,
                        onCheckedChange = onPanZoom,
                        modifier = Modifier.focusProperties { canFocus = false }
                            .then(if (focusedSettingsNode == 2) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(24.dp)) else Modifier)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text("Bilddauer · ${seconds}s", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = seconds.toFloat(),
                    onValueChange = { onSeconds(it.toInt().coerceIn(3, 60)) },
                    valueRange = 3f..60f,
                    modifier = Modifier.focusProperties { canFocus = false }
                        .then(if (focusedSettingsNode == 3) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier)
                )
            }
        }
    }
}

@Composable
private fun DisplayDirectionSetup(rotation: Int, portraitUi: Boolean, confirmSelected: Boolean, onConfirmSelected: (Boolean) -> Unit, onRotate: () -> Unit, onConfirm: () -> Unit) {
    val rotateRequester = remember { FocusRequester() }
    var rotateFocused by remember { mutableStateOf(false) }
    var confirmFocused by remember { mutableStateOf(false) }
    LaunchedEffect(rotation) {
        delay(120)
        runCatching { rotateRequester.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).focusGroup(), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 680.dp), color = SurfaceCard, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Displayausrichtung", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(10.dp))
                Text("Wie ist dein Fernseher montiert?", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("Drehe die Vorschau, bis sie auf deinem Fernseher richtig steht.", color = Color(0xFFCFD5DD), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                Text("Vorschau: ${displayDirectionName(rotation)}", color = Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 16.dp))
                val rotateButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                    OutlinedButton(
                        onClick = onRotate,
                        modifier = buttonModifier
                            .focusRequester(rotateRequester)
                            .onFocusChanged { rotateFocused = it.isFocused; if (it.isFocused) onConfirmSelected(false) }
                            .then(if (!confirmSelected) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(14.dp)) else Modifier),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (rotateFocused) RemoteFocusSurface else Color.Transparent,
                            contentColor = if (rotateFocused) Color.White else Accent
                        )
                    ) {
                        Icon(Icons.Default.Rotate90DegreesCw, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Drehen")
                    }
                }
                val confirmButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                    Button(
                        onClick = onConfirm,
                        modifier = buttonModifier
                            .onFocusChanged { confirmFocused = it.isFocused; if (it.isFocused) onConfirmSelected(true) }
                            .then(if (confirmSelected) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(14.dp)) else Modifier),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (confirmFocused) RemoteFocusSurface else Accent,
                            contentColor = if (confirmFocused) Color.White else Ink
                        )
                    ) { Text("Bestätigen") }
                }
                if (portraitUi) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        rotateButton(Modifier.fillMaxWidth())
                        confirmButton(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rotateButton(Modifier.weight(1f))
                        confirmButton(Modifier.weight(1f))
                    }
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

private fun nextDisplayRotation(degrees: Int): Int = when (degrees) {
    0 -> 90
    90 -> 180
    180 -> 270
    else -> 0
}

@Composable
private fun InlineMessageDialog(message: String, onClose: () -> Unit) {
    val okRequester = remember { FocusRequester() }
    var okFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(120); runCatching { okRequester.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).focusGroup(), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 520.dp), color = SurfaceCard, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Hinweis", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(message, color = Color(0xFFCFD5DD), modifier = Modifier.padding(vertical = 16.dp), textAlign = TextAlign.Center)
                Button(
                    onClick = onClose,
                    modifier = Modifier.focusRequester(okRequester).onFocusChanged { okFocused = it.isFocused }
                        .then(if (okFocused) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(14.dp)) else Modifier),
                    colors = ButtonDefaults.buttonColors(containerColor = if (okFocused) RemoteFocusSurface else Accent, contentColor = if (okFocused) Color.White else Ink)
                ) { Text("OK") }
            }
        }
    }
}

@Composable
private fun PickerQrDialog(uri: String, userCode: String, status: String, doneSelected: Boolean, pickerApi: Boolean, onCancel: () -> Unit, onDone: () -> Unit) {
    val cancelRequester = remember { FocusRequester() }
    var cancelFocused by remember { mutableStateOf(false) }
    var doneFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(120); runCatching { cancelRequester.requestFocus() } }
    val bitmap = remember(uri) {
        runCatching {
            val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 640, 640)
            android.graphics.Bitmap.createBitmap(640, 640, android.graphics.Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until 640) for (y in 0 until 640) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }.asImageBitmap()
        }.getOrNull()
    }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).focusGroup(), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp).heightIn(max = 900.dp), color = SurfaceCard, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Mit Google Fotos verbinden", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        userCode.isNotBlank() -> "Schritt 1 von 2: Öffne den Link am Handy und gib dort den angezeigten Code ein. Danach erscheint hier die Fotoauswahl."
                        pickerApi -> "Scanne den Code mit deinem Handy und wähle die Fotos aus, die auf dem Fernseher angezeigt werden sollen. Ausgewählte Videos werden übersprungen."
                        else -> "Scanne den Code mit deinem Handy und wähle in Google Fotos das Album für diesen Fernseher aus."
                    },
                    textAlign = TextAlign.Center, color = Color(0xFFCFD5DD), modifier = Modifier.padding(top = 12.dp)
                )
                if (userCode.isNotBlank()) Text(userCode, color = Accent, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(14.dp))
                if (bitmap != null) Image(bitmap, "QR-Code für Google Fotos Picker", modifier = Modifier.size(220.dp))
                Spacer(Modifier.height(8.dp))
                Text(status.ifBlank { "Nach der Auswahl tippe auf Fertig." }, fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.focusRequester(cancelRequester).onFocusChanged { cancelFocused = it.isFocused }
                            .then(if (cancelFocused || !doneSelected) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier)
                    ) { Text("Abbrechen", color = if (cancelFocused) RemoteFocus else Accent) }
                    Button(
                        onClick = onDone,
                        modifier = Modifier.onFocusChanged { doneFocused = it.isFocused }
                            .then(if (doneFocused || doneSelected) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(14.dp)) else Modifier),
                        colors = ButtonDefaults.buttonColors(containerColor = if (doneFocused) RemoteFocusSurface else Accent, contentColor = if (doneFocused) Color.White else Ink)
                    ) { Text("Fertig") }
                }
            }
        }
    }
}
