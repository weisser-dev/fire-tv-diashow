package dev.weisser.googlephotoscarousel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
        val state = JSONObject().put("requestId", requestId).put("displayName", "Photo Carousel").toString()
        val form = FormBody.Builder().add("client_id", BuildConfig.AMBIENT_CLIENT_ID)
            .add("scope", "profile https://www.googleapis.com/auth/photosambient.mediaitems").add("state", state).build()
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
                val token = json.getString("access_token")
                json.optString("refresh_token").takeIf { it.isNotBlank() }?.let(::saveSecret)
                return@withContext token
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
            if (current.optBoolean("mediaSourcesSet")) return@withContext downloadMedia(token, device.id)
            onStatus("Wähle jetzt ein Album in Google Fotos aus …")
            delay((current.optJSONObject("pollingConfig")?.optString("pollInterval")?.removeSuffix("s")?.toLongOrNull() ?: device.pollSeconds).coerceAtLeast(5) * 1000)
        }
        error("Die Albumauswahl wurde nicht abgeschlossen. Scanne den QR-Code später erneut.")
    }

    private suspend fun downloadMedia(token: String, deviceId: String): List<String> = withContext(Dispatchers.IO) {
        val folder = File(app.filesDir, "google-photos").apply { mkdirs() }
        val paths = mutableListOf<String>(); var pageToken: String? = null; var pageNo = 0
        do {
            val query = buildString { append("?deviceId=$deviceId&pageSize=100"); pageToken?.let { append("&pageToken=$it") } }
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
        check(paths.isNotEmpty()) { "Keine Bilder im ausgewählten Album gefunden." }; paths
    }

    private fun call(request: Request): JSONObject = http.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty(); check(response.isSuccessful) { runCatching { JSONObject(raw).getJSONObject("error").optString("message") }.getOrNull()?.ifBlank { null } ?: "Ambient API Fehler (${response.code})." }; JSONObject(raw)
    }

    fun settingsUri() = prefs.getString("settings_uri", null)
    fun clearDevice() { prefs.edit().remove("device_id").remove("request_id").remove("settings_uri").remove("refresh_token").apply() }
    private fun saveSecret(value: String) { val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secretKey()); val encrypted = cipher.doFinal(value.toByteArray()); prefs.edit().putString("refresh_token", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply() }
    @Suppress("unused") private fun loadSecret(): String? = runCatching { val all = Base64.decode(prefs.getString("refresh_token", null), Base64.NO_WRAP); val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, all.copyOfRange(0, 12))); String(cipher.doFinal(all.copyOfRange(12, all.size))) }.getOrNull()
    private fun secretKey(): SecretKey { val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; (ks.getKey("ambient-token", null) as? SecretKey)?.let { return it }; return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder("ambient-token", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey() }
    private val JSON = "application/json; charset=utf-8".toMediaType()
}

data class DeviceCode(val deviceCode: String, val verificationUrl: String, val userCode: String, val interval: Long, val expiresIn: Long)
data class AmbientDevice(val id: String, val settingsUri: String, val sourcesSet: Boolean, val pollSeconds: Long)
