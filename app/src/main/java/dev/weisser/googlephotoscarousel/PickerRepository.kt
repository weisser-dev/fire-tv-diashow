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
import java.util.concurrent.TimeUnit

/** Google Photos Picker API: OAuth device flow, remote selection, then durable local copies. */
class PickerRepository(context: Context) {
    private val app = context.applicationContext
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    fun isConfigured() = BuildConfig.AMBIENT_CLIENT_ID.isNotBlank() && BuildConfig.AMBIENT_CLIENT_SECRET.isNotBlank()

    suspend fun requestDeviceCode(): DeviceCode = withContext(Dispatchers.IO) {
        check(isConfigured()) { "Google-OAuth ist in diesem Build nicht konfiguriert." }
        val form = FormBody.Builder()
            .add("client_id", BuildConfig.AMBIENT_CLIENT_ID)
            .add("scope", "https://www.googleapis.com/auth/photospicker.mediaitems.readonly")
            .build()
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
            val response = http.newCall(Request.Builder().url("https://oauth2.googleapis.com/token").post(form).build()).execute().use { it.code to it.body?.string().orEmpty() }
            val json = JSONObject(response.second)
            if (response.first in 200..299) return@withContext json.getString("access_token")
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

    suspend fun createSession(token: String): PickerSession = withContext(Dispatchers.IO) {
        val json = call(Request.Builder().url("https://photospicker.googleapis.com/v1/sessions")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody(JSON)).build())
        PickerSession(json.getString("id"), json.getString("pickerUri"), json.optJSONObject("pollingConfig")?.optString("pollInterval")?.removeSuffix("s")?.toLongOrNull() ?: 3L,
            json.optJSONObject("pollingConfig")?.optString("timeoutIn")?.removeSuffix("s")?.toLongOrNull() ?: 600L)
    }

    suspend fun waitAndDownload(token: String, session: PickerSession, onStatus: (String) -> Unit): List<String> = withContext(Dispatchers.IO) {
        try {
            val deadline = System.currentTimeMillis() + session.timeoutSeconds.coerceAtLeast(60) * 1000
            var interval = session.pollSeconds.coerceIn(2, 30)
            while (System.currentTimeMillis() < deadline) {
                val state = call(Request.Builder().url("https://photospicker.googleapis.com/v1/sessions/${session.id}")
                    .header("Authorization", "Bearer $token").get().build())
                if (state.optBoolean("mediaItemsSet")) return@withContext downloadMedia(token, session.id)
                val config = state.optJSONObject("pollingConfig")
                interval = config?.optString("pollInterval")?.removeSuffix("s")?.toLongOrNull()?.coerceIn(2, 30) ?: interval
                onStatus("Wähle Fotos auf dem Handy aus. Danach hier auf „Fertig“ tippen.")
                delay(interval * 1000)
            }
            error("Die Fotoauswahl ist abgelaufen. Bitte erneut verbinden.")
        } finally {
            runCatching {
                http.newCall(Request.Builder().url("https://photospicker.googleapis.com/v1/sessions/${session.id}")
                    .header("Authorization", "Bearer $token").delete().build()).execute().close()
            }
        }
    }

    private suspend fun downloadMedia(token: String, sessionId: String): List<String> = withContext(Dispatchers.IO) {
        val folder = File(app.filesDir, "google-photos").apply { mkdirs() }
        val paths = mutableListOf<String>()
        var pageToken: String? = null
        var index = 0
        do {
            val query = buildString {
                append("?sessionId=${java.net.URLEncoder.encode(sessionId, "UTF-8")}&pageSize=100")
                pageToken?.let { append("&pageToken=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            }
            val page = call(Request.Builder().url("https://photospicker.googleapis.com/v1/mediaItems$query")
                .header("Authorization", "Bearer $token").get().build())
            val items = page.optJSONArray("mediaItems")
            if (items != null) for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val media = item.optJSONObject("mediaFile") ?: continue
                if (!media.optString("mimeType").startsWith("image/")) continue
                val baseUrl = media.optString("baseUrl")
                if (baseUrl.isBlank()) continue
                val name = (media.optString("filename").ifBlank { "photo-${item.optString("id")}.jpg" })
                    .replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
                val output = File(folder, "picker_${index++}_$name")
                http.newCall(Request.Builder().url("$baseUrl=w2048-h2048").header("Authorization", "Bearer $token").get().build())
                    .execute().use { response ->
                        check(response.isSuccessful) { "Ein ausgewähltes Foto konnte nicht geladen werden (${response.code})." }
                        output.outputStream().use { response.body!!.byteStream().copyTo(it) }
                    }
                paths += output.absolutePath
            }
            pageToken = page.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (pageToken != null)
        check(paths.isNotEmpty()) { "Keine Bilder ausgewählt. Wähle mindestens ein Foto aus." }
        paths
    }

    private fun call(request: Request): JSONObject = http.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        check(response.isSuccessful) {
            runCatching { JSONObject(raw).getJSONObject("error").optString("message") }.getOrNull()?.ifBlank { null }
                ?: "Google Photos Picker API Fehler (${response.code})."
        }
        JSONObject(raw)
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
}

data class PickerSession(val id: String, val uri: String, val pollSeconds: Long, val timeoutSeconds: Long)
