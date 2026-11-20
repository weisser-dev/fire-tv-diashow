package dev.weisser.googlephotoscarousel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Google Photos Picker bridge. Only media explicitly selected in a session is downloaded. */
class PhotosPickerRepository(context: Context) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun createSession(token: String): PickerSession = withContext(Dispatchers.IO) {
        val response = request("POST", "https://photospicker.googleapis.com/v1/sessions", token, "{\"pickingConfig\":{\"maxItemCount\":\"500\"}}")
        val config = response.optJSONObject("pollingConfig")
        PickerSession(
            response.getString("id"),
            response.getString("pickerUri"),
            durationMillis(config?.optString("pollInterval"), 5_000L),
            durationMillis(config?.optString("timeoutIn"), 15 * 60_000L)
        )
    }

    suspend fun deleteSession(token: String, sessionId: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://photospicker.googleapis.com/v1/sessions/$sessionId")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Die Fotoauswahl konnte nicht beendet werden (${response.code})." }
        }
    }

    suspend fun waitAndDownload(token: String, session: PickerSession): List<String> = withContext(Dispatchers.IO) {
        var pollInterval = session.pollIntervalMillis
        var timeout = session.timeoutMillis
        var deadline = System.currentTimeMillis() + timeout
        var ready = false
        while (System.currentTimeMillis() < deadline) {
            delay(pollInterval)
            val status = request("GET", "https://photospicker.googleapis.com/v1/sessions/${session.id}", token)
            if (status.optBoolean("mediaItemsSet")) {
                ready = true
                break
            }
            status.optJSONObject("pollingConfig")?.let { config ->
                pollInterval = durationMillis(config.optString("pollInterval"), pollInterval)
                timeout = durationMillis(config.optString("timeoutIn"), timeout)
                deadline = System.currentTimeMillis() + timeout
            }
        }
        check(ready) { "Google Photos hat die Auswahl noch nicht bestätigt. Bitte starte sie erneut." }

        val urls = mutableListOf<Pair<String, String>>()
        var pageToken: String? = null
        do {
            val suffix = buildString {
                append("?sessionId=${session.id}&pageSize=100")
                if (pageToken != null) append("&pageToken=$pageToken")
            }
            val page = request("GET", "https://photospicker.googleapis.com/v1/mediaItems$suffix", token)
            val items = page.optJSONArray("mediaItems")
            if (items != null) for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val file = item.optJSONObject("mediaFile") ?: continue
                val mime = file.optString("mimeType")
                if (mime.startsWith("image/")) {
                    val baseUrl = file.optString("baseUrl")
                    val filename = file.optString("filename", "photo-${item.optString("id")}.jpg")
                    if (baseUrl.isNotBlank()) urls += baseUrl + "=w3840-h3840" to filename
                }
            }
            pageToken = page.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)

        check(urls.isNotEmpty()) { "In dieser Auswahl wurden keine Fotos gefunden." }
        val folder = File(appContext.filesDir, "google-photos").apply { mkdirs() }
        urls.mapIndexedNotNull { index, (url, name) ->
            runCatching {
                val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Foto konnte nicht geladen werden (${response.code})." }
                    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
                    val file = File(folder, "${index}_$safeName")
                    file.outputStream().use { output -> response.body!!.byteStream().copyTo(output) }
                    file.absolutePath
                }
            }.getOrNull()
        }
    }

    private fun request(method: String, url: String, token: String, body: String? = null): JSONObject {
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
        if (method == "POST") builder.post((body ?: "{}").toRequestBody()) else builder.get()
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                val detail = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                detail?.takeIf { it.isNotBlank() } ?: "Google Photos API Fehler (${response.code}). Prüfe API-Aktivierung und OAuth-Konfiguration."
            }
            return JSONObject(text)
        }
    }

    private fun durationMillis(value: String?, fallback: Long): Long =
        value?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * 1_000).toLong().coerceAtLeast(1_000L) } ?: fallback
}

data class PickerSession(val id: String, val uri: String, val pollIntervalMillis: Long, val timeoutMillis: Long)
