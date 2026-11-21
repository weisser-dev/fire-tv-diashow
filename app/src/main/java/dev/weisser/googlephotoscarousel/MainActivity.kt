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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val Ink = Color(0xFF10141B)
private val SurfaceCard = Color(0xFF1B222C)
private val Accent = Color(0xFFE8C98C)
private val RemoteFocus = Color(0xFF42D9FF)
private val RemoteFocusSurface = Color(0xFF153744)
class MainActivity : ComponentActivity() {
    private var message by mutableStateOf<String?>(null)
    private var localPhotoUris by mutableStateOf<List<String>>(emptyList())
    private var uploadedPhotos by mutableStateOf<List<UploadedPhoto>>(emptyList())
    private var uploadUrl by mutableStateOf<String?>(null)
    private var uploadStatus by mutableStateOf("")
    private var uploadServer: LocalPhotoUploadServer? = null
    private var remoteKeyHandler: ((Int) -> Boolean)? = null
    private val remoteHandledKeys = mutableSetOf<Int>()
    private var playbackControlsPulse by mutableIntStateOf(0)
    private var updateRelease by mutableStateOf<AppRelease?>(null)
    private var downloadedUpdate by mutableStateOf<File?>(null)
    private var updateBusy by mutableStateOf(false)
    private var updateStatus by mutableStateOf<String?>(null)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            val handled = remoteKeyHandler?.invoke(keyCode) == true
            if (handled) {
                playbackControlsPulse++
                remoteHandledKeys += keyCode
                return true
            }
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
        photoPrefs.edit().remove("google_photos").apply()
        File(filesDir, "google-photos").deleteRecursively()
        refreshUploadedPhotos()
        setContent {
            val prefs = remember { getSharedPreferences("carousel", MODE_PRIVATE) }
            val localPhotos = localPhotoUris
            var seconds by remember { mutableIntStateOf(prefs.getInt("seconds", 8)) }
            var shuffle by remember { mutableStateOf(prefs.getBoolean("shuffle", false)) }
            var displayRotation by remember { mutableIntStateOf(prefs.getInt("display_rotation", 0)) }
            var orientationChosen by remember { mutableStateOf(prefs.contains("display_rotation")) }
            var panZoom by remember { mutableStateOf(prefs.getBoolean("pan_zoom", false)) }
            var autoStartOnBoot by remember { mutableStateOf(prefs.getBoolean("auto_start_on_boot", true)) }
            var playing by remember { mutableStateOf(false) }
            var index by remember { mutableIntStateOf(0) }
            var showSettings by remember { mutableStateOf(false) }
            var setupConfirmSelected by remember { mutableStateOf(false) }
            var sourceCardFocus by remember { mutableIntStateOf(-1) }
            var focusedSettingsNode by remember { mutableIntStateOf(0) }
            var mainFocusNode by remember { mutableIntStateOf(1) }
            var photoManagerTarget by remember { mutableStateOf("clear") }
            var focusedPhotoIndex by remember { mutableIntStateOf(0) }
            var focusRestoreNode by remember { mutableIntStateOf(1) }
            var rotationBeforeSettings by remember { mutableIntStateOf(displayRotation) }
            var selectedUploadAlbum by remember { mutableStateOf(prefs.getString("selected_upload_album", "*") ?: "*") }
            var newestUploadsFirst by remember { mutableStateOf(prefs.getBoolean("newest_uploads_first", true)) }
            var focusedAlbumIndex by remember { mutableIntStateOf(0) }
            var paused by remember { mutableStateOf(false) }
            var pauseMenuIndex by remember { mutableIntStateOf(0) }
            var transitionStyle by remember { mutableStateOf(prefs.getString("transition_style", "fade") ?: "fade") }
            if (!prefs.getBoolean("fill_default_migrated", false)) {
                val editor = prefs.edit()
                if (prefs.getString("image_scale_mode", null) == "fit") editor.putString("image_scale_mode", "fill")
                editor.putBoolean("fill_default_migrated", true).apply()
            }
            var imageScaleMode by remember { mutableStateOf(prefs.getString("image_scale_mode", "fill") ?: "fill") }
            var imagePaddingDp by remember { mutableIntStateOf(prefs.getInt("image_padding_dp", 0)) }
            var showPinSetup by remember { mutableStateOf(false) }
            var pinEntry by remember { mutableStateOf("") }
            var pinPadIndex by remember { mutableIntStateOf(0) }
            var uploadPin by remember { mutableStateOf(prefs.getString("upload_pin", "").orEmpty()) }
            var sleeping by remember { mutableStateOf(false) }
            var manualSleepOverride by remember { mutableStateOf(false) }
            var showClock by remember { mutableStateOf(prefs.getBoolean("show_clock", true)) }
            var showDate by remember { mutableStateOf(prefs.getBoolean("show_date", false)) }
            var sleepTimerMinutes by remember { mutableIntStateOf(prefs.getInt("sleep_timer_minutes", 0)) }
            var sleepDeadline by remember { mutableLongStateOf(0L) }
            var weatherEnabled by remember { mutableStateOf(prefs.getBoolean("show_weather", false)) }
            var glassOverlay by remember { mutableStateOf(prefs.getBoolean("glass_overlay", false)) }
            var weatherCountry by remember { mutableStateOf(prefs.getString("weather_country", "DE") ?: "DE") }
            var weatherPostal by remember { mutableStateOf(prefs.getString("weather_postal", "") ?: "") }
            var weatherLabel by remember { mutableStateOf("") }
            var scheduleEnabled by remember { mutableStateOf(prefs.getBoolean("schedule_enabled", false)) }
            var scheduleWeekdaysOnly by remember { mutableStateOf(prefs.getBoolean("schedule_weekdays_only", true)) }
            var scheduleStart by remember { mutableStateOf(prefs.getString("schedule_start", "16:00") ?: "16:00") }
            var scheduleEnd by remember { mutableStateOf(prefs.getString("schedule_end", "21:00") ?: "21:00") }
            val focusManager = LocalFocusManager.current
            val photoListState = rememberLazyListState()
            val uiScope = rememberCoroutineScope()
            val settingsFocusRequester = remember { FocusRequester() }
            val localSourceFocusRequester = remember { FocusRequester() }
            val uploadSourceFocusRequester = remember { FocusRequester() }
            val sortFocusRequester = remember { FocusRequester() }
            val playFocusRequester = remember { FocusRequester() }
            val clearPhotosFocusRequester = remember { FocusRequester() }
            val updateCheckFocusRequester = remember { FocusRequester() }
            var playButtonFocused by remember { mutableStateOf(false) }
            var sortButtonFocused by remember { mutableStateOf(false) }
            var settingsButtonFocused by remember { mutableStateOf(false) }
            var updateCheckFocused by remember { mutableStateOf(false) }
            val uploadAlbums = uploadedPhotos.map { it.album }.distinct().sorted()
            val selectedUploads = uploadedPhotos
                .filter { selectedUploadAlbum == "*" || it.album == selectedUploadAlbum }
                .let { if (newestUploadsFirst) it.sortedByDescending(UploadedPhoto::modifiedAt) else it.sortedBy(UploadedPhoto::modifiedAt) }
            val images = remember(localPhotos, selectedUploads, selectedUploadAlbum) {
                if (selectedUploadAlbum != "*") selectedUploads.map { "file:${it.path}" }
                else (localPhotos.map { "uri:$it" } + selectedUploads.map { "file:${it.path}" }).distinct()
            }
            val portraitUi = displayRotation == 90 || displayRotation == 270
            LaunchedEffect(images.size) {
                focusedPhotoIndex = focusedPhotoIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
                if (images.isEmpty()) photoManagerTarget = "clear"
            }
            LaunchedEffect(uploadAlbums, selectedUploadAlbum) {
                focusedAlbumIndex = if (selectedUploadAlbum == "*") 0 else (uploadAlbums.indexOf(selectedUploadAlbum) + 1).coerceAtLeast(0)
            }
            LaunchedEffect(weatherEnabled, weatherCountry, weatherPostal) {
                weatherLabel = ""
                if (weatherEnabled && weatherCountry.matches(Regex("[A-Z]{2}")) && weatherPostal.trim().length >= 3) {
                    delay(750)
                    weatherLabel = withContext(Dispatchers.IO) { fetchWeather(weatherCountry, weatherPostal.trim()) }.orEmpty()
                }
            }
            LaunchedEffect(scheduleEnabled, scheduleWeekdaysOnly, scheduleStart, scheduleEnd, images.size, showSettings) {
                if (scheduleEnabled && !showSettings) {
                    while (true) {
                        if (!manualSleepOverride) {
                            val active = isScheduleActive(scheduleWeekdaysOnly, scheduleStart, scheduleEnd, System.currentTimeMillis())
                            if (isValidScheduleTime(scheduleStart) && isValidScheduleTime(scheduleEnd)) if (active) {
                                sleeping = false
                                if (images.isNotEmpty() && (!playing || paused)) { playing = true; paused = false }
                            } else {
                                playing = false
                                paused = false
                                sleeping = true
                            }
                        }
                        delay(30_000)
                    }
                }
            }

            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = SurfaceCard, onBackground = Color.White, onSurface = Color.White)) {
                fun focusMainNode(node: Int) {
                    mainFocusNode = node
                    if (node in 1..7) focusRestoreNode = node
                    runCatching {
                        when (node) {
                            0 -> settingsFocusRequester.requestFocus()
                            1 -> localSourceFocusRequester.requestFocus()
                            2 -> uploadSourceFocusRequester.requestFocus()
                            4 -> sortFocusRequester.requestFocus()
                            5 -> {
                                photoManagerTarget = if (localPhotos.isNotEmpty()) "clear" else "preview"
                                if (localPhotos.isNotEmpty()) clearPhotosFocusRequester.requestFocus()
                            }
                            6 -> playFocusRequester.requestFocus()
                            7 -> updateCheckFocusRequester.requestFocus()
                        }
                    }
                }
                fun focusPhoto(index: Int) {
                    focusedPhotoIndex = index.coerceIn(0, (images.size - 1).coerceAtLeast(0))
                    uiScope.launch { runCatching { photoListState.animateScrollToItem(focusedPhotoIndex) } }
                }
                fun navigateMainFocus(code: Int, from: Int = mainFocusNode): Boolean {
                    if (code !in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) return false
                    val photoNode = if (images.isNotEmpty()) 5 else 7
                    val albumNode = if (uploadAlbums.isNotEmpty()) 3 else photoNode
                    val next = if (portraitUi) when (from) {
                        0 -> if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else null
                        1 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 0; KeyEvent.KEYCODE_DPAD_DOWN -> 2; else -> null }
                        2 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 1; KeyEvent.KEYCODE_DPAD_DOWN -> albumNode; else -> null }
                        3 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 2; KeyEvent.KEYCODE_DPAD_DOWN -> 4; else -> null }
                        4 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 3; KeyEvent.KEYCODE_DPAD_DOWN -> photoNode; else -> null }
                        5 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> if (uploadAlbums.isNotEmpty()) 4 else 2; KeyEvent.KEYCODE_DPAD_DOWN -> 6; else -> null }
                        6 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 5; KeyEvent.KEYCODE_DPAD_DOWN -> 7; else -> null }
                        7 -> if (code == KeyEvent.KEYCODE_DPAD_UP) if (images.isNotEmpty()) 6 else if (uploadAlbums.isNotEmpty()) 4 else 2 else null
                        else -> null
                    } else when (from) {
                        0 -> if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else null
                        1 -> when (code) { KeyEvent.KEYCODE_DPAD_RIGHT -> 2; KeyEvent.KEYCODE_DPAD_UP -> 0; KeyEvent.KEYCODE_DPAD_DOWN -> albumNode; else -> null }
                        2 -> when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> 1; KeyEvent.KEYCODE_DPAD_UP -> 0; KeyEvent.KEYCODE_DPAD_DOWN -> albumNode; else -> null }
                        3 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 2; KeyEvent.KEYCODE_DPAD_DOWN -> 4; else -> null }
                        4 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 3; KeyEvent.KEYCODE_DPAD_DOWN -> photoNode; else -> null }
                        5 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 4; KeyEvent.KEYCODE_DPAD_DOWN -> 6; else -> null }
                        6 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 5; KeyEvent.KEYCODE_DPAD_DOWN -> 7; else -> null }
                        7 -> if (code == KeyEvent.KEYCODE_DPAD_UP) if (images.isNotEmpty()) 6 else if (uploadAlbums.isNotEmpty()) 4 else 2 else null
                        else -> null
                    }
                    if (next != null) focusMainNode(next)
                    return true
                }
                fun removePhoto(model: String) {
                    if (model.startsWith("uri:")) {
                        val uri = Uri.parse(model.removePrefix("uri:"))
                        localPhotoUris = localPhotoUris.filterNot { it == uri.toString() }
                        prefs.edit().putStringSet("local_photos", localPhotoUris.toSet()).apply()
                        runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    } else if (model.startsWith("file:")) {
                        File(model.removePrefix("file:")).delete()
                        refreshUploadedPhotos()
                    }
                    if (index >= images.size - 1) index = (images.size - 2).coerceAtLeast(0)
                }
                DisposableEffect(playing, paused, sleeping, images.size, orientationChosen, displayRotation, setupConfirmSelected, sourceCardFocus, focusedSettingsNode, mainFocusNode, focusedAlbumIndex, uploadAlbums, selectedUploadAlbum, newestUploadsFirst, pauseMenuIndex, showSettings, message, uploadUrl, showPinSetup, pinEntry, pinPadIndex) {
                    remoteKeyHandler = when {
                        sleeping -> { code ->
                            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_SPACE || code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) {
                                sleeping = false
                                manualSleepOverride = false
                                sleepDeadline = 0L
                                if (images.isNotEmpty()) { playing = true; paused = true; pauseMenuIndex = 0 }
                                true
                            } else false
                        }
                        uploadUrl != null -> { code ->
                            when (code) {
                                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> { stopPhotoUpload(); true }
                                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> true
                                else -> false
                            }
                        }
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
                        message != null -> { code ->
                            if (message == null) {
                                navigateMainFocus(code, focusRestoreNode)
                            } else when (code) {
                                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> true
                                KeyEvent.KEYCODE_BACK -> { message = null; true }
                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> { message = null; true }
                                else -> false
                            }
                        }
                        showSettings -> settingsKeys@{ code ->
                            when (code) {
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    if (focusedSettingsNode in listOf(8, 9, 14, 15)) return@settingsKeys false
                                    if (focusedSettingsNode == 17) { imagePaddingDp = cycleValue(imagePaddingDp, listOf(0, 4, 8, 12, 16, 24, 32), 1); prefs.edit().putInt("image_padding_dp", imagePaddingDp).apply(); return@settingsKeys true }
                                    when (focusedSettingsNode) {
                                        0 -> focusedSettingsNode = 1
                                        3 -> seconds = (seconds + 1).coerceAtMost(60)
                                        4 -> autoStartOnBoot = !autoStartOnBoot
                                        10 -> transitionStyle = cycleValue(transitionStyle, listOf("fade", "crossfade", "cut", "slide", "zoom"), 1)
                                        11 -> imageScaleMode = cycleValue(imageScaleMode, listOf("fit", "fill", "stretch"), 1)
                                        16 -> { glassOverlay = !glassOverlay; prefs.edit().putBoolean("glass_overlay", glassOverlay).apply() }
                                    }
                                    if (focusedSettingsNode == 3) prefs.edit().putInt("seconds", seconds).apply()
                                    if (focusedSettingsNode == 4) prefs.edit().putBoolean("auto_start_on_boot", autoStartOnBoot).apply()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    if (focusedSettingsNode in listOf(8, 9, 14, 15)) return@settingsKeys false
                                    if (focusedSettingsNode == 17) { imagePaddingDp = cycleValue(imagePaddingDp, listOf(0, 4, 8, 12, 16, 24, 32), -1); prefs.edit().putInt("image_padding_dp", imagePaddingDp).apply(); return@settingsKeys true }
                                    when (focusedSettingsNode) {
                                        1 -> focusedSettingsNode = 0
                                        3 -> seconds = (seconds - 1).coerceAtLeast(3)
                                        4 -> autoStartOnBoot = !autoStartOnBoot
                                        10 -> transitionStyle = cycleValue(transitionStyle, listOf("fade", "crossfade", "cut", "slide", "zoom"), -1)
                                        11 -> imageScaleMode = cycleValue(imageScaleMode, listOf("fit", "fill", "stretch"), -1)
                                        16 -> { glassOverlay = !glassOverlay; prefs.edit().putBoolean("glass_overlay", glassOverlay).apply() }
                                    }
                                    if (focusedSettingsNode == 3) prefs.edit().putInt("seconds", seconds).apply()
                                    if (focusedSettingsNode == 4) prefs.edit().putBoolean("auto_start_on_boot", autoStartOnBoot).apply()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    if (focusedSettingsNode == 7) { focusedSettingsNode = 8; return@settingsKeys false }
                                    if (focusedSettingsNode == 8) { focusedSettingsNode = 9; return@settingsKeys false }
                                    if (focusedSettingsNode == 9) { focusedSettingsNode = 10; return@settingsKeys true }
                                    if (focusedSettingsNode == 13) { focusedSettingsNode = 14; return@settingsKeys false }
                                    if (focusedSettingsNode == 14) { focusedSettingsNode = 15; return@settingsKeys false }
                                    if (focusedSettingsNode == 15) { focusedSettingsNode = 16; return@settingsKeys true }
                                    if (focusedSettingsNode == 16) { focusedSettingsNode = 17; return@settingsKeys true }
                                    if (focusedSettingsNode == 17) return@settingsKeys true
                                    focusedSettingsNode = if (portraitUi) {
                                        (focusedSettingsNode + 1).coerceAtMost(17)
                                    } else when (focusedSettingsNode) {
                                        0, 1 -> 2
                                        2 -> 3
                                        3 -> 4
                                        else -> (focusedSettingsNode + 1).coerceAtMost(17)
                                    }
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    if (focusedSettingsNode == 9) { focusedSettingsNode = 8; return@settingsKeys false }
                                    if (focusedSettingsNode == 8) { focusedSettingsNode = 7; return@settingsKeys true }
                                    if (focusedSettingsNode == 10) { focusedSettingsNode = 9; return@settingsKeys true }
                                    if (focusedSettingsNode == 11) { focusedSettingsNode = 10; return@settingsKeys true }
                                    if (focusedSettingsNode == 17) { focusedSettingsNode = 16; return@settingsKeys true }
                                    if (focusedSettingsNode == 16) { focusedSettingsNode = 15; return@settingsKeys true }
                                    if (focusedSettingsNode == 15) { focusedSettingsNode = 14; return@settingsKeys false }
                                    if (focusedSettingsNode == 14) { focusedSettingsNode = 13; return@settingsKeys false }
                                    if (focusedSettingsNode == 13) { focusedSettingsNode = 12; return@settingsKeys true }
                                    focusedSettingsNode = if (portraitUi) {
                                        (focusedSettingsNode - 1).coerceAtLeast(0)
                                    } else when (focusedSettingsNode) {
                                        2 -> 0
                                        3 -> 2
                                        4 -> 3
                                        else -> (focusedSettingsNode - 1).coerceAtLeast(0)
                                    }
                                    true
                                }
                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                                    if (focusedSettingsNode in listOf(8, 9, 14, 15)) return@settingsKeys false
                                    when (focusedSettingsNode) {
                                        0 -> displayRotation = nextDisplayRotation(displayRotation)
                                        1 -> { prefs.edit().putInt("display_rotation", displayRotation).apply(); showSettings = false }
                                        2 -> { panZoom = !panZoom; prefs.edit().putBoolean("pan_zoom", panZoom).apply() }
                                        3 -> { seconds = if (seconds >= 60) 3 else seconds + 1; prefs.edit().putInt("seconds", seconds).apply() }
                                        4 -> { autoStartOnBoot = !autoStartOnBoot; prefs.edit().putBoolean("auto_start_on_boot", autoStartOnBoot).apply() }
                                        5 -> { showClock = !showClock; prefs.edit().putBoolean("show_clock", showClock).apply() }
                                        6 -> { showDate = !showDate; prefs.edit().putBoolean("show_date", showDate).apply() }
                                        7 -> { weatherEnabled = !weatherEnabled; prefs.edit().putBoolean("show_weather", weatherEnabled).apply() }
                                        10 -> transitionStyle = cycleValue(transitionStyle, listOf("fade", "crossfade", "cut", "slide", "zoom"), 1)
                                        11 -> imageScaleMode = cycleValue(imageScaleMode, listOf("fit", "fill", "stretch"), 1)
                                        12 -> { scheduleEnabled = !scheduleEnabled; prefs.edit().putBoolean("schedule_enabled", scheduleEnabled).apply() }
                                        13 -> { scheduleWeekdaysOnly = !scheduleWeekdaysOnly; prefs.edit().putBoolean("schedule_weekdays_only", scheduleWeekdaysOnly).apply() }
                                        16 -> { glassOverlay = !glassOverlay; prefs.edit().putBoolean("glass_overlay", glassOverlay).apply() }
                                        17 -> { imagePaddingDp = cycleValue(imagePaddingDp, listOf(0, 4, 8, 12, 16, 24, 32), 1); prefs.edit().putInt("image_padding_dp", imagePaddingDp).apply() }
                                    }
                                    prefs.edit().putString("transition_style", transitionStyle).putString("image_scale_mode", imageScaleMode).apply()
                                    true
                                }
                                else -> false
                            }
                        }
                        orientationChosen && !playing && mainFocusNode in 0..7 -> mainKeys@{ code ->
                            when (code) {
                                KeyEvent.KEYCODE_DPAD_UP -> if (mainFocusNode == 5) {
                                    when (photoManagerTarget) {
                                        "delete" -> { photoManagerTarget = "preview"; true }
                                        "preview" -> if (localPhotos.isNotEmpty()) { photoManagerTarget = "clear"; clearPhotosFocusRequester.requestFocus(); true } else navigateMainFocus(code)
                                        else -> navigateMainFocus(code)
                                    }
                                } else navigateMainFocus(code)
                                KeyEvent.KEYCODE_DPAD_DOWN -> if (mainFocusNode == 5) {
                                    when (photoManagerTarget) {
                                        "clear" -> if (images.isNotEmpty()) { photoManagerTarget = "preview"; focusPhoto(focusedPhotoIndex); true } else { focusMainNode(6); true }
                                        "preview" -> { photoManagerTarget = "delete"; true }
                                        else -> true
                                    }
                                } else navigateMainFocus(code)
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    if (mainFocusNode == 5) {
                                        if (photoManagerTarget != "clear" && images.isNotEmpty()) {
                                            val direction = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                                            focusPhoto(focusedPhotoIndex + direction)
                                        }
                                        return@mainKeys true
                                    }
                                    when (mainFocusNode) {
                                        3 -> focusedAlbumIndex = (focusedAlbumIndex + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1).coerceIn(0, uploadAlbums.size)
                                        4 -> { newestUploadsFirst = !newestUploadsFirst; prefs.edit().putBoolean("newest_uploads_first", newestUploadsFirst).apply() }
                                        else -> navigateMainFocus(code)
                                    }
                                    true
                                }
                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                                    if (mainFocusNode == 5) {
                                        when (photoManagerTarget) {
                                            "clear" -> { clearPhotos(); focusMainNode(1) }
                                            "delete" -> {
                                                images.getOrNull(focusedPhotoIndex)?.let(::removePhoto)
                                                if (images.size > 1) {
                                                    focusedPhotoIndex = focusedPhotoIndex.coerceAtMost(images.size - 2)
                                                    photoManagerTarget = "preview"
                                                } else focusMainNode(1)
                                            }
                                            else -> index = focusedPhotoIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
                                        }
                                        return@mainKeys true
                                    }
                                    when (mainFocusNode) {
                                        0 -> { rotationBeforeSettings = displayRotation; focusedSettingsNode = 0; showSettings = true }
                                        1 -> photoChooser.launch(arrayOf("image/*"))
                                        2 -> startPhotoUpload()
                                        3 -> {
                                            selectedUploadAlbum = if (focusedAlbumIndex == 0) "*" else uploadAlbums.getOrNull(focusedAlbumIndex - 1) ?: "*"
                                            prefs.edit().putString("selected_upload_album", selectedUploadAlbum).apply()
                                        }
                                        4 -> { newestUploadsFirst = !newestUploadsFirst; prefs.edit().putBoolean("newest_uploads_first", newestUploadsFirst).apply() }
                                        5 -> { /* Photo manager is handled above. */ }
                                        6 -> if (images.isNotEmpty()) { playing = true; paused = false } else message = "Füge zuerst Bilder hinzu."
                                        7 -> if (updateRelease != null) downloadOrInstallUpdate() else checkForUpdates(force = true)
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        playing && paused -> { code ->
                            when (code) {
                                KeyEvent.KEYCODE_BACK -> { playing = false; paused = false; true }
                                KeyEvent.KEYCODE_DPAD_UP -> { pauseMenuIndex = (pauseMenuIndex - 1).coerceAtLeast(0); true }
                                KeyEvent.KEYCODE_DPAD_DOWN -> { pauseMenuIndex = (pauseMenuIndex + 1).coerceAtMost(9); true }
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    val direction = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                                    when (pauseMenuIndex) {
                                        3 -> { seconds = (seconds + direction).coerceIn(3, 60); prefs.edit().putInt("seconds", seconds).apply() }
                                        4 -> { transitionStyle = cycleValue(transitionStyle, listOf("fade", "crossfade", "cut", "slide", "zoom"), direction); prefs.edit().putString("transition_style", transitionStyle).apply() }
                                        5 -> { imageScaleMode = cycleValue(imageScaleMode, listOf("fit", "fill", "stretch"), direction); prefs.edit().putString("image_scale_mode", imageScaleMode).apply() }
                                        6 -> { focusedAlbumIndex = (focusedAlbumIndex + direction).coerceIn(0, uploadAlbums.size); selectedUploadAlbum = if (focusedAlbumIndex == 0) "*" else uploadAlbums.getOrNull(focusedAlbumIndex - 1) ?: "*"; prefs.edit().putString("selected_upload_album", selectedUploadAlbum).apply() }
                                        7 -> { sleepTimerMinutes = cycleValue(sleepTimerMinutes, listOf(0, 15, 30, 60), direction); prefs.edit().putInt("sleep_timer_minutes", sleepTimerMinutes).apply(); sleepDeadline = if (sleepTimerMinutes == 0) 0L else System.currentTimeMillis() + sleepTimerMinutes * 60_000L }
                                    }
                                    true
                                }
                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                                    when (pauseMenuIndex) {
                                        0 -> paused = false
                                        1 -> startPhotoUpload()
                                        2 -> photoChooser.launch(arrayOf("image/*"))
                                        3 -> seconds = if (seconds >= 60) 3 else seconds + 1
                                        4 -> transitionStyle = cycleValue(transitionStyle, listOf("fade", "crossfade", "cut", "slide", "zoom"), 1)
                                        5 -> imageScaleMode = cycleValue(imageScaleMode, listOf("fit", "fill", "stretch"), 1)
                                        6 -> { focusedAlbumIndex = (focusedAlbumIndex + 1).coerceIn(0, uploadAlbums.size); selectedUploadAlbum = if (focusedAlbumIndex == 0) "*" else uploadAlbums.getOrNull(focusedAlbumIndex - 1) ?: "*" }
                                        7 -> { sleepTimerMinutes = cycleValue(sleepTimerMinutes, listOf(0, 15, 30, 60), 1); prefs.edit().putInt("sleep_timer_minutes", sleepTimerMinutes).apply(); sleepDeadline = if (sleepTimerMinutes == 0) 0L else System.currentTimeMillis() + sleepTimerMinutes * 60_000L }
                                        8 -> { sleeping = true; manualSleepOverride = true; playing = false; paused = false; sleepDeadline = 0L }
                                        9 -> { playing = false; paused = false; sleepDeadline = 0L }
                                    }
                                    prefs.edit().putInt("seconds", seconds).putString("transition_style", transitionStyle).putString("image_scale_mode", imageScaleMode).putString("selected_upload_album", selectedUploadAlbum).apply()
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
                                    paused = true
                                    pauseMenuIndex = 0
                                    true
                                }
                                else -> false
                            }
                        }
                        else -> null
                    }
                    onDispose { remoteKeyHandler = null }
                }
                LaunchedEffect(images.size, playing, paused, seconds, shuffle) {
                    if (playing && !paused && images.size > 1) {
                        while (true) {
                            delay(seconds * 1000L)
                            index = if (shuffle) {
                                if (images.size <= 1) 0 else (images.indices.filter { it != index }.random())
                            } else (index + 1) % images.size
                        }
                    }
                }
                LaunchedEffect(playing, paused, sleeping, sleepTimerMinutes, sleepDeadline) {
                    if (playing && !paused && !sleeping && sleepTimerMinutes > 0) {
                        val deadline = if (sleepDeadline > 0L) sleepDeadline else System.currentTimeMillis() + sleepTimerMinutes * 60_000L
                        sleepDeadline = deadline
                        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                        delay(remaining)
                        sleeping = true
                        manualSleepOverride = true
                        playing = false
                        paused = false
                        sleepDeadline = 0L
                    }
                }
                DisposableEffect(playing, sleeping) {
                    if (playing || sleeping && showClock || scheduleEnabled) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                BackHandler(playing) { playing = false; paused = false }
                BackHandler(showSettings) {
                    displayRotation = rotationBeforeSettings
                    showSettings = false
                }
                BackHandler(uploadUrl != null) { stopPhotoUpload() }
                BackHandler(!orientationChosen) {
                    displayRotation = 0
                    prefs.edit().putInt("display_rotation", 0).apply()
                    orientationChosen = true
                }
                BackHandler(message != null) { message = null }
                BackHandler(showPinSetup) { showPinSetup = false; pinEntry = "" }
                LaunchedEffect(showSettings, message, orientationChosen, uploadUrl, showPinSetup, playing, paused) {
                    val modalVisible = showSettings || message != null || !orientationChosen || uploadUrl != null || showPinSetup
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
                    val modalVisible = showSettings || message != null || !orientationChosen || uploadUrl != null || showPinSetup
                    if (sleeping) {
                        SleepScreen(showClock, showDate, weatherEnabled, weatherLabel, glassOverlay)
                    } else if (playing && images.isNotEmpty()) {
                        PlaybackView(images, index, displayRotation, panZoom, seconds, playing, paused, imageScaleMode, imagePaddingDp, playbackControlsPulse, transitionStyle, pauseMenuIndex, sleepTimerMinutes,
                            showClock, showDate, weatherEnabled, weatherLabel, glassOverlay,
                            onPause = { paused = true; pauseMenuIndex = 0 },
                            onNext = { index = (index + 1) % images.size }, onPrevious = { index = (index - 1 + images.size) % images.size },
                            onResume = { paused = false }, onAddImages = { startPhotoUpload() }, onAddFiles = { photoChooser.launch(arrayOf("image/*")) },
                            onCycleSpeed = { direction -> seconds = (seconds + direction).coerceIn(3, 60); prefs.edit().putInt("seconds", seconds).apply() },
                            onCycleTransition = { direction -> transitionStyle = cycleValue(transitionStyle, listOf("fade", "crossfade", "cut", "slide", "zoom"), direction); prefs.edit().putString("transition_style", transitionStyle).apply() },
                            onCycleScale = { direction -> imageScaleMode = cycleValue(imageScaleMode, listOf("fit", "fill", "stretch"), direction); prefs.edit().putString("image_scale_mode", imageScaleMode).apply() },
                            onCycleSleepTimer = { direction -> sleepTimerMinutes = cycleValue(sleepTimerMinutes, listOf(0, 15, 30, 60), direction); prefs.edit().putInt("sleep_timer_minutes", sleepTimerMinutes).apply(); sleepDeadline = if (sleepTimerMinutes == 0) 0L else System.currentTimeMillis() + sleepTimerMinutes * 60_000L },
                            onSleepNow = { sleeping = true; manualSleepOverride = true; playing = false; paused = false; sleepDeadline = 0L },
                            onExit = { playing = false; paused = false; sleepDeadline = 0L })
                    } else if (modalVisible) {
                        Spacer(Modifier.fillMaxSize().background(Ink))
                    } else {
                        Column(
                            Modifier.fillMaxSize().focusProperties { canFocus = !modalVisible }.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
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
                            Text("BILDER HINZUFÜGEN", color = Color(0xFF9DA8B6), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(10.dp))
                            if (portraitUi) {
                                Column(Modifier.fillMaxWidth().focusGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SourceCard("Dateien", "Fotos von diesem Gerät", Icons.Default.FolderOpen, Modifier.fillMaxWidth().focusRequester(localSourceFocusRequester), count = localPhotos.size, onClick = { photoChooser.launch(arrayOf("image/*")) }, onFocusChange = { if (it) { sourceCardFocus = 0; mainFocusNode = 1; focusRestoreNode = 1 } else if (sourceCardFocus == 0) sourceCardFocus = -1 })
                                    SourceCard("Handy-Upload", "Bilder per QR im WLAN", Icons.Default.QrCode2, Modifier.fillMaxWidth().focusRequester(uploadSourceFocusRequester), count = uploadedPhotos.size, onClick = { startPhotoUpload() }, onFocusChange = { if (it) { sourceCardFocus = 1; mainFocusNode = 2; focusRestoreNode = 2 } else if (sourceCardFocus == 1) sourceCardFocus = -1 })
                                }
                            } else {
                                Row(Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SourceCard("Dateien", "Fotos von diesem Gerät", Icons.Default.FolderOpen, Modifier.weight(1f).focusRequester(localSourceFocusRequester), count = localPhotos.size, onClick = { photoChooser.launch(arrayOf("image/*")) }, onFocusChange = { if (it) { sourceCardFocus = 0; mainFocusNode = 1; focusRestoreNode = 1 } else if (sourceCardFocus == 0) sourceCardFocus = -1 })
                                    SourceCard("Handy-Upload", "Bilder per QR im WLAN", Icons.Default.QrCode2, Modifier.weight(1f).focusRequester(uploadSourceFocusRequester), count = uploadedPhotos.size, onClick = { startPhotoUpload() }, onFocusChange = { if (it) { sourceCardFocus = 1; mainFocusNode = 2; focusRestoreNode = 2 } else if (sourceCardFocus == 1) sourceCardFocus = -1 })
                                }
                            }
                            if (uploadAlbums.isNotEmpty()) {
                                Spacer(Modifier.height(14.dp))
                                Text("UPLOAD-ALBEN", color = Color(0xFF9DA8B6), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                                LazyRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item {
                                        FilterChip(modifier = if (mainFocusNode == 3 && focusedAlbumIndex == 0) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(50)) else Modifier, selected = selectedUploadAlbum == "*", onClick = {
                                            selectedUploadAlbum = "*"; prefs.edit().putString("selected_upload_album", "*").apply()
                                        }, label = { Text("Alle Bilder") })
                                    }
                                    itemsIndexed(uploadAlbums) { albumIndex, album ->
                                        val itIndex = albumIndex + 1
                                        FilterChip(modifier = if (mainFocusNode == 3 && focusedAlbumIndex == itIndex) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(50)) else Modifier, selected = selectedUploadAlbum == album, onClick = {
                                            selectedUploadAlbum = album; prefs.edit().putString("selected_upload_album", album).apply()
                                        }, label = { Text(album) })
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (selectedUploadAlbum == "*") "Alle Bilder" else "Album: $selectedUploadAlbum", color = Color(0xFFB8C1CC), fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    TextButton(modifier = Modifier.focusRequester(sortFocusRequester).onFocusChanged { sortButtonFocused = it.isFocused; if (it.isFocused) mainFocusNode = 4 }.then(if (sortButtonFocused || mainFocusNode == 4) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(10.dp)) else Modifier), onClick = {
                                        newestUploadsFirst = !newestUploadsFirst
                                        prefs.edit().putBoolean("newest_uploads_first", newestUploadsFirst).apply()
                                    }) { Text(if (newestUploadsFirst) "Neueste zuerst" else "Älteste zuerst", color = Accent, fontSize = 12.sp) }
                                }
                            }
                                            Spacer(Modifier.height(24.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("DEINE PRÄSENTATION", color = Color(0xFF9DA8B6), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                    Text(if (images.isEmpty()) "Bilder hinzufügen, um zu starten" else "${images.size} Bilder bereit", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                }
                                if (localPhotos.isNotEmpty()) TextButton(
                                    modifier = Modifier.focusRequester(clearPhotosFocusRequester)
                                        .onFocusChanged { if (it.isFocused) { mainFocusNode = 5; photoManagerTarget = "clear" } }
                                        .then(if (mainFocusNode == 5 && photoManagerTarget == "clear") Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier),
                                    onClick = { clearPhotos(); focusMainNode(1) }
                                ) { Text("Dateiauswahl leeren", color = if (mainFocusNode == 5 && photoManagerTarget == "clear") Color.White else Color(0xFFADB7C4)) }
                            }
                            Spacer(Modifier.height(12.dp))
                            if (images.isEmpty()) EmptyState()
                            else {
                                LazyRow(state = photoListState, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                                    itemsIndexed(images) { i, model ->
                                        val previewFocused = mainFocusNode == 5 && focusedPhotoIndex == i && photoManagerTarget == "preview"
                                        val deleteFocused = mainFocusNode == 5 && focusedPhotoIndex == i && photoManagerTarget == "delete"
                                        Box(Modifier.size(142.dp, 104.dp)
                                            .border(if (previewFocused) 4.dp else 1.dp, if (previewFocused) RemoteFocus else Color(0x554D6175), RoundedCornerShape(12.dp))
                                            .clip(RoundedCornerShape(12.dp)).background(SurfaceCard)) {
                                            AsyncImage(model = model.removePrefix("uri:").removePrefix("file:"), contentDescription = "Vorschau Bild ${i + 1}", modifier = Modifier.fillMaxSize()
                                                .clickable { focusedPhotoIndex = i; photoManagerTarget = "preview"; mainFocusNode = 5; index = i }, contentScale = ContentScale.Crop)
                                            IconButton(onClick = {
                                                removePhoto(model)
                                                if (images.size > 1) { focusedPhotoIndex = focusedPhotoIndex.coerceAtMost(images.size - 2); photoManagerTarget = "preview" }
                                                else focusMainNode(1)
                                            }, modifier = Modifier.align(Alignment.TopEnd).size(38.dp)
                                                .then(if (deleteFocused) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(30.dp)) else Modifier)
                                                .background(if (deleteFocused) Color(0xFFB3261E) else Color(0xCC10141B), RoundedCornerShape(30.dp))) { Icon(Icons.Default.Delete, "Bild löschen", tint = Color.White, modifier = Modifier.size(20.dp)) }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(20.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(onClick = { playing = true; paused = false }, colors = ButtonDefaults.buttonColors(containerColor = if (playButtonFocused || mainFocusNode == 6) RemoteFocusSurface else Accent, contentColor = if (playButtonFocused || mainFocusNode == 6) Color.White else Ink), modifier = Modifier.height(54.dp).focusRequester(playFocusRequester).onFocusChanged { playButtonFocused = it.isFocused; if (it.isFocused) mainFocusNode = 6 }.then(if (playButtonFocused || mainFocusNode == 6) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(14.dp)) else Modifier)) {
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
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Text("Ausrichtung: ${displayRotation}° · Version: ${BuildConfig.VERSION_NAME}", color = Color(0xFF9AA6B4), fontSize = 12.sp)
                                TextButton(
                                    onClick = { checkForUpdates(force = true) },
                                    modifier = Modifier.focusRequester(updateCheckFocusRequester).onFocusChanged { updateCheckFocused = it.isFocused; if (it.isFocused) mainFocusNode = 7 else if (mainFocusNode == 7) mainFocusNode = -1 }
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
                    uploadUrl?.let { url -> UploadQrDialog(url, uploadedPhotos.size, uploadStatus, onClose = { stopPhotoUpload() }) }
                    message?.let { text -> InlineMessageDialog(text) { message = null } }
                    if (showSettings) {
                        SettingsDialog(
                            seconds, panZoom, displayRotation, focusedSettingsNode, portraitUi, autoStartOnBoot,
                            showClock, showDate, weatherEnabled, weatherCountry, weatherPostal, transitionStyle, imageScaleMode, glassOverlay, imagePaddingDp,
                            scheduleEnabled, scheduleWeekdaysOnly, scheduleStart, scheduleEnd,
                            onFocusedNode = { focusedSettingsNode = it },
                            onAutoStartOnBoot = { autoStartOnBoot = it; prefs.edit().putBoolean("auto_start_on_boot", it).apply() },
                            onShowClock = { showClock = it; prefs.edit().putBoolean("show_clock", it).apply() },
                            onShowDate = { showDate = it; prefs.edit().putBoolean("show_date", it).apply() },
                            onShowWeather = { weatherEnabled = it; prefs.edit().putBoolean("show_weather", it).apply() },
                            onGlassOverlay = { glassOverlay = it; prefs.edit().putBoolean("glass_overlay", it).apply() },
                            onWeatherCountry = { weatherCountry = it.uppercase().take(2); prefs.edit().putString("weather_country", weatherCountry).apply() },
                            onWeatherPostal = { weatherPostal = it; prefs.edit().putString("weather_postal", it).apply() },
                            onTransitionStyle = { transitionStyle = it; prefs.edit().putString("transition_style", it).apply() },
                            onImageScaleMode = { imageScaleMode = it; prefs.edit().putString("image_scale_mode", it).apply() },
                            onImagePadding = { imagePaddingDp = it; prefs.edit().putInt("image_padding_dp", it).apply() },
                            onScheduleEnabled = { scheduleEnabled = it; prefs.edit().putBoolean("schedule_enabled", it).apply() },
                            onScheduleDays = { scheduleWeekdaysOnly = it; prefs.edit().putBoolean("schedule_weekdays_only", it).apply() },
                            onScheduleStart = { scheduleStart = it; prefs.edit().putString("schedule_start", it).apply() },
                            onScheduleEnd = { scheduleEnd = it; prefs.edit().putString("schedule_end", it).apply() },
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

    override fun onDestroy() {
        uploadServer?.stop()
        uploadServer = null
        super.onDestroy()
    }

    private fun refreshUploadedPhotos() {
        uploadedPhotos = LocalPhotoUploadServer.scan(filesDir)
    }

    private fun startPhotoUpload() {
        if (uploadUrl != null) return
        val server = LocalPhotoUploadServer(filesDir) { _, _ ->
            runOnUiThread {
                refreshUploadedPhotos()
                uploadStatus = "${uploadedPhotos.size} Bilder empfangen. Du kannst weitere senden."
            }
        }
        try {
            uploadServer = server
            uploadStatus = "Warte auf Bilder von deinem Handy …"
            uploadUrl = server.start()
        } catch (error: Exception) {
            server.stop()
            uploadServer = null
            message = error.localizedMessage ?: "Der Handy-Upload konnte nicht gestartet werden."
        }
    }

    private fun stopPhotoUpload() {
        uploadServer?.stop()
        uploadServer = null
        uploadUrl = null
        uploadStatus = ""
        refreshUploadedPhotos()
    }

    private fun checkForUpdates(force: Boolean = false) {
        lifecycleScope.launch {
            val prefs = getSharedPreferences("carousel", MODE_PRIVATE)
            val cachedTag = prefs.getString("latest_release_tag", null)
            if (cachedTag != null && ReleaseUpdater.isNewer(cachedTag, BuildConfig.VERSION_NAME)) {
                updateRelease = AppRelease(cachedTag, "https://github.com/weisser-dev/fire-tv-diashow/releases/download/$cachedTag/app-release.apk")
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
                updateStatus = "Erlaube Stillframe die Installation unbekannter Apps und tippe erneut auf Installieren."
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

    private fun clearPhotos() {
        val photos = localPhotoUris
        localPhotoUris = emptyList()
        getSharedPreferences("carousel", MODE_PRIVATE).edit().remove("local_photos").apply()
        photos.forEach { value ->
            runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
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
        Text("Wähle Dateien am Fernseher oder sende Bilder vom Handy per QR-Code.", color = Color(0xFFADB7C4), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun PlaybackView(
    images: List<String>, index: Int, rotation: Int, panZoom: Boolean, seconds: Int, playing: Boolean,
    paused: Boolean, imageScaleMode: String, imagePaddingDp: Int, controlsPulse: Int, transitionStyle: String, pauseMenuIndex: Int, sleepTimerMinutes: Int,
    showClock: Boolean, showDate: Boolean, showWeather: Boolean, weatherLabel: String, glassOverlay: Boolean,
    onPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onResume: () -> Unit,
    onAddImages: () -> Unit, onAddFiles: () -> Unit, onCycleSpeed: (Int) -> Unit,
    onCycleTransition: (Int) -> Unit, onCycleScale: (Int) -> Unit, onCycleSleepTimer: (Int) -> Unit,
    onSleepNow: () -> Unit, onExit: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val model = images[index].removePrefix("uri:").removePrefix("file:")
        var sourceLandscape by remember(model) { mutableStateOf(false) }
        val panX = remember { Animatable(0f) }
        val density = LocalDensity.current
        val panEnabled = panZoom && sourceLandscape && (rotation == 90 || rotation == 270)
        LaunchedEffect(index, panEnabled, playing, paused, seconds, maxWidth) {
            if (panEnabled && playing && !paused) {
                val travel = with(density) { maxWidth.toPx() * 0.06f }
                val start = if (index % 2 == 0) -travel else travel
                panX.snapTo(start)
                panX.animateTo(-start, tween(maxOf(seconds * 3, 15) * 1000, easing = LinearEasing))
            } else panX.snapTo(0f)
        }
        var controlsVisible by remember { mutableStateOf(true) }
        LaunchedEffect(index, paused, controlsPulse) {
            controlsVisible = true
            if (!paused) { delay(2800); controlsVisible = false }
        }
        val transitionMs = when (transitionStyle) { "cut" -> 0; "crossfade" -> 1100; else -> 1800 }
        AnimatedContent(targetState = model, transitionSpec = {
            when (transitionStyle) {
                "cut" -> androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                "slide" -> (slideInHorizontally(tween(700)) { it / 9 } + fadeIn(tween(700))) togetherWith (slideOutHorizontally(tween(700)) { -it / 9 } + fadeOut(tween(700)))
                "zoom" -> (scaleIn(tween(900), initialScale = 0.96f) + fadeIn(tween(900))) togetherWith fadeOut(tween(900))
                else -> fadeIn(tween(transitionMs)) togetherWith fadeOut(tween(transitionMs))
            }
        }, label = "photo-transition") { photo ->
            AsyncImage(
                model = photo,
                contentDescription = "Slideshow Foto",
                onSuccess = { state -> sourceLandscape = state.result.drawable.intrinsicWidth > state.result.drawable.intrinsicHeight },
                modifier = Modifier.fillMaxSize().then(if (panEnabled) Modifier.clipToBounds() else Modifier.padding(imagePaddingDp.dp))
                    .graphicsLayer {
                        if (panEnabled) {
                            scaleX = 1.08f
                            scaleY = 1.08f
                            translationX = panX.value
                        }
                    },
                contentScale = if (panEnabled) ContentScale.Crop else when (imageScaleMode) {
                    "fill" -> ContentScale.Crop
                    "stretch" -> ContentScale.FillBounds
                    else -> ContentScale.Fit
                }
            )
        }
        if (showClock || showDate || showWeather && weatherLabel.isNotBlank()) {
            TimeWeatherOverlay(showClock, showDate, showWeather, weatherLabel, glassOverlay)
        }
        if (paused) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                Surface(Modifier.fillMaxWidth().widthIn(max = 580.dp).heightIn(max = 920.dp), color = SurfaceCard, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Diashow pausiert", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        Text("Mittlere Taste: Auswahl öffnen", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp, bottom = 16.dp))
                        val options = listOf(
                            "Fortsetzen" to "Weiterblättern",
                            "Bilder per QR hinzufügen" to "Handy-Upload im WLAN",
                            "Dateien hinzufügen" to "Bilder auf diesem Gerät wählen",
                            "Bilddauer · ${seconds}s" to "Links/rechts ändert die Dauer",
                            "Übergang · ${transitionName(transitionStyle)}" to "Links/rechts wechselt den Übergang",
                            "Bilddarstellung · ${imageScaleName(imageScaleMode)}" to "Einpassen, Füllen oder Strecken",
                            "Album · ${if (selectedAlbumLabel(images, index)) "Auswahl" else "Alle Bilder"}" to "Album im Hauptmenü wechseln",
                            "Schlaf-Timer · ${if (sleepTimerMinutes == 0) "Aus" else "$sleepTimerMinutes Min."}" to "Links/rechts stellt 15, 30 oder 60 Minuten ein",
                            "Jetzt schlafen" to "Bildschirm wird schwarz; OK oder Zurück weckt auf",
                            "Zur Übersicht" to "Diashow beenden"
                        )
                        options.forEachIndexed { optionIndex, option ->
                            val selected = pauseMenuIndex == optionIndex
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .then(if (selected) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(14.dp)) else Modifier),
                                color = if (selected) RemoteFocusSurface else Color(0xFF222B35),
                                shape = RoundedCornerShape(14.dp),
                                onClick = {
                                    when (optionIndex) {
                                        0 -> onResume()
                                        1 -> onAddImages()
                                        2 -> onAddFiles()
                                        3 -> onCycleSpeed(1)
                                        4 -> onCycleTransition(1)
                                        5 -> onCycleScale(1)
                                        7 -> onCycleSleepTimer(1)
                                        8 -> onSleepNow()
                                        9 -> onExit()
                                    }
                                }
                            ) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    Text(option.first, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                    Text(option.second, color = Color(0xFFB8C1CC), fontSize = 12.sp)
                                }
                            }
                        }
                        Text("Pfeile: Auswahl · Links/rechts: Wert ändern · Zurück: Übersicht", color = Color(0xFF9DA8B6), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        } else if (controlsVisible) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clip(RoundedCornerShape(24.dp)).background(Color(0x990E1116)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Vorheriges Bild", tint = Color.White) }
                IconButton(onClick = onPause) { Icon(Icons.Default.Pause, "Pause", tint = Accent) }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Nächstes Bild", tint = Color.White) }
                Text("${index + 1} / ${images.size}", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

@Composable
private fun TimeWeatherOverlay(showClock: Boolean, showDate: Boolean, showWeather: Boolean, weatherLabel: String, glassOverlay: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    val date = remember(now) { java.text.SimpleDateFormat("EEEE, d. MMMM", java.util.Locale.GERMANY).format(java.util.Date(now)) }
    val time = remember(now) { java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date(now)) }
    Box(Modifier.fillMaxSize()) {
        val shape = RoundedCornerShape(18.dp)
        val glassModifier = if (glassOverlay) Modifier
            .shadow(16.dp, shape)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0x99FFFFFF), Color(0x553D536A), Color(0xAA141B25))))
            .border(1.dp, Brush.linearGradient(listOf(Color(0xCCFFFFFF), Color(0x3383D8FF), Color(0x66FFFFFF))), shape)
        else Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x990E1116))
        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            if (showClock) Text(time, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.then(glassModifier).padding(horizontal = if (glassOverlay) 18.dp else 12.dp, vertical = if (glassOverlay) 12.dp else 8.dp))
            Column(Modifier.then(glassModifier).padding(horizontal = if (glassOverlay) 18.dp else 12.dp, vertical = if (glassOverlay) 12.dp else 8.dp), horizontalAlignment = Alignment.End) {
                if (showDate) Text(date, color = Color(0xFFE1E5EA), fontSize = 12.sp)
                if (showWeather && weatherLabel.isNotBlank()) Text(weatherLabel, color = Color(0xFFE1E5EA), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SleepScreen(showClock: Boolean, showDate: Boolean, showWeather: Boolean, weatherLabel: String, glassOverlay: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    val date = remember(now) { java.text.SimpleDateFormat("EEEE, d. MMMM", java.util.Locale.GERMANY).format(java.util.Date(now)) }
    val time = remember(now) { java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date(now)) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (showClock || showDate || showWeather && weatherLabel.isNotBlank()) {
            val shape = RoundedCornerShape(28.dp)
            val glassModifier = if (glassOverlay) Modifier
                .shadow(20.dp, shape)
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0x66FFFFFF), Color(0x223D536A), Color(0x66141B25))))
                .border(1.dp, Brush.linearGradient(listOf(Color(0xCCFFFFFF), Color(0x3383D8FF), Color(0x66FFFFFF))), shape)
            else Modifier
            Column(Modifier.align(Alignment.Center).then(glassModifier).padding(horizontal = if (glassOverlay) 38.dp else 0.dp, vertical = if (glassOverlay) 26.dp else 0.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (showClock) Text(time, color = Color.White, fontSize = 68.sp, fontWeight = FontWeight.Light)
                if (showDate) Text(date, color = Color(0xFFB8C1CC), fontSize = 21.sp)
            }
            if (showWeather && weatherLabel.isNotBlank()) {
                val weatherGlass = if (glassOverlay) Modifier.shadow(14.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0x99FFFFFF), Color(0x66141B25)))).border(1.dp, Color(0x88FFFFFF), RoundedCornerShape(18.dp)) else Modifier
                Text(weatherLabel, color = Color(0xFFB8C1CC), fontSize = 16.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).then(weatherGlass).padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

private fun cycleValue(current: String, choices: List<String>, direction: Int): String {
    val index = choices.indexOf(current).let { if (it < 0) 0 else it }
    return choices[(index + direction + choices.size) % choices.size]
}
private fun cycleValue(current: Int, choices: List<Int>, direction: Int): Int {
    val index = choices.indexOf(current).let { if (it < 0) 0 else it }
    return choices[(index + direction + choices.size) % choices.size]
}

private fun transitionName(style: String) = when (style) { "cut" -> "Direkt"; "crossfade" -> "Sanfte Überblendung"; "slide" -> "Gleiten"; "zoom" -> "Sanfter Zoom"; else -> "Langsame Überblendung" }
private fun imageScaleName(mode: String) = when (mode) { "fill" -> "Füllen"; "stretch" -> "Strecken"; else -> "Einpassen" }
private fun selectedAlbumLabel(images: List<String>, index: Int) = images.getOrNull(index)?.contains("upload-albums") == true

@Composable
private fun SettingsDialog(
    seconds: Int,
    panZoom: Boolean,
    displayRotation: Int,
    focusedSettingsNode: Int,
    portraitUi: Boolean,
    autoStartOnBoot: Boolean,
    showClock: Boolean,
    showDate: Boolean,
    showWeather: Boolean,
    weatherCountry: String,
    weatherPostal: String,
    transitionStyle: String,
    imageScaleMode: String,
    glassOverlay: Boolean,
    imagePaddingDp: Int,
    scheduleEnabled: Boolean,
    scheduleWeekdaysOnly: Boolean,
    scheduleStart: String,
    scheduleEnd: String,
    onFocusedNode: (Int) -> Unit,
    onAutoStartOnBoot: (Boolean) -> Unit,
    onShowClock: (Boolean) -> Unit,
    onShowDate: (Boolean) -> Unit,
    onShowWeather: (Boolean) -> Unit,
    onGlassOverlay: (Boolean) -> Unit,
    onWeatherCountry: (String) -> Unit,
    onWeatherPostal: (String) -> Unit,
    onTransitionStyle: (String) -> Unit,
    onImageScaleMode: (String) -> Unit,
    onImagePadding: (Int) -> Unit,
    onScheduleEnabled: (Boolean) -> Unit,
    onScheduleDays: (Boolean) -> Unit,
    onScheduleStart: (String) -> Unit,
    onScheduleEnd: (String) -> Unit,
    onSeconds: (Int) -> Unit,
    onPanZoom: (Boolean) -> Unit,
    onRotateDisplay: () -> Unit,
    onConfirmDisplay: () -> Unit
) {
    val settingsScroll = rememberScrollState()
    val scheduleStartFocus = remember { FocusRequester() }
    val scheduleEndFocus = remember { FocusRequester() }
    val countryFocus = remember { FocusRequester() }
    val postalFocus = remember { FocusRequester() }
    LaunchedEffect(focusedSettingsNode) {
        val position = (settingsScroll.maxValue * (focusedSettingsNode.coerceIn(0, 16) / 16f)).toInt()
        runCatching { settingsScroll.animateScrollTo(position) }
        delay(50)
        runCatching {
            when (focusedSettingsNode) {
                8 -> countryFocus.requestFocus()
                9 -> postalFocus.requestFocus()
                14 -> scheduleStartFocus.requestFocus()
                15 -> scheduleEndFocus.requestFocus()
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).focusGroup(), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 620.dp).heightIn(max = 700.dp), color = SurfaceCard, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.verticalScroll(settingsScroll).padding(24.dp)) {
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
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Beim Fire-TV-Start öffnen", fontWeight = FontWeight.SemiBold)
                        Text("Stillframe nach dem Einschalten automatisch starten.", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = autoStartOnBoot,
                        onCheckedChange = onAutoStartOnBoot,
                        modifier = Modifier.focusProperties { canFocus = false }
                            .then(if (focusedSettingsNode == 4) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(24.dp)) else Modifier)
                    )
                }
                Spacer(Modifier.height(10.dp))
                SettingsToggle("Uhr im Schlafmodus", "Im Ruhebildschirm die Uhrzeit zeigen.", showClock, 5, focusedSettingsNode, onShowClock)
                SettingsToggle("Datum im Schlafmodus", "Datum unter der Uhr anzeigen.", showDate, 6, focusedSettingsNode, onShowDate)
                SettingsToggle("Wetter anzeigen", "Aktuelles Wetter unten in der Ecke.", showWeather, 7, focusedSettingsNode, onShowWeather)
                Text("Wetter-Ort", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                Text("Land (ISO-Code) und Postleitzahl, zum Beispiel DE · 10115", color = Color(0xFFB8C1CC), fontSize = 12.sp)
                OutlinedTextField(
                    value = weatherCountry,
                    onValueChange = onWeatherCountry,
                    label = { Text("Land") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(countryFocus).onFocusChanged { if (it.isFocused) onFocusedNode(8) }
                        .then(if (focusedSettingsNode == 8) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier)
                )
                OutlinedTextField(
                    value = weatherPostal,
                    onValueChange = onWeatherPostal,
                    label = { Text("Postleitzahl") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(postalFocus).onFocusChanged { if (it.isFocused) onFocusedNode(9) }
                        .then(if (focusedSettingsNode == 9) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier)
                )
                Spacer(Modifier.height(12.dp))
                Text("Übergang · ${transitionName(transitionStyle)}", color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (focusedSettingsNode == 10) RemoteFocusSurface else Color.Transparent).clickable { onTransitionStyle(cycleValue(transitionStyle, listOf("fade", "crossfade", "cut", "slide", "zoom"), 1)) }.padding(12.dp))
                Text("Bilddarstellung · ${imageScaleName(imageScaleMode)}", color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (focusedSettingsNode == 11) RemoteFocusSurface else Color.Transparent).clickable { onImageScaleMode(cycleValue(imageScaleMode, listOf("fit", "fill", "stretch"), 1)) }.padding(12.dp))
                SettingsToggle("Zeitplan verwenden", "Außerhalb der aktiven Zeit automatisch schlafen.", scheduleEnabled, 12, focusedSettingsNode, onScheduleEnabled)
                Text("Aktive Tage · ${if (scheduleWeekdaysOnly) "Montag bis Freitag" else "Täglich"}", color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (focusedSettingsNode == 13) RemoteFocusSurface else Color.Transparent).clickable { onScheduleDays(!scheduleWeekdaysOnly) }.padding(12.dp))
                Text("Aktive Zeit", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                Text("${if (scheduleWeekdaysOnly) "Mo–Fr" else "Jeden Tag"} ${scheduleStart.ifBlank { "16:00" }}–${scheduleEnd.ifBlank { "21:00" }} · außerhalb zeigt die App den Schlafbildschirm.", color = Color(0xFFB8C1CC), fontSize = 12.sp)
                SettingsToggle("Liquid-Glass-Optik", "Leichtes Glas mit transparentem Verlauf und Lichtkante für Uhr, Datum und Wetter.", glassOverlay, 16, focusedSettingsNode, onGlassOverlay)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = scheduleStart, onValueChange = { value -> if (value.length <= 5 && value.all { it.isDigit() || it == ':' }) onScheduleStart(value) }, label = { Text("Von · HH:MM") }, singleLine = true,
                        modifier = Modifier.weight(1f).focusRequester(scheduleStartFocus).onFocusChanged { if (it.isFocused) onFocusedNode(14) }.then(if (focusedSettingsNode == 14) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier))
                    OutlinedTextField(value = scheduleEnd, onValueChange = { value -> if (value.length <= 5 && value.all { it.isDigit() || it == ':' }) onScheduleEnd(value) }, label = { Text("Bis · HH:MM") }, singleLine = true,
                        modifier = Modifier.weight(1f).focusRequester(scheduleEndFocus).onFocusChanged { if (it.isFocused) onFocusedNode(15) }.then(if (focusedSettingsNode == 15) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier))
                }
                Text("Bildrand · ${imagePaddingDp} dp", color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (focusedSettingsNode == 17) RemoteFocusSurface else Color.Transparent)
                        .clickable { onImagePadding(cycleValue(imagePaddingDp, listOf(0, 4, 8, 12, 16, 24, 32), 1)) }.padding(12.dp))
                Text("Links/rechts ändert den Abstand zum Bildrand.", color = Color(0xFFB8C1CC), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingsToggle(title: String, description: String, checked: Boolean, node: Int, focusedNode: Int, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).then(if (focusedNode == node) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, fontSize = 12.sp, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onToggle, modifier = Modifier.focusProperties { canFocus = false })
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

private fun fetchWeather(country: String, postal: String): String? = runCatching {
    val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    val encodedPostal = URLEncoder.encode(postal.trim(), "UTF-8")
    val encodedCountry = URLEncoder.encode(country.trim().uppercase(), "UTF-8")
    val geocodeUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encodedPostal&count=10&language=de&format=json&countryCode=$encodedCountry"
    val placeJson = client.newCall(Request.Builder().url(geocodeUrl).build()).execute().use { response ->
        if (!response.isSuccessful) return null
        JSONObject(response.body?.string().orEmpty())
    }
    val places = placeJson.optJSONArray("results") ?: return null
    if (places.length() == 0) return null
    val place = places.getJSONObject(0)
    val latitude = place.optDouble("latitude")
    val longitude = place.optDouble("longitude")
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current=temperature_2m,weather_code&timezone=auto"
    val weather = client.newCall(Request.Builder().url(weatherUrl).build()).execute().use { response ->
        if (!response.isSuccessful) return null
        JSONObject(response.body?.string().orEmpty()).optJSONObject("current") ?: return null
    }
    val temp = weather.optDouble("temperature_2m", Double.NaN)
    if (!temp.isFinite()) return null
    val code = weather.optInt("weather_code", -1)
    val description = when (code) {
        0 -> "Klar"
        1, 2 -> "Leicht bewölkt"
        3 -> "Bewölkt"
        45, 48 -> "Nebel"
        51, 53, 55, 56, 57 -> "Nieselregen"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Regen"
        71, 73, 75, 77, 85, 86 -> "Schnee"
        95, 96, 99 -> "Gewitter"
        else -> "Wetter"
    }
    "${place.optString("name")} · ${temp.toInt()}°C · $description"
}.getOrNull()

private fun isValidScheduleTime(value: String): Boolean {
    val pieces = value.split(":")
    if (pieces.size != 2) return false
    val hour = pieces[0].toIntOrNull() ?: return false
    val minute = pieces[1].toIntOrNull() ?: return false
    return hour in 0..23 && minute in 0..59
}

private fun isScheduleActive(weekdaysOnly: Boolean, start: String, end: String, nowMillis: Long): Boolean {
    fun toMinutes(value: String): Int? {
        val pieces = value.split(":")
        if (pieces.size != 2) return null
        val hour = pieces[0].toIntOrNull() ?: return null
        val minute = pieces[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }
    val from = toMinutes(start) ?: return false
    val until = toMinutes(end) ?: return false
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
    val minuteNow = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
    fun selectedDay(day: Int) = !weekdaysOnly || day in java.util.Calendar.MONDAY..java.util.Calendar.FRIDAY
    if (from == until) return false
    if (from < until) return minuteNow in from until until && selectedDay(calendar.get(java.util.Calendar.DAY_OF_WEEK))
    if (minuteNow >= from) return selectedDay(calendar.get(java.util.Calendar.DAY_OF_WEEK))
    val yesterday = (calendar.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1
    return minuteNow < until && selectedDay(yesterday)
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
private fun UploadQrDialog(url: String, uploadedCount: Int, status: String, onClose: () -> Unit) {
    val closeRequester = remember { FocusRequester() }
    var closeFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(120); runCatching { closeRequester.requestFocus() } }
    val bitmap = remember(url) {
        runCatching {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 600, 600)
            android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until 600) for (y in 0 until 600) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }.asImageBitmap()
        }.getOrNull()
    }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).focusGroup(), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 620.dp).heightIn(max = 900.dp), color = SurfaceCard, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.QrCode2, contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
                Text("Bilder vom Handy hochladen", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text("Verbinde dein Handy mit demselben WLAN und scanne den QR-Code. Wähle auf der Webseite ein Album und die Bilder aus.", textAlign = TextAlign.Center, color = Color(0xFFCFD5DD), modifier = Modifier.padding(top = 10.dp))
                if (bitmap != null) Image(bitmap, "Upload-QR-Code", modifier = Modifier.padding(top = 16.dp).size(230.dp))
                Text(url, color = Accent, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                Text("$uploadedCount Bilder empfangen", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                if (status.isNotBlank()) Text(status, color = Color(0xFF42D9FF), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                Text("Der Upload-Link gilt nur, solange dieser Dialog geöffnet ist.", color = Color(0xFF9DA8B6), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                Button(
                    onClick = onClose,
                    modifier = Modifier.padding(top = 12.dp).focusRequester(closeRequester).onFocusChanged { closeFocused = it.isFocused }
                        .then(if (closeFocused) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(14.dp)) else Modifier),
                    colors = ButtonDefaults.buttonColors(containerColor = if (closeFocused) RemoteFocusSurface else Accent, contentColor = if (closeFocused) Color.White else Ink)
                ) { Text("Upload schließen") }
            }
        }
    }
}
