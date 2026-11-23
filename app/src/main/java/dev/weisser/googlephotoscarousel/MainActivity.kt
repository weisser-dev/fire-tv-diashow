package dev.weisser.googlephotoscarousel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.util.AtomicFile
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
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
import java.util.concurrent.Executors

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
    private var uploadDialogVisible by mutableStateOf(false)
    private var uploadStatus by mutableStateOf("")
    private var uploadServer: LocalPhotoUploadServer? = null
    private var phoneContentUpdate by mutableStateOf<String?>(null)
    private var phoneHiddenCalendarTitles by mutableStateOf<List<String>>(emptyList())
    private var calendarEventsSnapshot by mutableStateOf<List<CalendarEvent>>(emptyList())
    private var remoteKeyHandler: ((Int) -> Boolean)? = null
    private val remoteHandledKeys = mutableSetOf<Int>()
    private var playbackControlsPulse by mutableIntStateOf(0)
    @Volatile private var remoteDebugRotation = 0
    @Volatile private var remoteDebugPortrait = false
    @Volatile private var remoteDebugSelection = "starting"
    private val remoteLogExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "stillframe-debug-log").apply { isDaemon = true } }
    private var updateRelease by mutableStateOf<AppRelease?>(null)
    private var downloadedUpdate by mutableStateOf<File?>(null)
    private var updateBusy by mutableStateOf(false)
    private var updateStatus by mutableStateOf<String?>(null)

    private fun logRemote(message: String) {
        if (!BuildConfig.DEBUG) return
        val line = "${System.currentTimeMillis()} $message"
        Log.i("StillframeRemote", line)
        remoteLogExecutor.execute {
            runCatching {
                val file = File(filesDir, "remote-input-debug.log")
                if (file.length() > 512 * 1024) file.writeText("")
                file.appendText("$line\n")
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            val handled = remoteKeyHandler?.invoke(keyCode) == true
            if (event.repeatCount == 0) logRemote("key=DOWN ${KeyEvent.keyCodeToString(keyCode)} handled=$handled rotation=$remoteDebugRotation portrait=$remoteDebugPortrait selection=$remoteDebugSelection")
            if (handled) {
                playbackControlsPulse++
                remoteHandledKeys += keyCode
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_UP) {
            val handled = remoteHandledKeys.remove(keyCode)
            if (event.repeatCount == 0) logRemote("key=UP ${KeyEvent.keyCodeToString(keyCode)} handled=$handled rotation=$remoteDebugRotation portrait=$remoteDebugPortrait selection=$remoteDebugSelection")
            if (handled) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return super.onKeyDown(keyCode, event)
    }

    private val photoChooser = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        logRemote("picker result count=${uris.size} schemes=${uris.mapNotNull { it.scheme }.distinct()}")
        val prefs = getSharedPreferences("carousel", MODE_PRIVATE)
        val stored = prefs.getStringSet("local_photos", emptySet()).orEmpty().toMutableSet()
        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            stored.add(uri.toString())
        }
        prefs.edit().putStringSet("local_photos", stored).apply()
        localPhotoUris = stored.toList()
    }


    private fun launchPhotoChooser() {
        val probe = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val target = packageManager.resolveActivity(probe, 0)?.activityInfo
        logRemote("picker intent=OPEN_DOCUMENT target=${target?.packageName}/${target?.name ?: "none"} rotation=$remoteDebugRotation")
        photoChooser.launch(arrayOf("image/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val photoPrefs = getSharedPreferences("carousel", MODE_PRIVATE)
        localPhotoUris = photoPrefs.getStringSet("local_photos", emptySet()).orEmpty().toList()
        phoneHiddenCalendarTitles = parseCalendarTitleList(photoPrefs.getString("calendar_phone_hidden_titles", null))
        photoPrefs.edit().remove("google_photos").apply()
        File(filesDir, "google-photos").deleteRecursively()
        refreshUploadedPhotos()
        setContent {
            val prefs = remember { getSharedPreferences("carousel", MODE_PRIVATE) }
            val localPhotos = localPhotoUris
            var seconds by remember { mutableIntStateOf(prefs.getInt("seconds", 8).coerceIn(5, 60)) }
            var motionSeconds by remember { mutableIntStateOf(prefs.getInt("motion_seconds", 30)) }
            var shuffle by remember { mutableStateOf(prefs.getBoolean("shuffle", false)) }
            var photoHistory by remember { mutableStateOf<Map<String, PhotoHistory>>(emptyMap()) }
            val latestPhotoHistory by rememberUpdatedState(photoHistory)
            var photoHistoryLoaded by remember { mutableStateOf(false) }
            var lastRecordedSlide by remember { mutableStateOf<String?>(null) }
            var failedPreloadPhotos by remember { mutableStateOf<Set<String>>(emptySet()) }
            var displayRotation by remember { mutableIntStateOf(prefs.getInt("display_rotation", 0)) }
            var orientationChosen by remember { mutableStateOf(prefs.contains("display_rotation")) }
            var panZoom by remember { mutableStateOf(prefs.getBoolean("pan_zoom", true)) }
            var autoStartOnBoot by remember { mutableStateOf(prefs.getBoolean("auto_start_on_boot", true)) }
            var playing by remember { mutableStateOf(false) }
            var index by remember { mutableIntStateOf(0) }
            var currentImageNeedsMotion by remember(index) { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }
            var showPhotoManager by remember { mutableStateOf(false) }
            var confirmDeleteSelected by remember { mutableStateOf(false) }
            var selectedPhotoModels by remember { mutableStateOf<Set<String>>(emptySet()) }
            var photoManagerDialogFocus by remember { mutableIntStateOf(-1) }
            var managerAlbumFilter by remember { mutableStateOf("*") }
            var managerAlbumOptionsVisible by remember { mutableStateOf(false) }
            var setupConfirmSelected by remember { mutableStateOf(false) }
            var sourceCardFocus by remember { mutableIntStateOf(-1) }
            var focusedSettingsNode by remember { mutableIntStateOf(0) }
            var mainFocusNode by remember { mutableIntStateOf(1) }
            var photoManagerTarget by remember { mutableStateOf("manage") }
            var focusedPhotoIndex by remember { mutableIntStateOf(0) }
            var focusRestoreNode by remember { mutableIntStateOf(1) }
            var rotationBeforeSettings by remember { mutableIntStateOf(displayRotation) }
            var selectedUploadAlbum by remember { mutableStateOf(prefs.getString("selected_upload_album", "*") ?: "*") }
            var newestUploadsFirst by remember { mutableStateOf(prefs.getBoolean("newest_uploads_first", true)) }
            var focusedAlbumIndex by remember { mutableIntStateOf(0) }
            var paused by remember { mutableStateOf(false) }
            var pauseMenuIndex by remember { mutableIntStateOf(0) }
            val savedTransitionStyle = prefs.getString("transition_style", "fade") ?: "fade"
            var transitionStyle by remember { mutableStateOf(if (savedTransitionStyle == "slide" || savedTransitionStyle == "zoom") "crossfade" else savedTransitionStyle) }
            if (savedTransitionStyle == "slide" || savedTransitionStyle == "zoom") {
                prefs.edit().putString("transition_style", "crossfade").apply()
            }
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
            var calendarEnabled by remember { mutableStateOf(prefs.getBoolean("calendar_enabled", false)) }
            var calendarFeedUrl by remember { mutableStateOf(prefs.getString("calendar_feed_url", "").orEmpty()) }
            var calendarBlacklist by remember { mutableStateOf(prefs.getString("calendar_blacklist", "").orEmpty()) }
            var calendarEvents by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
            var calendarStatus by remember { mutableStateOf("") }
            val visibleCalendarEvents = remember(calendarEvents, calendarBlacklist, phoneHiddenCalendarTitles) {
                val blockedTerms = calendarBlacklist.split(',', ';', '\n').map(String::trim).filter(String::isNotBlank)
                val hiddenTitleKeys = phoneHiddenCalendarTitles.map(::calendarTitleKey).toSet()
                calendarEvents.filterNot { event ->
                    blockedTerms.any { event.title.contains(it, ignoreCase = true) } ||
                        calendarTitleKey(event.title) in hiddenTitleKeys
                }
            }
            var notesEnabled by remember { mutableStateOf(prefs.getBoolean("notes_enabled", false)) }
            var noteText by remember { mutableStateOf(prefs.getString("note_text", "").orEmpty()) }
            var phoneContent by remember { mutableStateOf(parsePhoneContent(prefs.getString("phone_content", null), noteText)) }
            LaunchedEffect(phoneContentUpdate) {
                phoneContentUpdate?.let { updated ->
                    phoneContent = parsePhoneContent(updated, "")
                    noteText = phoneContent.notes.joinToString("\n") { it.text }
                    notesEnabled = phoneContent.notes.isNotEmpty() || phoneContent.todos.isNotEmpty()
                    phoneContentUpdate = null
                }
            }
            LaunchedEffect(calendarEvents) { calendarEventsSnapshot = calendarEvents }
            LaunchedEffect(orientationChosen) {
                if (orientationChosen && uploadServer == null && uploadUrl == null) startPhotoUpload(showDialog = false)
            }
            var scheduleEnabled by remember { mutableStateOf(prefs.getBoolean("schedule_enabled", false)) }
            var scheduleWeekdaysOnly by remember { mutableStateOf(prefs.getBoolean("schedule_weekdays_only", true)) }
            var scheduleStart by remember { mutableStateOf(prefs.getString("schedule_start", "16:00") ?: "16:00") }
            var scheduleEnd by remember { mutableStateOf(prefs.getString("schedule_end", "21:00") ?: "21:00") }
            val focusManager = LocalFocusManager.current
            val photoListState = rememberLazyListState()
            val uiScope = rememberCoroutineScope()
            var requestedSlide by remember { mutableStateOf<String?>(null) }
            val settingsFocusRequester = remember { FocusRequester() }
            val localSourceFocusRequester = remember { FocusRequester() }
            val uploadSourceFocusRequester = remember { FocusRequester() }
            val sortFocusRequester = remember { FocusRequester() }
            val playFocusRequester = remember { FocusRequester() }
            val clearPhotosFocusRequester = remember { FocusRequester() }
            val managePhotosFocusRequester = remember { FocusRequester() }
            val updateCheckFocusRequester = remember { FocusRequester() }
            val updateBannerFocusRequester = remember { FocusRequester() }
            var playButtonFocused by remember { mutableStateOf(false) }
            var sortButtonFocused by remember { mutableStateOf(false) }
            var managePhotosButtonFocused by remember { mutableStateOf(false) }
            var settingsButtonFocused by remember { mutableStateOf(false) }
            var updateCheckFocused by remember { mutableStateOf(false) }
            var updateBannerFocused by remember { mutableStateOf(false) }
            val uploadAlbums = remember(uploadedPhotos) { uploadedPhotos.map { it.album }.distinct().sorted() }
            val selectedUploads = remember(uploadedPhotos, selectedUploadAlbum, newestUploadsFirst) {
                uploadedPhotos.filter { selectedUploadAlbum == "*" || it.album == selectedUploadAlbum }
                .let { if (newestUploadsFirst) it.sortedByDescending(UploadedPhoto::modifiedAt) else it.sortedBy(UploadedPhoto::modifiedAt) }
            }
            val images = remember(localPhotos, selectedUploads, selectedUploadAlbum) {
                if (selectedUploadAlbum != "*") selectedUploads.map { "file:${it.path}" }
                else (localPhotos.map { "uri:$it" } + selectedUploads.map { "file:${it.path}" }).distinct()
            }
            val nextSlideIndex = remember(images, index, shuffle, photoHistory, failedPreloadPhotos) {
                chooseNextImage(images, photoHistory, index, shuffle, failedPreloadPhotos)
            }
            val transitionToSlide: (Int) -> Unit = { target ->
                if (requestedSlide == null && target != index) requestedSlide = images.getOrNull(target)
            }
            LaunchedEffect(images, playing) {
                if (!playing || requestedSlide !in images) requestedSlide = null
            }
            val managerImages = remember(localPhotos, uploadedPhotos, managerAlbumFilter) {
                val local = if (managerAlbumFilter == "*" || managerAlbumFilter == "__local__") localPhotos.map { "uri:$it" } else emptyList()
                val uploads = uploadedPhotos.filter { managerAlbumFilter == "*" || it.album == managerAlbumFilter }.map { "file:${it.path}" }
                (local + uploads).distinct()
            }
            val portraitUi = displayRotation == 90 || displayRotation == 270
            val remoteSelection = when {
                showSettings -> "settings:$focusedSettingsNode"
                showPhotoManager -> "photo-manager:$photoManagerDialogFocus"
                message != null -> "dialog:message"
                uploadDialogVisible -> "dialog:upload"
                !orientationChosen -> "setup:rotation"
                playing && paused -> "pause-menu:$pauseMenuIndex"
                playing -> "slideshow:$index/${images.size}"
                mainFocusNode == 5 -> "photos:$photoManagerTarget:$focusedPhotoIndex/${images.size}"
                else -> when (mainFocusNode) { 0 -> "settings-button"; 1 -> "files-source"; 2 -> "phone-upload"; 3 -> "album-filter:$focusedAlbumIndex"; 4 -> "sort-order"; 6 -> "play"; 7 -> "update-check"; 8 -> "update-install"; else -> "main:$mainFocusNode" }
            }
            SideEffect {
                remoteDebugRotation = displayRotation
                remoteDebugPortrait = portraitUi
                remoteDebugSelection = remoteSelection
            }
            LaunchedEffect(remoteSelection, displayRotation, portraitUi) {
                logRemote("selected=$remoteSelection rotation=$displayRotation portrait=$portraitUi")
            }
            LaunchedEffect(images.size) {
                focusedPhotoIndex = focusedPhotoIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
                if (images.isEmpty()) photoManagerTarget = "clear"
            }
            LaunchedEffect(Unit) {
                val historyFile = File(filesDir, "photo-history.json")
                val savedHistory = withContext(Dispatchers.IO) {
                    val historyAtomicFile = AtomicFile(historyFile)
                    val existing = runCatching { historyAtomicFile.openRead().bufferedReader().use { it.readText() } }.getOrNull()
                    if (existing != null) {
                        prefs.edit().remove("photo_history").apply()
                        existing
                    } else {
                        prefs.getString("photo_history", null)?.also { legacyHistory ->
                            writeAtomicFile(historyAtomicFile, legacyHistory)
                            prefs.edit().remove("photo_history").apply()
                        }
                    }
                }
                photoHistory = withContext(Dispatchers.Default) { loadPhotoHistory(savedHistory) }
                photoHistoryLoaded = true
            }
            LaunchedEffect(images, photoHistoryLoaded) {
                if (!photoHistoryLoaded) return@LaunchedEffect
                val now = System.currentTimeMillis()
                val missing = images.filterNot(photoHistory::containsKey)
                if (missing.isNotEmpty()) {
                    photoHistory = photoHistory + missing.associateWith { PhotoHistory(now, 0, 0L) }
                }
            }
            LaunchedEffect(images, index, playing, photoHistoryLoaded) {
                if (!photoHistoryLoaded || !playing) return@LaunchedEffect
                val model = images.getOrNull(index) ?: return@LaunchedEffect
                val slideKey = "$index:$model"
                if (lastRecordedSlide == slideKey) return@LaunchedEffect
                val now = System.currentTimeMillis()
                val old = photoHistory[model] ?: PhotoHistory(now, 0, 0L)
                photoHistory = photoHistory + (model to old.copy(viewCount = old.viewCount + 1, lastViewedAt = now))
                lastRecordedSlide = slideKey
            }
            LaunchedEffect(photoHistoryLoaded) {
                if (!photoHistoryLoaded) return@LaunchedEffect
                val historyFile = File(filesDir, "photo-history.json")
                while (true) {
                    delay(60_000)
                    val snapshot = latestPhotoHistory
                    val serialized = withContext(Dispatchers.Default) { savePhotoHistory(snapshot) }
                    withContext(Dispatchers.IO) { writeAtomicFile(AtomicFile(historyFile), serialized) }
                }
            }
            LaunchedEffect(uploadAlbums, selectedUploadAlbum) {
                focusedAlbumIndex = if (selectedUploadAlbum == "*") 0 else (uploadAlbums.indexOf(selectedUploadAlbum) + 1).coerceAtLeast(0)
            }
            LaunchedEffect(weatherEnabled, weatherCountry, weatherPostal) {
                weatherLabel = ""
                if (weatherEnabled && weatherCountry.matches(Regex("[A-Z]{2}")) && weatherPostal.trim().length >= 3) {
                    delay(750)
                    weatherLabel = withContext(Dispatchers.IO) { fetchWeather(weatherCountry, weatherPostal.trim()) }.orEmpty()
                        logRemote(if (weatherLabel.isBlank()) "weather unavailable country=$weatherCountry postal=${weatherPostal.trim()}" else "weather loaded $weatherLabel")
                }
            }
            LaunchedEffect(calendarEnabled, calendarFeedUrl) {
                if (!calendarEnabled || calendarFeedUrl.isBlank()) {
                    calendarEvents = emptyList()
                    calendarStatus = ""
                } else {
                    delay(1_500)
                    while (true) {
                        val result = runCatching { withContext(Dispatchers.IO) { fetchCalendarEvents(calendarFeedUrl) } }
                        result.onSuccess {
                            calendarEvents = it
                            calendarStatus = "${it.size} Termine geladen"
                            logRemote("calendar loaded events=${it.size} birthdays=${it.count { event -> event.kind == CalendarEventKind.BIRTHDAY }} garbage=${it.count { event -> event.kind == CalendarEventKind.GARBAGE }}")
                        }.onFailure {
                            calendarStatus = "Kalender-Feed konnte nicht geladen werden. Adresse und Internetverbindung prüfen."
                            logRemote("calendar fetch failed type=${it.javaClass.simpleName}")
                        }
                        delay(15 * 60 * 1000L)
                    }
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
                    if (node in 1..8) focusRestoreNode = node
                    runCatching {
                        when (node) {
                            0 -> settingsFocusRequester.requestFocus()
                            1 -> localSourceFocusRequester.requestFocus()
                            2 -> uploadSourceFocusRequester.requestFocus()
                            4 -> sortFocusRequester.requestFocus()
                            5 -> { photoManagerTarget = "manage"; managePhotosFocusRequester.requestFocus() }
                            6 -> playFocusRequester.requestFocus()
                            7 -> updateCheckFocusRequester.requestFocus()
                            8 -> updateBannerFocusRequester.requestFocus()
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
                    val updateBannerVisible = updateRelease != null && !updateBusy
                    val next = if (portraitUi) when (from) {
                        0 -> if (code == KeyEvent.KEYCODE_DPAD_DOWN) 6 else null
                        8 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 6; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> null }
                        6 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 0; KeyEvent.KEYCODE_DPAD_DOWN -> if (updateBannerVisible) 8 else 1; else -> null }
                        1 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> if (updateBannerVisible) 8 else 6; KeyEvent.KEYCODE_DPAD_DOWN -> 2; else -> null }
                        2 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 1; KeyEvent.KEYCODE_DPAD_DOWN -> albumNode; else -> null }
                        3 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 2; KeyEvent.KEYCODE_DPAD_DOWN -> 4; else -> null }
                        4 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 3; KeyEvent.KEYCODE_DPAD_DOWN -> photoNode; else -> null }
                        5 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> if (uploadAlbums.isNotEmpty()) 4 else 2; KeyEvent.KEYCODE_DPAD_DOWN -> 7; else -> null }
                        7 -> if (code == KeyEvent.KEYCODE_DPAD_UP) if (images.isNotEmpty()) 5 else if (uploadAlbums.isNotEmpty()) 4 else 2 else null
                        else -> null
                    } else when (from) {
                        0 -> if (code == KeyEvent.KEYCODE_DPAD_DOWN) 6 else null
                        8 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 6; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> null }
                        6 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 0; KeyEvent.KEYCODE_DPAD_DOWN -> if (updateBannerVisible) 8 else 1; else -> null }
                        1 -> when (code) { KeyEvent.KEYCODE_DPAD_RIGHT -> 2; KeyEvent.KEYCODE_DPAD_UP -> if (updateBannerVisible) 8 else 6; KeyEvent.KEYCODE_DPAD_DOWN -> albumNode; else -> null }
                        2 -> when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> 1; KeyEvent.KEYCODE_DPAD_UP -> if (updateBannerVisible) 8 else 6; KeyEvent.KEYCODE_DPAD_DOWN -> albumNode; else -> null }
                        3 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 2; KeyEvent.KEYCODE_DPAD_DOWN -> 4; else -> null }
                        4 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 3; KeyEvent.KEYCODE_DPAD_DOWN -> photoNode; else -> null }
                        5 -> when (code) { KeyEvent.KEYCODE_DPAD_UP -> 4; KeyEvent.KEYCODE_DPAD_DOWN -> 7; else -> null }
                        7 -> if (code == KeyEvent.KEYCODE_DPAD_UP) if (images.isNotEmpty()) 5 else if (uploadAlbums.isNotEmpty()) 4 else 2 else null
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
                fun beginPlayback() {
                    if (images.isEmpty()) {
                        message = "Füge zuerst Bilder hinzu."
                        return
                    }
                    if (shuffle) index = images.indices.random()
                    playing = true
                    paused = false
                }
                LaunchedEffect(playing, paused, index, images.size) {
                    logRemote("playback state playing=$playing paused=$paused index=$index/${images.size} rotation=$displayRotation")
                }
                DisposableEffect(playing, paused, sleeping, images.size, index, nextSlideIndex, transitionStyle, orientationChosen, displayRotation, setupConfirmSelected, sourceCardFocus, focusedSettingsNode, mainFocusNode, focusedAlbumIndex, uploadAlbums, selectedUploadAlbum, newestUploadsFirst, pauseMenuIndex, showSettings, showPhotoManager, message, uploadUrl, uploadDialogVisible, showPinSetup, pinEntry, pinPadIndex) {
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
                        uploadDialogVisible -> { code ->
                            when (code) {
                                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> { closeUploadQr(); true }
                                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> true
                                else -> false
                            }
                        }
                        showPhotoManager -> { code ->
                            if (code == KeyEvent.KEYCODE_BACK) {
                                showPhotoManager = false
                                focusMainNode(5)
                                true
                            } else false // Let Compose move through the focused image rows and dialog buttons.
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
                                    if (focusedSettingsNode in listOf(9, 10, 15, 16, 19, 20, 22)) return@settingsKeys false
                                    if (focusedSettingsNode == 2) { imagePaddingDp = cycleValue(imagePaddingDp, listOf(0, 4, 8, 12, 16, 24, 32), 1); prefs.edit().putInt("image_padding_dp", imagePaddingDp).apply(); return@settingsKeys true }
                                    when (focusedSettingsNode) {
                                        0 -> focusedSettingsNode = 1
                                        4 -> seconds = (seconds + 1).coerceAtMost(60)
                                        5 -> autoStartOnBoot = !autoStartOnBoot
                                        11 -> transitionStyle = cycleValue(transitionStyle, listOf("fade", "crossfade", "cut"), 1)
                                        12 -> imageScaleMode = cycleValue(imageScaleMode, listOf("fit", "fill", "stretch", "ken_burns"), 1)
                                        17 -> { glassOverlay = !glassOverlay; prefs.edit().putBoolean("glass_overlay", glassOverlay).apply() }
                                        18 -> { calendarEnabled = !calendarEnabled; prefs.edit().putBoolean("calendar_enabled", calendarEnabled).apply() }
                                        21 -> { notesEnabled = !notesEnabled; prefs.edit().putBoolean("notes_enabled", notesEnabled).apply() }
                                    }
                                    if (focusedSettingsNode == 4) prefs.edit().putInt("seconds", seconds).apply()
                                    if (focusedSettingsNode == 5) prefs.edit().putBoolean("auto_start_on_boot", autoStartOnBoot).apply()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    if (focusedSettingsNode in listOf(9, 10, 15, 16, 19, 20, 22)) return@settingsKeys false
                                    if (focusedSettingsNode == 2) { imagePaddingDp = cycleValue(imagePaddingDp, listOf(0, 4, 8, 12, 16, 24, 32), -1); prefs.edit().putInt("image_padding_dp", imagePaddingDp).apply(); return@settingsKeys true }
                                    when (focusedSettingsNode) {
                                        1 -> focusedSettingsNode = 0
                                        4 -> seconds = (seconds - 1).coerceAtLeast(5)
                                        5 -> autoStartOnBoot = !autoStartOnBoot
                                        11 -> transitionStyle = cycleValue(transitionStyle, listOf("fade", "crossfade", "cut"), -1)
                                        12 -> imageScaleMode = cycleValue(imageScaleMode, listOf("fit", "fill", "stretch", "ken_burns"), -1)
                                        17 -> { glassOverlay = !glassOverlay; prefs.edit().putBoolean("glass_overlay", glassOverlay).apply() }
                                        18 -> { calendarEnabled = !calendarEnabled; prefs.edit().putBoolean("calendar_enabled", calendarEnabled).apply() }
                                        21 -> { notesEnabled = !notesEnabled; prefs.edit().putBoolean("notes_enabled", notesEnabled).apply() }
                                    }
                                    if (focusedSettingsNode == 4) prefs.edit().putInt("seconds", seconds).apply()
                                    if (focusedSettingsNode == 5) prefs.edit().putBoolean("auto_start_on_boot", autoStartOnBoot).apply()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    focusedSettingsNode = if (portraitUi) (focusedSettingsNode + 1).coerceAtMost(22) else when (focusedSettingsNode) {
                                        0, 1 -> 2
                                        else -> (focusedSettingsNode + 1).coerceAtMost(22)
                                    }
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    focusedSettingsNode = (focusedSettingsNode - 1).coerceAtLeast(0)
                                    true
                                }
                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                                    if (focusedSettingsNode in listOf(9, 10, 15, 16, 19, 20, 22)) return@settingsKeys false
                                    when (focusedSettingsNode) {
                                        0 -> displayRotation = nextDisplayRotation(displayRotation)
                                        1 -> { prefs.edit().putInt("display_rotation", displayRotation).apply(); showSettings = false }
                                        2 -> { imagePaddingDp = cycleValue(imagePaddingDp, listOf(0, 4, 8, 12, 16, 24, 32), 1); prefs.edit().putInt("image_padding_dp", imagePaddingDp).apply() }
                                        3 -> { panZoom = !panZoom; prefs.edit().putBoolean("pan_zoom", panZoom).apply() }
                                        4 -> { seconds = if (seconds >= 60) 5 else seconds + 1; prefs.edit().putInt("seconds", seconds).apply() }
                                        5 -> { autoStartOnBoot = !autoStartOnBoot; prefs.edit().putBoolean("auto_start_on_boot", autoStartOnBoot).apply() }
                                        6 -> { showClock = !showClock; prefs.edit().putBoolean("show_clock", showClock).apply() }
                                        7 -> { showDate = !showDate; prefs.edit().putBoolean("show_date", showDate).apply() }
                                        8 -> { weatherEnabled = !weatherEnabled; prefs.edit().putBoolean("show_weather", weatherEnabled).apply() }
                                        11 -> transitionStyle = cycleValue(transitionStyle, listOf("fade", "crossfade", "cut"), 1)
                                        12 -> imageScaleMode = cycleValue(imageScaleMode, listOf("fit", "fill", "stretch", "ken_burns"), 1)
                                        13 -> { scheduleEnabled = !scheduleEnabled; prefs.edit().putBoolean("schedule_enabled", scheduleEnabled).apply() }
                                        14 -> { scheduleWeekdaysOnly = !scheduleWeekdaysOnly; prefs.edit().putBoolean("schedule_weekdays_only", scheduleWeekdaysOnly).apply() }
                                        17 -> { glassOverlay = !glassOverlay; prefs.edit().putBoolean("glass_overlay", glassOverlay).apply() }
                                        18 -> { calendarEnabled = !calendarEnabled; prefs.edit().putBoolean("calendar_enabled", calendarEnabled).apply() }
                                        21 -> { notesEnabled = !notesEnabled; prefs.edit().putBoolean("notes_enabled", notesEnabled).apply() }
                                    }
                                    prefs.edit().putString("transition_style", transitionStyle).putString("image_scale_mode", imageScaleMode).apply()
                                    true
                                }
                                else -> false
                            }
                        }
                        orientationChosen && !playing && mainFocusNode in 0..8 -> mainKeys@{ code ->
                            when (code) {
                                KeyEvent.KEYCODE_DPAD_UP -> if (mainFocusNode == 5) {
                                    when (photoManagerTarget) {
                                        "delete" -> { photoManagerTarget = "preview"; true }
                                        "preview" -> { photoManagerTarget = "manage"; managePhotosFocusRequester.requestFocus(); true }
                                        else -> navigateMainFocus(code)
                                    }
                                } else navigateMainFocus(code)
                                KeyEvent.KEYCODE_DPAD_DOWN -> if (mainFocusNode == 5) {
                                    when (photoManagerTarget) {
                                        "manage" -> if (images.isNotEmpty()) { photoManagerTarget = "preview"; focusPhoto(focusedPhotoIndex); true } else { focusMainNode(6); true }
                                        "preview" -> { photoManagerTarget = "delete"; true }
                                        "delete" -> { focusMainNode(6); true }
                                        else -> { focusMainNode(6); true }
                                    }
                                } else navigateMainFocus(code)
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    if (mainFocusNode == 5) {
                                        if (photoManagerTarget != "manage" && images.isNotEmpty()) {
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
                                            "manage" -> { showPhotoManager = true; selectedPhotoModels = emptySet(); photoManagerDialogFocus = -1 }
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
                                        1 -> launchPhotoChooser()
                                        2 -> startPhotoUpload()
                                        3 -> {
                                            selectedUploadAlbum = if (focusedAlbumIndex == 0) "*" else uploadAlbums.getOrNull(focusedAlbumIndex - 1) ?: "*"
                                            prefs.edit().putString("selected_upload_album", selectedUploadAlbum).apply()
                                        }
                                        4 -> { newestUploadsFirst = !newestUploadsFirst; prefs.edit().putBoolean("newest_uploads_first", newestUploadsFirst).apply() }
                                        5 -> { /* Photo manager is handled above. */ }
                                        6 -> beginPlayback()
                                        7 -> if (updateRelease != null) downloadOrInstallUpdate() else checkForUpdates(force = true)
                                        8 -> downloadOrInstallUpdate()
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
                                KeyEvent.KEYCODE_DPAD_DOWN -> { pauseMenuIndex = (pauseMenuIndex + 1).coerceAtMost(11); true }
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    val direction = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                                    when (pauseMenuIndex) {
                                        2 -> { shuffle = !shuffle; prefs.edit().putBoolean("shuffle", shuffle).apply() }
                                        5 -> { seconds = (seconds + direction).coerceIn(5, 60); prefs.edit().putInt("seconds", seconds).apply() }
                                        6 -> { motionSeconds = cycleValue(motionSeconds, listOf(15, 30, 45, 60), direction); prefs.edit().putInt("motion_seconds", motionSeconds).apply() }
                                        7 -> { transitionStyle = cycleValue(transitionStyle, listOf("fade", "crossfade", "cut"), direction); prefs.edit().putString("transition_style", transitionStyle).apply() }
                                        8 -> { imageScaleMode = cycleValue(imageScaleMode, listOf("fit", "fill", "stretch", "ken_burns"), direction); prefs.edit().putString("image_scale_mode", imageScaleMode).apply() }
                                        9 -> { focusedAlbumIndex = (focusedAlbumIndex + direction).coerceIn(0, uploadAlbums.size); selectedUploadAlbum = if (focusedAlbumIndex == 0) "*" else uploadAlbums.getOrNull(focusedAlbumIndex - 1) ?: "*"; prefs.edit().putString("selected_upload_album", selectedUploadAlbum).apply() }
                                        10 -> { sleepTimerMinutes = cycleValue(sleepTimerMinutes, listOf(0, 15, 30, 60), direction); prefs.edit().putInt("sleep_timer_minutes", sleepTimerMinutes).apply(); sleepDeadline = if (sleepTimerMinutes == 0) 0L else System.currentTimeMillis() + sleepTimerMinutes * 60_000L }
                                    }
                                    true
                                }
                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                                    when (pauseMenuIndex) {
                                        0 -> paused = false
                                        1 -> { sleeping = true; manualSleepOverride = true; playing = false; paused = false; sleepDeadline = 0L }
                                        2 -> { shuffle = !shuffle; prefs.edit().putBoolean("shuffle", shuffle).apply() }
                                        3 -> startPhotoUpload()
                                        4 -> launchPhotoChooser()
                                        5 -> seconds = if (seconds >= 60) 5 else seconds + 1
                                        6 -> { motionSeconds = cycleValue(motionSeconds, listOf(15, 30, 45, 60), 1); prefs.edit().putInt("motion_seconds", motionSeconds).apply() }
                                        7 -> transitionStyle = cycleValue(transitionStyle, listOf("fade", "crossfade", "cut"), 1)
                                        8 -> imageScaleMode = cycleValue(imageScaleMode, listOf("fit", "fill", "stretch", "ken_burns"), 1)
                                        9 -> { focusedAlbumIndex = (focusedAlbumIndex + 1).coerceIn(0, uploadAlbums.size); selectedUploadAlbum = if (focusedAlbumIndex == 0) "*" else uploadAlbums.getOrNull(focusedAlbumIndex - 1) ?: "*" }
                                        10 -> { sleepTimerMinutes = cycleValue(sleepTimerMinutes, listOf(0, 15, 30, 60), 1); prefs.edit().putInt("sleep_timer_minutes", sleepTimerMinutes).apply(); sleepDeadline = if (sleepTimerMinutes == 0) 0L else System.currentTimeMillis() + sleepTimerMinutes * 60_000L }
                                        11 -> { playing = false; paused = false; sleepDeadline = 0L }
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
                                    transitionToSlide(nextSlideIndex)
                                    true
                                }
                                (portraitUi && code == KeyEvent.KEYCODE_DPAD_UP) || (!portraitUi && code == KeyEvent.KEYCODE_DPAD_LEFT) || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                    transitionToSlide((index - 1 + images.size) % images.size)
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
                LaunchedEffect(images.size, index, nextSlideIndex, playing, paused, seconds, shuffle, panZoom, currentImageNeedsMotion, motionSeconds, requestedSlide) {
                    if (playing && !paused && images.size > 1 && requestedSlide == null) {
                        val slideSeconds = if (panZoom && currentImageNeedsMotion) maxOf(seconds, motionSeconds, 5) else maxOf(seconds, 5)
                        delay(slideSeconds * 1000L)
                        transitionToSlide(nextSlideIndex)
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
                BackHandler(showPhotoManager) { showPhotoManager = false; focusMainNode(5) }
                BackHandler(showSettings) {
                    displayRotation = rotationBeforeSettings
                    showSettings = false
                }
                BackHandler(uploadDialogVisible) { closeUploadQr() }
                BackHandler(!orientationChosen) {
                    displayRotation = 0
                    prefs.edit().putInt("display_rotation", 0).apply()
                    orientationChosen = true
                }
                BackHandler(message != null) { message = null }
                BackHandler(showPinSetup) { showPinSetup = false; pinEntry = "" }
                LaunchedEffect(showSettings, message, orientationChosen, uploadDialogVisible, showPinSetup, playing, paused) {
                    val modalVisible = showSettings || showPhotoManager || message != null || !orientationChosen || uploadDialogVisible || showPinSetup
                    if (modalVisible) {
                        focusManager.clearFocus(force = true)
                    } else {
                        focusMainNode(focusRestoreNode)
                    }
                }

                BoxWithConstraints(
                    Modifier.fillMaxSize().background(Ink)
                        .then(if (playing || sleeping) Modifier else Modifier.padding(28.dp).border(2.dp, Color(0xFF394654)).padding(14.dp)),
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
                            val modalVisible = showSettings || showPhotoManager || message != null || !orientationChosen || uploadDialogVisible || showPinSetup
                    if (sleeping) {
                        SleepScreen(showClock, showDate, weatherEnabled, weatherLabel, glassOverlay, if (calendarEnabled) visibleCalendarEvents else emptyList(), if (notesEnabled) phoneContent else PhoneContent(), uploadUrl) { startPhotoUpload() }
                    } else if (playing && images.isNotEmpty()) {
                        PlaybackView(images, index, nextSlideIndex, panZoom, seconds, motionSeconds, paused, imageScaleMode, imagePaddingDp, playbackControlsPulse, transitionStyle, pauseMenuIndex, shuffle, sleepTimerMinutes, requestedSlide,
                            onTransitionComplete = { photo ->
                                if (photo == requestedSlide) {
                                    val target = images.indexOf(photo)
                                    if (target >= 0) index = target
                                    requestedSlide = null
                                }
                            },
                            showClock, showDate, weatherEnabled, weatherLabel, glassOverlay, if (calendarEnabled) visibleCalendarEvents else emptyList(), if (notesEnabled) phoneContent else PhoneContent(), uploadUrl,
                            onNextPreloadFailed = { photo ->
                                failedPreloadPhotos = failedPreloadPhotos + photo.removePrefix("uri:").removePrefix("file:")
                                if (requestedSlide == photo) requestedSlide = null
                            },
                            onImageMotionRequired = { currentImageNeedsMotion = it },
                            onPause = { paused = true; pauseMenuIndex = 0 },
                            onNext = { transitionToSlide(nextSlideIndex) }, onPrevious = { transitionToSlide((index - 1 + images.size) % images.size) },
                            onResume = { paused = false }, onAddImages = { startPhotoUpload() }, onAddFiles = { launchPhotoChooser() },
                            onCycleSpeed = { direction -> seconds = (seconds + direction).coerceIn(5, 60); prefs.edit().putInt("seconds", seconds).apply() },
                            onCycleMotionDuration = { direction -> motionSeconds = cycleValue(motionSeconds, listOf(15, 30, 45, 60), direction); prefs.edit().putInt("motion_seconds", motionSeconds).apply() },
                            onCycleTransition = { direction -> transitionStyle = cycleValue(transitionStyle, listOf("fade", "crossfade", "cut"), direction); prefs.edit().putString("transition_style", transitionStyle).apply() },
                            onCycleScale = { direction -> imageScaleMode = cycleValue(imageScaleMode, listOf("fit", "fill", "stretch", "ken_burns"), direction); prefs.edit().putString("image_scale_mode", imageScaleMode).apply() },
                            onToggleShuffle = { shuffle = !shuffle; prefs.edit().putBoolean("shuffle", shuffle).apply() }, onOpenUploadQr = { startPhotoUpload() },
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
                            if (images.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = { logRemote("play clicked images=${images.size} rotation=$displayRotation shuffle=$shuffle"); beginPlayback() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (playButtonFocused || mainFocusNode == 6) RemoteFocusSurface else Accent,
                                        contentColor = if (playButtonFocused || mainFocusNode == 6) Color.White else Ink
                                    ),
                                    modifier = Modifier.fillMaxWidth().height(56.dp).focusRequester(playFocusRequester)
                                        .onFocusChanged { playButtonFocused = it.isFocused; if (it.isFocused) mainFocusNode = 6 }
                                        .then(if (playButtonFocused || mainFocusNode == 6) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(14.dp)) else Modifier)
                                ) {
                                    Icon(Icons.Default.PlayArrow, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Abspielen", fontWeight = FontWeight.Bold)
                                }
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
                                        Button(
                                            onClick = { downloadOrInstallUpdate() },
                                            modifier = Modifier.focusRequester(updateBannerFocusRequester)
                                                .onFocusChanged {
                                                    updateBannerFocused = it.isFocused
                                                    if (it.isFocused) { mainFocusNode = 8; focusRestoreNode = 8 }
                                                    else if (mainFocusNode == 8) mainFocusNode = -1
                                                }
                                                .then(if (updateBannerFocused) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(24.dp)) else Modifier),
                                            colors = ButtonDefaults.buttonColors(containerColor = if (updateBannerFocused) RemoteFocusSurface else Accent, contentColor = if (updateBannerFocused) Color.White else Ink)
                                        ) {
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
                                    SourceCard("Dateien", "Fotos von diesem Gerät", Icons.Default.FolderOpen, Modifier.fillMaxWidth().focusRequester(localSourceFocusRequester), count = localPhotos.size, onClick = { launchPhotoChooser() }, onFocusChange = { if (it) { sourceCardFocus = 0; mainFocusNode = 1; focusRestoreNode = 1 } else if (sourceCardFocus == 0) sourceCardFocus = -1 })
                                    SourceCard("Handy-Upload", "Bilder per QR im WLAN", Icons.Default.QrCode2, Modifier.fillMaxWidth().focusRequester(uploadSourceFocusRequester), count = uploadedPhotos.size, onClick = { startPhotoUpload() }, onFocusChange = { if (it) { sourceCardFocus = 1; mainFocusNode = 2; focusRestoreNode = 2 } else if (sourceCardFocus == 1) sourceCardFocus = -1 })
                                }
                            } else {
                                Row(Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SourceCard("Dateien", "Fotos von diesem Gerät", Icons.Default.FolderOpen, Modifier.weight(1f).focusRequester(localSourceFocusRequester), count = localPhotos.size, onClick = { launchPhotoChooser() }, onFocusChange = { if (it) { sourceCardFocus = 0; mainFocusNode = 1; focusRestoreNode = 1 } else if (sourceCardFocus == 0) sourceCardFocus = -1 })
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
                                if (images.isNotEmpty()) TextButton(
                                    modifier = Modifier.focusRequester(managePhotosFocusRequester)
                                        .onFocusChanged { managePhotosButtonFocused = it.isFocused; if (it.isFocused) { mainFocusNode = 5; photoManagerTarget = "manage" } }
                                        .then(if (managePhotosButtonFocused || mainFocusNode == 5 && photoManagerTarget == "manage") Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier),
                                    onClick = { showPhotoManager = true; selectedPhotoModels = emptySet(); photoManagerDialogFocus = -1 }
                                ) { Text("Bilder verwalten (${images.size})", color = if (managePhotosButtonFocused || mainFocusNode == 5 && photoManagerTarget == "manage") Color.White else Color(0xFFADB7C4)) }
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
                                    OutlinedButton(onClick = { index = (index - 1 + images.size) % images.size }, modifier = Modifier.height(54.dp)) { Icon(Icons.Default.SkipPrevious, "Vorheriges Bild") }
                                    OutlinedButton(onClick = { index = (index + 1) % images.size }, modifier = Modifier.height(54.dp)) { Icon(Icons.Default.SkipNext, "Nächstes Bild") }
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${seconds}s pro Bild", color = Color(0xFFC1C8D2), fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { shuffle = !shuffle; prefs.edit().putBoolean("shuffle", shuffle).apply() }) { Icon(Icons.Default.Shuffle, "Zufall", tint = if (shuffle) Accent else Color.Gray) }
                                    Text(if (shuffle) "Shuffle an" else "Shuffle aus", color = Color(0xFFC1C8D2), fontSize = 13.sp)
                                }
                                Slider(value = seconds.toFloat(), onValueChange = { seconds = it.toInt().coerceIn(5, 60); prefs.edit().putInt("seconds", seconds).apply() }, valueRange = 5f..60f, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
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
                    if (uploadDialogVisible) uploadUrl?.let { url -> UploadQrDialog(url, uploadedPhotos.size, uploadStatus, onClose = { closeUploadQr() }) }
                    message?.let { text -> InlineMessageDialog(text) { message = null } }
                    if (showSettings) {
                        SettingsDialog(
                            seconds, panZoom, displayRotation, focusedSettingsNode, portraitUi, autoStartOnBoot,
                            showClock, showDate, weatherEnabled, weatherCountry, weatherPostal, transitionStyle, imageScaleMode, glassOverlay, imagePaddingDp,
                            scheduleEnabled, scheduleWeekdaysOnly, scheduleStart, scheduleEnd, calendarEnabled, calendarFeedUrl, calendarStatus, calendarBlacklist, notesEnabled, noteText,
                            onFocusedNode = { focusedSettingsNode = it },
                            onAutoStartOnBoot = { autoStartOnBoot = it; prefs.edit().putBoolean("auto_start_on_boot", it).apply() },
                            onShowClock = { showClock = it; prefs.edit().putBoolean("show_clock", it).apply() },
                            onShowDate = { showDate = it; prefs.edit().putBoolean("show_date", it).apply() },
                            onShowWeather = { weatherEnabled = it; prefs.edit().putBoolean("show_weather", it).apply() },
                            onCalendarEnabled = { calendarEnabled = it; prefs.edit().putBoolean("calendar_enabled", it).apply() },
                            onCalendarFeedUrl = { calendarFeedUrl = it; prefs.edit().putString("calendar_feed_url", it).apply() },
                            onCalendarBlacklist = { calendarBlacklist = it; prefs.edit().putString("calendar_blacklist", it).apply() },
                            onNotesEnabled = { notesEnabled = it; prefs.edit().putBoolean("notes_enabled", it).apply() },
                            onNoteText = { noteText = it.take(240); prefs.edit().putString("note_text", noteText).apply() },
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
                    if (showPhotoManager) {
                        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                            Surface(Modifier.fillMaxWidth(0.94f).widthIn(max = 980.dp).fillMaxHeight(0.88f), color = SurfaceCard, shape = RoundedCornerShape(24.dp)) {
                                Column(Modifier.padding(20.dp)) {
                                    Text("Bilder verwalten", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                    Text("${selectedPhotoModels.size} von ${managerImages.size} ausgewählt", color = Color(0xFFB8C1CC), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Ordner:", color = Color(0xFFB8C1CC), fontSize = 12.sp)
                                        TextButton(onClick = { managerAlbumOptionsVisible = !managerAlbumOptionsVisible }) {
                                            Text(when (managerAlbumFilter) { "__local__" -> "Dateien auf dem Gerät"; "*" -> "Alle Ordner"; else -> managerAlbumFilter })
                                        }
                                    }
                                    if (managerAlbumOptionsVisible) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            FilterChip(selected = managerAlbumFilter == "*", onClick = { managerAlbumFilter = "*"; selectedPhotoModels = emptySet() }, label = { Text("Alle") })
                                            FilterChip(selected = managerAlbumFilter == "__local__", onClick = { managerAlbumFilter = "__local__"; selectedPhotoModels = emptySet() }, label = { Text("Dateien") })
                                            uploadAlbums.forEach { album -> FilterChip(selected = managerAlbumFilter == album, onClick = { managerAlbumFilter = album; selectedPhotoModels = emptySet() }, label = { Text(album) }) }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                                        TextButton(onClick = { selectedPhotoModels = if (selectedPhotoModels.size == managerImages.size) emptySet() else managerImages.toSet() }) {
                                            Text(if (managerImages.isNotEmpty() && selectedPhotoModels.size == managerImages.size) "Auswahl aufheben" else "Alle auswählen")
                                        }
                                        TextButton(onClick = { confirmDeleteSelected = true }, enabled = selectedPhotoModels.isNotEmpty()) { Text("Löschen (${selectedPhotoModels.size})") }
                                    }
                                    LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        gridItemsIndexed(managerImages) { i, model ->
                                            val isSelected = model in selectedPhotoModels
                                            Column(
                                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                                    .background(if (isSelected) RemoteFocusSurface else Color(0xFF121820))
                                                    .border(if (photoManagerDialogFocus == i) 4.dp else 1.dp, if (photoManagerDialogFocus == i) RemoteFocus else Color(0x554D6175), RoundedCornerShape(12.dp))
                                                    .onFocusChanged { if (it.isFocused) { photoManagerDialogFocus = i; logRemote("selected=photo-manager:item:$i/${managerImages.size}") } }
                                                    .clickable { selectedPhotoModels = if (isSelected) selectedPhotoModels - model else selectedPhotoModels + model },
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                AsyncImage(model.removePrefix("uri:").removePrefix("file:"), "Foto ${i + 1}", Modifier.fillMaxWidth().height(118.dp).clip(RoundedCornerShape(9.dp)).padding(4.dp), contentScale = ContentScale.Crop)
                                                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Text(model.substringAfterLast('/').substringAfterLast(':').takeLast(23), color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, fontSize = 11.sp)
                                                    Checkbox(checked = isSelected, onCheckedChange = { checked -> selectedPhotoModels = if (checked) selectedPhotoModels + model else selectedPhotoModels - model })
                                                }
                                            }
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { showPhotoManager = false; focusMainNode(5) }) { Text("Schließen") }
                                        Button(onClick = { showPhotoManager = false; focusMainNode(5) }) { Text("Fertig") }
                                    }
                                }
                            }
                        }
                    }
                    if (confirmDeleteSelected) {
                        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                            Surface(Modifier.fillMaxWidth(0.86f).widthIn(max = 520.dp), color = SurfaceCard, shape = RoundedCornerShape(20.dp)) {
                                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Ausgewählte Bilder löschen?", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                                    Text("${selectedPhotoModels.size} Bilder werden von diesem Gerät entfernt.", color = Color(0xFFB8C1CC), modifier = Modifier.padding(top = 10.dp, bottom = 16.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        TextButton(onClick = { confirmDeleteSelected = false }) { Text("Abbrechen") }
                                        Button(onClick = {
                                            val doomed = selectedPhotoModels.toList()
                                            confirmDeleteSelected = false
                                            doomed.forEach(::removePhoto)
                                            selectedPhotoModels = emptySet()
                                            focusedPhotoIndex = focusedPhotoIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
                                        }) { Text("Löschen") }
                                    }
                                }
                            }
                        }
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
        lifecycleScope.launch(Dispatchers.IO) {
            val photos = LocalPhotoUploadServer.scan(filesDir)
            withContext(Dispatchers.Main) { uploadedPhotos = photos }
        }
    }

    private fun startPhotoUpload(showDialog: Boolean = true) {
        if (uploadServer != null && uploadUrl != null) {
            uploadDialogVisible = showDialog
            return
        }
        val prefs = getSharedPreferences("carousel", MODE_PRIVATE)
        val server = LocalPhotoUploadServer(
            filesDir = filesDir,
            onPhotoUploaded = { _, _ ->
                runOnUiThread {
                    refreshUploadedPhotos()
                    uploadStatus = "Bild empfangen. Du kannst weitere senden."
                }
            },
            readPhoneContent = {
                val legacy = prefs.getString("note_text", "").orEmpty()
                serializePhoneContent(parsePhoneContent(prefs.getString("phone_content", null), legacy))
            },
            onPhoneContentUpdated = { updated ->
                runOnUiThread {
                    val parsed = parsePhoneContent(updated, "")
                    val summary = buildList {
                        parsed.todos.forEach { add("${if (it.done) "☑" else "□"} ${it.text}") }
                        parsed.notes.forEach { add(it.text) }
                    }.joinToString("\n")
                    prefs.edit().putString("phone_content", updated).putString("note_text", summary).putBoolean("notes_enabled", true).apply()
                    phoneContentUpdate = updated
                    uploadStatus = "Aufgaben und Notizen auf dem Fernseher gespeichert."
                }
            },
            readCalendarState = {
                serializeCalendarSnapshot(
                    calendarEventsSnapshot,
                    parseCalendarTitleList(prefs.getString("calendar_phone_hidden_titles", null)),
                    prefs.getString("calendar_blacklist", "").orEmpty()
                )
            },
            onCalendarHiddenTitlesUpdated = { updated ->
                runOnUiThread {
                    val parsed = parseCalendarTitleList(updated)
                    prefs.edit().putString("calendar_phone_hidden_titles", updated).apply()
                    phoneHiddenCalendarTitles = parsed
                    uploadStatus = "Kalenderauswahl auf dem Fernseher gespeichert."
                    logRemote("calendar hidden-title update applied count=${parsed.size}")
                }
            }
        )
        try {
            uploadServer = server
            uploadStatus = "Warte auf Bilder von deinem Handy …"
            uploadUrl = server.start()
            uploadDialogVisible = showDialog
        } catch (error: Exception) {
            server.stop()
            uploadServer = null
            uploadUrl = null
            uploadDialogVisible = false
            if (showDialog) message = error.localizedMessage ?: "Der Handy-Upload konnte nicht gestartet werden."
        }
    }

    private fun closeUploadQr() {
        uploadDialogVisible = false
        uploadStatus = ""
        refreshUploadedPhotos()
    }

    private fun stopPhotoUpload() {
        uploadServer?.stop()
        uploadServer = null
        uploadUrl = null
        uploadDialogVisible = false
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

@Composable
private fun PlaybackView(
    images: List<String>, index: Int, nextIndex: Int, panZoom: Boolean, seconds: Int, motionSeconds: Int,
    paused: Boolean, imageScaleMode: String, imagePaddingDp: Int, controlsPulse: Int, transitionStyle: String, pauseMenuIndex: Int, shuffle: Boolean, sleepTimerMinutes: Int,
    requestedSlide: String?, onTransitionComplete: (String) -> Unit,
    showClock: Boolean, showDate: Boolean, showWeather: Boolean, weatherLabel: String, glassOverlay: Boolean, calendarEvents: List<CalendarEvent>, phoneContent: PhoneContent, uploadUrl: String?,
    onNextPreloadFailed: (String) -> Unit,
    onImageMotionRequired: (Boolean) -> Unit,
    onPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onResume: () -> Unit,
    onAddImages: () -> Unit, onAddFiles: () -> Unit, onCycleSpeed: (Int) -> Unit, onCycleMotionDuration: (Int) -> Unit,
    onCycleTransition: (Int) -> Unit, onCycleScale: (Int) -> Unit, onToggleShuffle: () -> Unit, onOpenUploadQr: () -> Unit, onCycleSleepTimer: (Int) -> Unit,
    onSleepNow: () -> Unit, onExit: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        var controlsVisible by remember { mutableStateOf(true) }
        LaunchedEffect(paused, controlsPulse) {
            controlsVisible = true
            if (!paused) { delay(2800); controlsVisible = false }
        }
        val standbyIndex = if (requestedSlide == null) nextIndex else images.indexOf(requestedSlide)
        SlideshowPhotos(
            current = images[index], currentIndex = index, standby = images.getOrNull(standbyIndex), standbyIndex = standbyIndex,
            advancing = requestedSlide != null, paused = paused, motionEnabled = panZoom || imageScaleMode == "ken_burns",
            seconds = seconds, motionSeconds = motionSeconds, mode = imageScaleMode, padding = imagePaddingDp.dp,
            fadeMillis = transitionDurationMillis(transitionStyle), onAdvance = onTransitionComplete,
            onFailed = onNextPreloadFailed, onMotionRequired = onImageMotionRequired
        )
        if (showClock || showDate || showWeather && weatherLabel.isNotBlank() || calendarEvents.isNotEmpty() || phoneContent.hasContent || uploadUrl != null) {
            TimeWeatherOverlay(showClock, showDate, showWeather, weatherLabel, glassOverlay, imagePaddingDp, calendarEvents, phoneContent, uploadUrl, onOpenUploadQr)
        }
        if (paused) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                Surface(Modifier.fillMaxWidth().widthIn(max = 580.dp).heightIn(max = 920.dp), color = SurfaceCard, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Diashow pausiert", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        Text("Mittlere Taste: Auswahl öffnen", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp, bottom = 16.dp))
                        val options = listOf(
                            "Fortsetzen" to "Weiterblättern",
                            "Jetzt schlafen" to "Bildschirm wird schwarz; OK oder Zurück weckt auf",
                            "Shuffle · ${if (shuffle) "An" else "Aus"}" to "Zufällig; neue Bilder bekommen Vorrang",
                            "Bilder per QR hinzufügen" to "Handy-Upload im WLAN",
                            "Dateien hinzufügen" to "Bilder auf diesem Gerät wählen",
                            "Bilddauer · ${seconds}s" to "Links/rechts ändert die Dauer",
                            "Schwenkdauer · ${motionSeconds}s" to "Bei abweichendem Format läuft der Schwenk vollständig durch",
                            "Übergang · ${transitionName(transitionStyle)}" to "Links/rechts wechselt den Übergang",
                            "Bilddarstellung · ${imageScaleName(imageScaleMode)}" to "Einpassen, Füllen, Strecken oder Ken-Burns-Bewegung",
                            "Album · ${if (selectedAlbumLabel(images, index)) "Auswahl" else "Alle Bilder"}" to "Album im Hauptmenü wechseln",
                            "Schlaf-Timer · ${if (sleepTimerMinutes == 0) "Aus" else "$sleepTimerMinutes Min."}" to "Links/rechts stellt 15, 30 oder 60 Minuten ein",
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
                                        1 -> onSleepNow()
                                        2 -> onToggleShuffle()
                                        3 -> onAddImages()
                                        4 -> onAddFiles()
                                        5 -> onCycleSpeed(1)
                                        6 -> onCycleMotionDuration(1)
                                        7 -> onCycleTransition(1)
                                        8 -> onCycleScale(1)
                                        10 -> onCycleSleepTimer(1)
                                        11 -> onExit()
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
private fun TimeWeatherOverlay(showClock: Boolean, showDate: Boolean, showWeather: Boolean, weatherLabel: String, glassOverlay: Boolean, imagePaddingDp: Int, calendarEvents: List<CalendarEvent>, phoneContent: PhoneContent, uploadUrl: String?, onOpenUploadQr: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    val date = remember(now) { java.text.SimpleDateFormat("EEEE, d. MMMM", java.util.Locale.getDefault()).format(java.util.Date(now)) }
    val time = remember(now) { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val shape = RoundedCornerShape(18.dp)
        // Keep the glass treatment to one quiet outline; stacked gradients looked like a second bar on TV.
        val glassModifier = if (glassOverlay) Modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0x66FFFFFF), Color(0x443D536A), Color(0x99141B25))))
            .border(1.dp, Color(0x66FFFFFF), shape)
        else Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x990E1116))
        Row(Modifier.fillMaxWidth().padding((imagePaddingDp + 42).dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            if (showClock) Text(time, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.then(glassModifier).padding(horizontal = if (glassOverlay) 18.dp else 12.dp, vertical = if (glassOverlay) 12.dp else 8.dp))
            if (showDate || showWeather && weatherLabel.isNotBlank() || uploadUrl != null) {
                Row(Modifier.then(glassModifier).padding(horizontal = if (glassOverlay) 12.dp else 8.dp, vertical = if (glassOverlay) 6.dp else 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        if (showDate) Text(date, color = Color(0xFFE1E5EA), fontSize = 12.sp)
                        if (showWeather && weatherLabel.isNotBlank()) Text(weatherLabel, color = Color(0xFFE1E5EA), fontSize = 12.sp)
                    }
                    if (uploadUrl != null) PersistentQrBadge(uploadUrl, Modifier.size(42.dp), onOpenUploadQr)
                }
            }
        }
        if (calendarEvents.isNotEmpty() || phoneContent.hasContent) {
            val inset = (imagePaddingDp + 42).dp
            AgendaAndPhoneCard(calendarEvents, phoneContent, glassOverlay,
                Modifier.align(Alignment.BottomStart).padding(start = inset, end = inset, bottom = (imagePaddingDp + 45).dp).fillMaxWidth().height(230.dp))
        }
    }
}

@Composable
private fun SleepScreen(showClock: Boolean, showDate: Boolean, showWeather: Boolean, weatherLabel: String, glassOverlay: Boolean, calendarEvents: List<CalendarEvent>, phoneContent: PhoneContent, uploadUrl: String?, onOpenUploadQr: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    val date = remember(now) { java.text.SimpleDateFormat("EEEE, d. MMMM", java.util.Locale.getDefault()).format(java.util.Date(now)) }
    val time = remember(now) { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 42.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (uploadUrl != null) PersistentQrBadge(uploadUrl, Modifier.size(32.dp), onOpenUploadQr, dotColor = android.graphics.Color.rgb(112, 118, 126))
            if (showClock) Text(time, color = Color(0xFF686D75), fontSize = 68.sp, fontWeight = FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
            if (showDate) Text(date, color = Color(0xFF555B63), fontSize = 19.sp)
            if (showWeather && weatherLabel.isNotBlank()) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Cloud, contentDescription = "Wetter", tint = Color(0xFF555B63), modifier = Modifier.size(19.dp).padding(end = 3.dp))
                    Text(weatherLabel, color = Color(0xFF555B63), fontSize = 19.sp)
                }
            }
            if (calendarEvents.isNotEmpty() || phoneContent.hasContent) {
                AgendaAndPhoneCard(calendarEvents, phoneContent, false,
                    Modifier.padding(top = 16.dp).fillMaxWidth(0.86f).height(230.dp), dimmed = true)
            }
        }
    }
}

@Composable
private fun AgendaAndPhoneCard(
    events: List<CalendarEvent>,
    content: PhoneContent,
    glass: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false
) {
    val shape = RoundedCornerShape(16.dp)
    val calendarTodos = events.asSequence()
        .filter { it.kind == CalendarEventKind.TODO }
        .sortedBy { it.startsAt.time }
        .take(5)
        .map { event -> PhoneTodo(calendarTodoDisplayText(event), false) }
        .toList()
    val displayedContent = content.copy(todos = calendarTodos + content.todos)
    val agendaEvents = events.filterNot { it.kind == CalendarEventKind.TODO }
    val background = when {
        dimmed -> Modifier.clip(shape).background(Color(0x4420262D)).border(1.dp, Color(0x44565F69), shape)
        glass -> Modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0x44FFFFFF), Color(0x66404D5A), Color(0xDD101820)))).border(1.dp, Color(0x668E9AAA), shape)
        else -> Modifier.clip(shape).background(Color(0xAA0E1116))
    }
    val hasEvents = agendaEvents.isNotEmpty()
    val hasPhoneContent = displayedContent.hasContent
    Row(modifier.then(background).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasEvents) CalendarAgenda(agendaEvents, glass, Modifier.weight(1f).fillMaxHeight(), dimmed, drawCardBackground = false)
        if (hasEvents && hasPhoneContent) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(if (dimmed) Color(0x333F464E) else Color(0x558E9AAA)))
        }
        if (hasPhoneContent) PhoneListsCard(displayedContent, glass, Modifier.weight(1f).fillMaxHeight(), dimmed, drawCardBackground = false)
    }
}

private fun calendarTodoDisplayText(event: CalendarEvent): String {
    val date = java.text.SimpleDateFormat("EEE d.M.", java.util.Locale.getDefault()).format(event.startsAt)
    val time = if (event.allDay) "" else " · ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(event.startsAt)}"
    val title = calendarTodoText(event.title) ?: event.title
    return "$date$time · $title"
}

@Composable
private fun NoteCard(text: String, glass: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val background = if (glass) Modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0x55FFFFFF), Color(0x443D536A), Color(0xAA141B25)))).border(1.dp, Color(0x66FFFFFF), shape)
        else Modifier.clip(shape).background(Color(0xAA0E1116))
    Text(text, color = if (glass) Color.White else Color(0xFFB4BAC2), fontSize = 15.sp, maxLines = 4, modifier = modifier.widthIn(max = 320.dp).then(background).padding(horizontal = 14.dp, vertical = 10.dp))
}

@Composable
private fun CalendarAgenda(events: List<CalendarEvent>, glass: Boolean, modifier: Modifier = Modifier, dimmed: Boolean = false, drawCardBackground: Boolean = true) {
    val zone = java.util.TimeZone.getDefault()
    val now = java.util.Date()
    val todayDate = dayStart(now, zone)
    val today = dayKey(todayDate, zone)
    val weekEnd = dayKey(addDays(dayStart(todayDate, zone), 7, zone), zone)
    val upcoming = events.filter { event ->
        if (event.allDay) dayKey(event.startsAt, zone) >= today else !event.startsAt.before(now)
    }.sortedBy { it.startsAt.time }.take(5)
    val grouped = listOf(
        "Heute" to upcoming.filter { dayKey(it.startsAt, zone) == today },
        "Diese Woche" to upcoming.filter { val day = dayKey(it.startsAt, zone); day > today && day <= weekEnd },
        "Bald" to upcoming.filter { dayKey(it.startsAt, zone) > weekEnd }
    ).filter { it.second.isNotEmpty() }
    if (grouped.isEmpty()) return
    val shape = RoundedCornerShape(16.dp)
    val background = when {
        dimmed -> Modifier.clip(shape).background(Color(0x4420262D)).border(1.dp, Color(0x44565F69), shape)
        glass -> Modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0x44FFFFFF), Color(0x66404D5A), Color(0xDD101820)))).border(1.dp, Color(0x668E9AAA), shape)
        else -> Modifier.clip(shape).background(Color(0xAA0E1116))
    }
    val headingColor = if (dimmed) Color(0xFF747C85) else Color(0xFFE8C98C)
    val eventColor = if (dimmed) Color(0xFF818991) else Color.White
    Column(modifier.then(if (drawCardBackground) background else Modifier).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp)) {
        grouped.forEach { (label, items) ->
            Text(label, color = headingColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            items.forEach { event ->
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    val normalTint = if (dimmed) Color(0xFF777F88) else Color(0xFFE0E5EB)
                    val (icon, tint, description) = when (event.kind) {
                        CalendarEventKind.BIRTHDAY -> Triple(Icons.Default.Cake, if (dimmed) Color(0xFF85817A) else Color(0xFFFFD27A), "Geburtstag")
                        CalendarEventKind.GARBAGE -> Triple(Icons.Default.DeleteOutline, if (dimmed) Color(0xFF777F88) else Color(0xFFB9D4E8), "Müllabfuhr")
                        else -> Triple(Icons.Default.Event, normalTint, "Termin")
                    }
                    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(14.dp))
                    val eventDay = dayKey(event.startsAt, zone)
                    val datePrefix = when {
                        eventDay == today -> ""
                        eventDay <= weekEnd -> java.text.SimpleDateFormat("EEE · ", java.util.Locale.getDefault()).format(event.startsAt)
                        else -> java.text.SimpleDateFormat("EEE d.M. · ", java.util.Locale.getDefault()).format(event.startsAt)
                    }
                    val time = if (event.allDay) "" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(event.startsAt) + " · "
                    Text("$datePrefix$time${event.title}", color = eventColor, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun PhoneListsCard(content: PhoneContent, glass: Boolean, modifier: Modifier = Modifier, dimmed: Boolean = false, drawCardBackground: Boolean = true) {
    val shape = RoundedCornerShape(16.dp)
    val background = when {
        dimmed -> Modifier.clip(shape).background(Color(0x4420262D)).border(1.dp, Color(0x44565F69), shape)
        glass -> Modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0x44FFFFFF), Color(0x66404D5A), Color(0xDD101820)))).border(1.dp, Color(0x668E9AAA), shape)
        else -> Modifier.clip(shape).background(Color(0xAA0E1116))
    }
    val mainText = if (dimmed) Color(0xFF818991) else Color.White
    val heading = if (dimmed) Color(0xFF747C85) else Color(0xFFE8C98C)
    Column(modifier.then(if (drawCardBackground) background else Modifier).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp)) {
        if (content.todos.isNotEmpty()) {
            Text("Aufgaben", color = heading, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            content.todos.forEach { todo ->
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.Top) {
                    Icon(if (todo.done) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, contentDescription = if (todo.done) "Erledigt" else "Offen", tint = mainText, modifier = Modifier.size(14.dp))
                    Text(todo.text, color = mainText, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(start = 5.dp))
                }
            }
        }
        if (content.notes.isNotEmpty()) {
            Text("Notizen", color = heading, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = if (content.todos.isNotEmpty()) 6.dp else 0.dp))
            content.notes.forEach { note ->
                Text(note.text, color = mainText, fontSize = 10.sp, maxLines = 3, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun PhoneSectionCard(title: String, rows: List<String>, glass: Boolean, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val shape = RoundedCornerShape(16.dp)
    val background = when {
        dimmed -> Modifier.clip(shape).background(Color(0x4420262D)).border(1.dp, Color(0x44565F69), shape)
        glass -> Modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0x44FFFFFF), Color(0x66404D5A), Color(0xDD101820)))).border(1.dp, Color(0x668E9AAA), shape)
        else -> Modifier.clip(shape).background(Color(0xAA0E1116))
    }
    Column(modifier.then(background).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 7.dp)) {
        Text(title, color = if (dimmed) Color(0xFF747C85) else Color(0xFFE8C98C), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        rows.forEach { row -> Text(row, color = if (dimmed) Color(0xFF818991) else Color.White, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp)) }
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

private fun transitionName(style: String) = when (style) { "cut" -> "Direkt"; "crossfade" -> "Sanft abblenden"; else -> "Abblenden" }
private fun transitionDurationMillis(style: String) = when (style) { "cut" -> 0; "crossfade" -> 750; else -> 850 }
private fun imageScaleName(mode: String) = when (mode) { "fill" -> "Füllen"; "stretch" -> "Strecken"; "ken_burns" -> "Zoom & Schwenk"; else -> "Einpassen" }
private fun selectedAlbumLabel(images: List<String>, index: Int) = images.getOrNull(index)?.contains("upload-albums") == true

private data class PhotoHistory(val addedAt: Long, val viewCount: Int, val lastViewedAt: Long)

private data class PhoneNote(val text: String)
private data class PhoneTodo(val text: String, val done: Boolean)
private data class PhoneContent(val notes: List<PhoneNote> = emptyList(), val todos: List<PhoneTodo> = emptyList()) {
    val hasContent: Boolean get() = notes.isNotEmpty() || todos.isNotEmpty()
}

private fun parsePhoneContent(raw: String?, legacyNote: String): PhoneContent = runCatching {
    val json = JSONObject(raw ?: "{}")
    val notes = json.optJSONArray("notes")
    val todos = json.optJSONArray("todos")
    PhoneContent(
        notes = if (notes == null) emptyList() else (0 until notes.length()).mapNotNull { index -> notes.optJSONObject(index)?.optString("text")?.takeIf(String::isNotBlank)?.let(::PhoneNote) },
        todos = if (todos == null) emptyList() else (0 until todos.length()).mapNotNull { index ->
            todos.optJSONObject(index)?.let { item -> item.optString("text").takeIf(String::isNotBlank)?.let { PhoneTodo(it, item.optBoolean("done")) } }
        }
    )
}.getOrElse {
    PhoneContent(notes = legacyNote.lines().map(String::trim).filter(String::isNotBlank).map(::PhoneNote))
}.let { parsed ->
    if (!parsed.hasContent && legacyNote.isNotBlank()) PhoneContent(notes = legacyNote.lines().map(String::trim).filter(String::isNotBlank).map(::PhoneNote)) else parsed
}

private fun serializePhoneContent(content: PhoneContent): String = JSONObject()
    .put("notes", org.json.JSONArray().apply { content.notes.forEach { put(JSONObject().put("text", it.text)) } })
    .put("todos", org.json.JSONArray().apply { content.todos.forEach { put(JSONObject().put("text", it.text).put("done", it.done)) } })
    .toString()

private fun calendarTitleKey(title: String): String = java.text.Normalizer
    .normalize(title, java.text.Normalizer.Form.NFKC)
    .trim()
    .replace(Regex("\\s+"), " ")
    .lowercase(java.util.Locale.ROOT)

private fun parseCalendarTitleList(raw: String?): List<String> = runCatching {
    val array = org.json.JSONArray(raw.orEmpty())
    (0 until array.length())
        .mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
        .distinctBy(::calendarTitleKey)
}.getOrDefault(emptyList())

private fun serializeCalendarSnapshot(events: List<CalendarEvent>, hiddenTitles: List<String>, manualBlacklist: String): String {
    val terms = manualBlacklist.split(',', ';', '\n').map(String::trim).filter(String::isNotBlank)
    return JSONObject().put("events", org.json.JSONArray().apply {
        events.take(300).forEach { event ->
            put(JSONObject()
                .put("title", event.title)
                .put("startsAt", event.startsAt.time)
                .put("allDay", event.allDay)
                .put("kind", event.kind.name.lowercase())
                .put("hiddenBySettings", terms.any { event.title.contains(it, ignoreCase = true) })
            )
        }
    }).put("hiddenTitles", org.json.JSONArray().apply { hiddenTitles.forEach { put(it) } })
        .toString()
}

private fun loadPhotoHistory(raw: String?): Map<String, PhotoHistory> = runCatching {
    val root = JSONObject(raw ?: "{}")
    buildMap {
        root.keys().forEach { key ->
            val item = root.optJSONObject(key) ?: return@forEach
            put(key, PhotoHistory(item.optLong("addedAt"), item.optInt("viewCount"), item.optLong("lastViewedAt")))
        }
    }
}.getOrDefault(emptyMap())

private fun savePhotoHistory(history: Map<String, PhotoHistory>): String = JSONObject().apply {
    history.forEach { (key, value) -> put(key, JSONObject().put("addedAt", value.addedAt).put("viewCount", value.viewCount).put("lastViewedAt", value.lastViewedAt)) }
}.toString()

private fun writeAtomicFile(atomicFile: AtomicFile, contents: String) {
    val stream = atomicFile.startWrite()
    try {
        stream.write(contents.toByteArray(Charsets.UTF_8))
        atomicFile.finishWrite(stream)
    } catch (error: Throwable) {
        atomicFile.failWrite(stream)
        throw error
    }
}

private fun chooseNextImage(
    images: List<String>, history: Map<String, PhotoHistory>, currentIndex: Int, shuffle: Boolean, failed: Set<String>
): Int {
    if (images.size <= 1) return currentIndex
    val usable = images.indices.filter { it != currentIndex && images[it].removePrefix("uri:").removePrefix("file:") !in failed }
    if (usable.isEmpty()) return currentIndex
    if (!shuffle) {
        for (step in 1 until images.size) {
            val candidate = (currentIndex + step) % images.size
            if (candidate in usable) return candidate
        }
        return currentIndex
    }
    return chooseWeightedUnderseen(images, history, usable)
}

private fun chooseWeightedUnderseen(images: List<String>, history: Map<String, PhotoHistory>, candidates: List<Int>): Int {
    if (candidates.isEmpty()) return 0
    val minimum = candidates.minOf { history[images[it]]?.viewCount ?: 0 }
    // Permit a view-count spread of three while strongly favoring unseen photos.
    val eligible = candidates.filter { (history[images[it]]?.viewCount ?: 0) <= minimum + 3 }
    val weighted = eligible.flatMap { index ->
        val count = history[images[index]]?.viewCount ?: 0
        List((4 - (count - minimum)).coerceAtLeast(1)) { index }
    }
    return weighted.random()
}

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
    calendarEnabled: Boolean,
    calendarFeedUrl: String,
    calendarStatus: String,
    calendarBlacklist: String,
    notesEnabled: Boolean,
    noteText: String,
    onFocusedNode: (Int) -> Unit,
    onAutoStartOnBoot: (Boolean) -> Unit,
    onShowClock: (Boolean) -> Unit,
    onShowDate: (Boolean) -> Unit,
    onShowWeather: (Boolean) -> Unit,
    onCalendarEnabled: (Boolean) -> Unit,
    onCalendarFeedUrl: (String) -> Unit,
    onCalendarBlacklist: (String) -> Unit,
    onNotesEnabled: (Boolean) -> Unit,
    onNoteText: (String) -> Unit,
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
    val calendarUrlFocus = remember { FocusRequester() }
    val calendarBlacklistFocus = remember { FocusRequester() }
    val noteFocus = remember { FocusRequester() }
    LaunchedEffect(focusedSettingsNode) {
        val position = (settingsScroll.maxValue * (focusedSettingsNode.coerceIn(0, 22) / 22f)).toInt()
        runCatching { settingsScroll.animateScrollTo(position) }
        delay(50)
        runCatching {
            when (focusedSettingsNode) {
                9 -> countryFocus.requestFocus()
                10 -> postalFocus.requestFocus()
                15 -> scheduleStartFocus.requestFocus()
                16 -> scheduleEndFocus.requestFocus()
                19 -> calendarUrlFocus.requestFocus()
                20 -> calendarBlacklistFocus.requestFocus()
                22 -> noteFocus.requestFocus()
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
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (focusedSettingsNode == 2) RemoteFocusSurface else Color.Transparent)
                    .then(if (focusedSettingsNode == 2) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("Bildrand · ${imagePaddingDp} dp", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Slider(value = imagePaddingDp.toFloat(), onValueChange = { onImagePadding((it / 4f).toInt() * 4) }, valueRange = 0f..32f, steps = 7,
                        modifier = Modifier.focusProperties { canFocus = false })
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Schwenken & Zoomen", fontWeight = FontWeight.SemiBold)
                        Text("Jedes Bild zoomt sanft. Passt das Seitenverhältnis nicht, füllt es die Höhe oder Breite und schwenkt vollständig durch, bevor das nächste Bild kommt.", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = panZoom,
                        onCheckedChange = onPanZoom,
                        modifier = Modifier.focusProperties { canFocus = false }
                            .then(if (focusedSettingsNode == 3) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(24.dp)) else Modifier)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text("Bilddauer · ${seconds}s", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = seconds.toFloat(),
                    onValueChange = { onSeconds(it.toInt().coerceIn(5, 60)) },
                    valueRange = 5f..60f,
                    modifier = Modifier.focusProperties { canFocus = false }
                        .then(if (focusedSettingsNode == 4) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier)
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
                            .then(if (focusedSettingsNode == 5) Modifier.border(4.dp, RemoteFocus, RoundedCornerShape(24.dp)) else Modifier)
                    )
                }
                Spacer(Modifier.height(10.dp))
                SettingsToggle("Uhr im Schlafmodus", "Im Ruhebildschirm die Uhrzeit zeigen.", showClock, 6, focusedSettingsNode, onShowClock)
                SettingsToggle("Datum im Schlafmodus", "Datum unter der Uhr anzeigen.", showDate, 7, focusedSettingsNode, onShowDate)
                SettingsToggle("Wetter anzeigen", "Aktuelles Wetter unten in der Ecke.", showWeather, 8, focusedSettingsNode, onShowWeather)
                Text("Wetter-Ort", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                Text("Land (ISO-Code) und Postleitzahl, zum Beispiel DE · 10115", color = Color(0xFFB8C1CC), fontSize = 12.sp)
                OutlinedTextField(
                    value = weatherCountry,
                    onValueChange = onWeatherCountry,
                    label = { Text("Land") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(countryFocus).onFocusChanged { if (it.isFocused) onFocusedNode(9) }
                        .then(if (focusedSettingsNode == 9) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier)
                )
                OutlinedTextField(
                    value = weatherPostal,
                    onValueChange = onWeatherPostal,
                    label = { Text("Postleitzahl") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(postalFocus).onFocusChanged { if (it.isFocused) onFocusedNode(10) }
                        .then(if (focusedSettingsNode == 10) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier)
                )
                Spacer(Modifier.height(12.dp))
                Text("Übergang · ${transitionName(transitionStyle)}", color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (focusedSettingsNode == 11) RemoteFocusSurface else Color.Transparent).clickable { onTransitionStyle(cycleValue(transitionStyle, listOf("fade", "crossfade", "cut"), 1)) }.padding(12.dp))
                Text("Bilddarstellung · ${imageScaleName(imageScaleMode)}", color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (focusedSettingsNode == 12) RemoteFocusSurface else Color.Transparent).clickable { onImageScaleMode(cycleValue(imageScaleMode, listOf("fit", "fill", "stretch", "ken_burns"), 1)) }.padding(12.dp))
                SettingsToggle("Zeitplan verwenden", "Außerhalb der aktiven Zeit automatisch schlafen.", scheduleEnabled, 13, focusedSettingsNode, onScheduleEnabled)
                Text("Aktive Tage · ${if (scheduleWeekdaysOnly) "Montag bis Freitag" else "Täglich"}", color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (focusedSettingsNode == 14) RemoteFocusSurface else Color.Transparent).clickable { onScheduleDays(!scheduleWeekdaysOnly) }.padding(12.dp))
                Text("Aktive Zeit", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                Text("${if (scheduleWeekdaysOnly) "Mo–Fr" else "Jeden Tag"} ${scheduleStart.ifBlank { "16:00" }}–${scheduleEnd.ifBlank { "21:00" }} · außerhalb zeigt die App den Schlafbildschirm.", color = Color(0xFFB8C1CC), fontSize = 12.sp)
                SettingsToggle("Liquid-Glass-Optik", "Leichtes Glas mit transparentem Verlauf und Lichtkante für Uhr, Datum und Wetter.", glassOverlay, 17, focusedSettingsNode, onGlassOverlay)
                Spacer(Modifier.height(10.dp))
                SettingsToggle("Kalender anzeigen", "Anstehende Termine mit Glass-Karte, heute, diese Woche und bald.", calendarEnabled, 18, focusedSettingsNode, onCalendarEnabled)
                Text("iCloud- oder iCal-Adresse", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text("Private Kalenderadresse bleibt nur auf diesem Gerät gespeichert. webcal:// wird sicher über HTTPS geladen.", color = Color(0xFFB8C1CC), fontSize = 12.sp)
                OutlinedTextField(
                    value = calendarFeedUrl,
                    onValueChange = onCalendarFeedUrl,
                    label = { Text("webcal://… oder https://…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(calendarUrlFocus).onFocusChanged { if (it.isFocused) onFocusedNode(19) }
                        .then(if (focusedSettingsNode == 19) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier)
                )
                if (calendarStatus.isNotBlank()) Text(calendarStatus, color = if (calendarStatus.contains("geladen")) Color(0xFF9FE3B1) else Color(0xFFFFB4A9), fontSize = 12.sp)
                OutlinedTextField(
                    value = calendarBlacklist,
                    onValueChange = onCalendarBlacklist,
                    label = { Text("Kalendereinträge ausblenden") },
                    placeholder = { Text("Wiegetag, Beispielname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(calendarBlacklistFocus).onFocusChanged { if (it.isFocused) onFocusedNode(20) }
                        .then(if (focusedSettingsNode == 20) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier)
                )
                Text("Kommagetrennte Wörter oder Namen. Termine, deren Titel einen Eintrag enthält, werden nicht angezeigt.", color = Color(0xFFB8C1CC), fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                SettingsToggle("Notiz anzeigen", "Eine kurze persönliche Notiz über Fotos legen, auch im Schlafbildschirm.", notesEnabled, 21, focusedSettingsNode, onNotesEnabled)
                OutlinedTextField(value = noteText, onValueChange = onNoteText, label = { Text("Notiz · bis 240 Zeichen") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().focusRequester(noteFocus).onFocusChanged { if (it.isFocused) onFocusedNode(22) }.then(if (focusedSettingsNode == 22) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = scheduleStart, onValueChange = { value -> if (value.length <= 5 && value.all { it.isDigit() || it == ':' }) onScheduleStart(value) }, label = { Text("Von · HH:MM") }, singleLine = true,
                        modifier = Modifier.weight(1f).focusRequester(scheduleStartFocus).onFocusChanged { if (it.isFocused) onFocusedNode(15) }.then(if (focusedSettingsNode == 15) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier))
                    OutlinedTextField(value = scheduleEnd, onValueChange = { value -> if (value.length <= 5 && value.all { it.isDigit() || it == ':' }) onScheduleEnd(value) }, label = { Text("Bis · HH:MM") }, singleLine = true,
                        modifier = Modifier.weight(1f).focusRequester(scheduleEndFocus).onFocusChanged { if (it.isFocused) onFocusedNode(16) }.then(if (focusedSettingsNode == 16) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier))
                }
            }
        }
    }
}

@Composable
private fun SettingsToggle(title: String, description: String, checked: Boolean, node: Int, focusedNode: Int, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (focusedNode == node) RemoteFocusSurface else Color.Transparent)
        .then(if (focusedNode == node) Modifier.border(3.dp, RemoteFocus, RoundedCornerShape(12.dp)) else Modifier)
        .clickable { onToggle(!checked) }.padding(vertical = 6.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
    val language = java.util.Locale.getDefault().language.ifBlank { "en" }
    val geocodeUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encodedPostal&count=10&language=$language&format=json&countryCode=$encodedCountry"
    var placeName: String? = null
    var latitude = Double.NaN
    var longitude = Double.NaN
    runCatching {
        client.newCall(Request.Builder().url(geocodeUrl).build()).execute().use { response ->
            if (response.isSuccessful) {
                val places = JSONObject(response.body?.string().orEmpty()).optJSONArray("results")
                if (places != null && places.length() > 0) {
                    val place = places.getJSONObject(0)
                    latitude = place.optDouble("latitude", Double.NaN)
                    longitude = place.optDouble("longitude", Double.NaN)
                    placeName = place.optString("name").takeIf { it.isNotBlank() }
                }
            }
        }
    }
    // Open-Meteo's postal-code search misses some valid German PLZs. Use a
    // postal-code geocoder as fallback, then keep Open-Meteo for the forecast.
    if (!latitude.isFinite() || !longitude.isFinite()) {
        val postalUrl = "https://api.zippopotam.us/${country.trim().uppercase()}/$encodedPostal"
        runCatching {
            client.newCall(Request.Builder().url(postalUrl).build()).execute().use { response ->
                if (response.isSuccessful) {
                    val places = JSONObject(response.body?.string().orEmpty()).optJSONArray("places")
                    if (places != null && places.length() > 0) {
                        val place = places.getJSONObject(0)
                        latitude = place.optString("latitude").toDoubleOrNull() ?: Double.NaN
                        longitude = place.optString("longitude").toDoubleOrNull() ?: Double.NaN
                        placeName = place.optString("place name").takeIf { it.isNotBlank() }
                    }
                }
            }
        }
    }
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
    "${placeName.orEmpty()} · ${temp.toInt()}°C · $description"
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
private fun PersistentQrBadge(url: String, modifier: Modifier = Modifier, onClick: () -> Unit, dotColor: Int = android.graphics.Color.BLACK) {
    val bitmap = remember(url) {
        runCatching {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 280, 280)
            android.graphics.Bitmap.createBitmap(280, 280, android.graphics.Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until 280) for (y in 0 until 280) if (matrix[x, y]) setPixel(x, y, dotColor)
            }.asImageBitmap()
        }.getOrNull()
    }
    Box(modifier.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, "Schwarzer QR-Code zum Hinzufügen von Notizen, Aufgaben oder Fotos", Modifier.fillMaxSize())
        else Icon(Icons.Default.QrCode2, "Notizen, Aufgaben oder Fotos hinzufügen", tint = Color.Black, modifier = Modifier.fillMaxSize())
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
