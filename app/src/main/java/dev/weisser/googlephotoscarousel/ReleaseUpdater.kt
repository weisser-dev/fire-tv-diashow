package dev.weisser.googlephotoscarousel

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal data class AppRelease(val tag: String, val apkUrl: String)

internal object ReleaseUpdater {
    private const val API_URL = "https://api.github.com/repos/weisser-dev/google-photos-carousel/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/weisser-dev/google-photos-carousel/releases/download/"
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    fun latest(): AppRelease? {
        val request = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Photo-Carousel-Android")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string() ?: return null)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.optString("tag_name")
            if (!tag.matches(Regex("v\\d+\\.\\d+\\.\\d+"))) return null
            val assetExists = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length()).any { assets.optJSONObject(it)?.optString("name") == "app-release.apk" }
            } ?: false
            if (!assetExists) return null
            return AppRelease(tag, "$DOWNLOAD_PREFIX$tag/app-release.apk")
        }
    }

    fun isNewer(remoteTag: String, currentVersion: String): Boolean {
        fun parts(value: String) = value.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val remote = parts(remoteTag)
        val current = parts(currentVersion)
        for (index in 0 until maxOf(remote.size, current.size)) {
            val comparison = (remote.getOrElse(index) { 0 }).compareTo(current.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    fun download(release: AppRelease, destination: File): File {
        require(release.apkUrl.startsWith(DOWNLOAD_PREFIX)) { "Ungültige Download-Adresse." }
        destination.parentFile?.mkdirs()
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", "Photo-Carousel-Android").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Der APK-Download ist fehlgeschlagen (HTTP ${response.code}).")
            val body = response.body ?: error("GitHub hat keine APK-Datei geliefert.")
            if (body.contentLength() > 200L * 1024 * 1024) error("Die APK ist unerwartet groß.")
            body.byteStream().use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
        }
        return destination
    }
}
