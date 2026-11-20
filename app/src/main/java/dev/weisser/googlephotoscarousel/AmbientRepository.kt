package dev.weisser.googlephotoscarousel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** OAuth device flow and media source access for Google Photos Ambient API. */
class AmbientRepository(context: Context) {
    private val app = context.applicationContext
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private val prefs = app.getSharedPreferences("ambient", Context.MODE_PRIVATE)

    fun isConfigured() = BuildConfig.AMBIENT_CLIENT_ID.isNotBlank() && BuildConfig.AMBIENT_CLIENT_SECRET.isNotBlank()

    suspend fun requestDeviceCode(): DeviceCode = withContext(Dispatchers.IO) {
        check(isConfigured()) { "Google Ambient OAuth fehlt. Lege einen OAuth-Client vom Typ „TVs and Limited Input devices“ an und konfiguriere AMBIENT_CLIENT_ID sowie AMBIENT_CLIENT_SECRET." }
        val requestId = UUID.randomUUID().toString().also { prefs.edit().putString("request_id", it).apply() }
        val form = FormBody.Builder().add("client_id", BuildConfig.AMBIENT_CLIENT_ID)
            // Use Google's standard two-step flow. The streamlined `state` redirect can
            // open Photos before the TV has created the device, leaving the phone at
            // photos.google.com/c with a device-not-found error.
            .add("scope", "profile https://www.googleapis.com/auth/photosambient.mediaitems").build()
        val json = call(Request.Builder().url("https://oauth2.googleapis.com/device/code").post(form).build())
        DeviceCode(json.getString("device_code"), json.getString("verification_url"), json.optString("user_code"), json.optLong("interval", 5), json.optLong("expires_in", 1800))
    }

    suspend fun authorize(code: DeviceCode, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val end = System.currentTimeMillis() + code.expiresIn * 1000
        while (System.currentTimeMillis() < end) {
            delay(code.interval.coerceAtLeast(5) * 1000)
            val form = FormBody.Builder().add("client_id", BuildConfig.AMBIENT_CLIENT_ID)
                .add("client_secret", BuildConfig.AMBIENT_CLIENT_SECRET).add("device_code", code.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()
            val req = Request.Builder().url("https://oauth2.googleapis.com/token").post(form).build()
            val response = http.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
            val json = JSONObject(response.second)
            if (response.first in 200..299) {
                // The app currently uses the access token only for this connection flow.
                // Do not persist an unused refresh token; Android 5.1 has no AES AndroidKeyStore provider.
                return@withContext json.getString("access_token")
            }
            when (json.optString("error")) {
                "authorization_pending" -> onStatus("Warte auf Bestätigung am Handy …")
                "slow_down" -> delay(5000)
                "access_denied" -> error("Google-Anmeldung wurde abgelehnt.")
                "expired_token" -> error("Der Anmeldecode ist abgelaufen. Bitte erneut verbinden.")
                else -> error(json.optString("error_description", "OAuth-Fehler: ${json.optString("error")}"))
            }
        }
        error("Der Anmeldecode ist abgelaufen. Bitte erneut verbinden.")
    }

    suspend fun createDevice(token: String): AmbientDevice = withContext(Dispatchers.IO) {
        prefs.getString("device_id", null)?.let { existing ->
            val current = call(Request.Builder().url("https://photosambient.googleapis.com/v1/devices/$existing").header("Authorization", "Bearer $token").get().build())
            return@withContext AmbientDevice(existing, current.optString("settingsUri"), current.optBoolean("mediaSourcesSet"), current.optJSONObject("pollingConfig")?.optString("pollInterval")?.removeSuffix("s")?.toLongOrNull() ?: 10L)
        }
        val requestId = prefs.getString("request_id", UUID.randomUUID().toString())!!
        val body = JSONObject().put("displayName", "Photo Carousel").toString().toRequestBody(JSON)
        val data = call(Request.Builder().url("https://photosambient.googleapis.com/v1/devices?requestId=$requestId").header("Authorization", "Bearer $token").post(body).build())
        val deviceId = data.getString("id")
        prefs.edit().putString("device_id", deviceId).putString("settings_uri", data.optString("settingsUri")).apply()
        AmbientDevice(deviceId, data.optString("settingsUri"), data.optBoolean("mediaSourcesSet"), data.optJSONObject("pollingConfig")?.optString("pollInterval")?.removeSuffix("s")?.toLongOrNull() ?: 10L)
    }

    suspend fun waitForSources(token: String, device: AmbientDevice, onStatus: (String) -> Unit): List<String> = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 30 * 60_000
        while (System.currentTimeMillis() < deadline) {
            val current = call(Request.Builder().url("https://photosambient.googleapis.com/v1/devices/${device.id}").header("Authorization", "Bearer $token").get().build())
            if (current.optBoolean("mediaSourcesSet")) {
                val ids = current.optJSONArray("mediaSources")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
                }.orEmpty()
                return@withContext downloadMedia(token, device.id, ids)
            }
            onStatus("Wähle jetzt ein Album in Google Fotos aus …")
            delay((current.optJSONObject("pollingConfig")?.optString("pollInterval")?.removeSuffix("s")?.toLongOrNull() ?: device.pollSeconds).coerceAtLeast(5) * 1000)
        }
        error("Die Albumauswahl wurde nicht abgeschlossen. Scanne den QR-Code später erneut.")
    }

    private suspend fun downloadMedia(token: String, deviceId: String, mediaSourceIds: List<String>): List<String> = withContext(Dispatchers.IO) {
        val folder = File(app.filesDir, "google-photos").apply { mkdirs() }
        val paths = mutableListOf<String>(); var pageNo = 0
        // Fetch every selected source explicitly so a selected album is not reduced to
        // the curated subset returned by listMediaItems when mediaSourceId is omitted.
        for (sourceId in mediaSourceIds.ifEmpty { listOf("") }) {
            var pageToken: String? = null
            do {
                val query = buildString {
                    append("?deviceId=${java.net.URLEncoder.encode(deviceId, "UTF-8")}&pageSize=100")
                    if (sourceId.isNotBlank()) append("&mediaSourceId=${java.net.URLEncoder.encode(sourceId, "UTF-8")}")
                    pageToken?.let { append("&pageToken=${java.net.URLEncoder.encode(it, "UTF-8")}") }
                }
                val page = call(Request.Builder().url("https://photosambient.googleapis.com/v1/mediaItems$query").header("Authorization", "Bearer $token").get().build())
                val items = page.optJSONArray("mediaItems")
                if (items != null) for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i); val media = item.optJSONObject("mediaFile") ?: continue
                    val mime = media.optString("mimeType"); if (!mime.startsWith("image/")) continue
                    val baseUrl = media.optString("baseUrl"); if (baseUrl.isBlank()) continue
                    val name = media.optString("filename", "photo-${item.optString("id")}.jpg").replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
                    runCatching {
                        val req = Request.Builder().url("$baseUrl=w3840-h3840").header("Authorization", "Bearer $token").get().build()
                        http.newCall(req).execute().use { res -> check(res.isSuccessful); val file = File(folder, "${pageNo++}_$name"); file.outputStream().use { res.body!!.byteStream().copyTo(it) }; paths += file.absolutePath }
                    }
                }
                pageToken = page.optString("nextPageToken").takeIf { it.isNotBlank() }
            } while (pageToken != null)
        }
        check(paths.isNotEmpty()) { "Keine Bilder im ausgewählten Album gefunden." }; paths
    }

    private fun call(request: Request): JSONObject = http.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty(); check(response.isSuccessful) { runCatching { JSONObject(raw).getJSONObject("error").optString("message") }.getOrNull()?.ifBlank { null } ?: "Ambient API Fehler (${response.code})." }; JSONObject(raw)
    }

    fun settingsUri() = prefs.getString("settings_uri", null)
    fun clearDevice() { prefs.edit().remove("device_id").remove("request_id").remove("settings_uri").remove("refresh_token").apply() }
    private val JSON = "application/json; charset=utf-8".toMediaType()
}

data class DeviceCode(val deviceCode: String, val verificationUrl: String, val userCode: String, val interval: Long, val expiresIn: Long)
data class AmbientDevice(val id: String, val settingsUri: String, val sourcesSet: Boolean, val pollSeconds: Long)
