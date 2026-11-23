package dev.weisser.googlephotoscarousel

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A generation is made visible by a single preferences commit, after every photo is checked. */
internal class FrameBackup(
    private val context: Context,
    private val library: File,
    private val historySnapshot: () -> Map<String, PhotoHistory>?
) {
    private val prefs = context.getSharedPreferences("carousel", Context.MODE_PRIVATE)
    private val root = context.filesDir
    fun pin() = prefs.getString("upload_pin", "").orEmpty()

    private data class Photo(val key: String, val album: String, val name: String, val modified: Long, val open: () -> InputStream)

    private fun photos(): List<Photo> {
        val uploads = LocalPhotoUploadServer.scan(library).map { item ->
            val file = File(item.path)
            Photo("file:${item.path}", item.album, file.name, item.modifiedAt, file::inputStream)
        }
        val albums = uploads.mapTo(HashSet()) { it.album }
        val localAlbum = generateSequence(1) { it + 1 }.map { if (it == 1) "Dateien" else "Dateien ($it)" }.first { it !in albums }
        val names = HashSet<String>()
        val locals = prefs.getStringSet("local_photos", emptySet()).orEmpty().sorted().mapIndexed { index, value ->
            val uri = Uri.parse(value)
            val resolver = context.contentResolver
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri)) ?: "jpg"
            val name = displayName?.takeIf(::safeComponent) ?: "Foto-${index + 1}.$ext"
            val unique = generateSequence(0) { it + 1 }.map { if (it == 0) name else "$it-$name" }.first(names::add)
            Photo("uri:$value", localAlbum, unique, 0L) { requireNotNull(resolver.openInputStream(uri)) { "Foto kann nicht gelesen werden." } }
        }
        return uploads + locals
    }

    fun export(output: OutputStream) {
        check(library == activeDirectory(root, prefs))
        val photos = photos()
        require(photos.size <= MAX_PHOTOS) { "Die Sammlung ist zu groß." }
        val history = historySnapshot() ?: loadPhotoHistory(runCatching {
            AtomicFile(File(library, HISTORY)).openRead().bufferedReader().use { it.readText() }
        }.getOrNull() ?: prefs.getString("photo_history", null))
        val hidden = prefs.getStringSet(HIDDEN, emptySet()).orEmpty()
        val settings = JSONObject()
        for ((key, value) in prefs.all) if (key in settingKeys) settings.put(key, value)
        val entries = JSONArray()
        for (photo in photos) {
            require(safeComponent(photo.album) && safeComponent(photo.name))
            val item = JSONObject().put("album", photo.album).put("name", photo.name).put("modified", photo.modified)
                .put("hidden", photo.key in hidden)
            history[photo.key]?.let { item.put("addedAt", it.addedAt).put("views", it.viewCount).put("lastSeen", it.lastViewedAt) }
            entries.put(item)
        }
        val albums = File(library, "upload-albums").listFiles().orEmpty().filter(File::isDirectory).map(File::getName)
        require(albums.size <= MAX_PHOTOS && albums.all(::safeComponent))
        val manifest = JSONObject().put("format", FORMAT).put("settings", settings).put("albums", JSONArray(albums))
            .put("photos", entries).toString().toByteArray()
        require(manifest.size <= MAX_MANIFEST)
        ZipOutputStream(output.buffered(65536)).use { zip ->
            zip.setLevel(0) // The photos are already encoded.
            zip.putNextEntry(ZipEntry("frame.json"))
            zip.write(manifest)
            zip.closeEntry()
            for ((i, photo) in photos.withIndex()) {
                zip.putNextEntry(ZipEntry("photos/$i"))
                photo.open().use { require(it.copyTo(zip, 65536) > 0) { "Leere Bilddatei." } }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("complete"))
            zip.closeEntry()
        }
    }

    fun restore(input: InputStream, length: Long) {
        require(library == activeDirectory(root, prefs)) { "Bitte den aktuellen QR-Code scannen." }
        require(length in 22..MAX_ARCHIVE) { "Ungültige Archivgröße." }
        val id = UUID.randomUUID().toString()
        val next = File(File(root, GENERATIONS), id)
        check(next.mkdirs())
        var committed = false
        try {
            val bytes = ArchiveBody(input, length)
            ZipInputStream(bytes.buffered(65536)).use { zip ->
                require(zip.nextEntry?.name == "frame.json") { "Keine Stillframe-Sicherung." }
                val metadata = ByteArrayOutputStream()
                copyLimited(zip, metadata, MAX_MANIFEST.toLong())
                val manifest = JSONObject(metadata.toString("UTF-8"))
                require(manifest.getString("format") == FORMAT) { "Unbekannte Sicherungsversion." }
                val entries = manifest.getJSONArray("photos")
                require(entries.length() <= MAX_PHOTOS)
                val editor = prefs.edit()
                restoreSettings(manifest.getJSONObject("settings"), editor)
                val hidden = HashSet<String>()
                val history = HashMap<String, PhotoHistory>()
                val available = (next.usableSpace - 16L * 1024 * 1024).coerceAtLeast(0)
                val albumRoot = File(next, "upload-albums")
                manifest.optJSONArray("albums")?.let { albums ->
                    require(albums.length() <= MAX_PHOTOS)
                    for (i in 0 until albums.length()) {
                        val name = albums.getString(i)
                        require(safeComponent(name))
                        check(File(albumRoot, name).mkdirs())
                    }
                }
                var remaining = available.coerceAtMost(MAX_ARCHIVE)
                for (i in 0 until entries.length()) {
                    val item = entries.getJSONObject(i)
                    val album = item.getString("album")
                    val name = item.getString("name")
                    require(safeComponent(album) && safeComponent(name)) { "Ungültiger Foto- oder Ordnername." }
                    require(zip.nextEntry?.name == "photos/$i") { "Ein Foto fehlt in der Sicherung." }
                    val dir = File(albumRoot, album)
                    dir.mkdirs()
                    val file = File(dir, name)
                    require(file.createNewFile()) { "Doppelter Dateiname im Album." }
                    val size = file.outputStream().use { out ->
                        copyLimited(zip, out, remaining).also { out.fd.sync() }
                    }
                    require(size > 0) { "Leere Bilddatei." }
                    remaining -= size
                    val modified = item.getLong("modified")
                    if (modified > 0) file.setLastModified(modified)
                    val key = "file:${file.absolutePath}"
                    if (item.getBoolean("hidden")) hidden.add(key)
                    if (item.has("views")) history[key] = PhotoHistory(item.getLong("addedAt"), item.getInt("views").coerceAtLeast(0), item.getLong("lastSeen"))
                }
                require(zip.nextEntry?.name == "complete" && zip.read() == -1 && zip.nextEntry == null) { "Sicherung unvollständig." }
                bytes.verifyEnd(entries.length() + 2)
                writeAtomicFile(AtomicFile(File(next, HISTORY)), savePhotoHistory(history))
                val oldUris = prefs.getStringSet("local_photos", emptySet()).orEmpty()
                val previous = prefs.all
                val saved = editor.putString(GENERATION_KEY, id).putStringSet(HIDDEN, hidden)
                    .remove("photo_history").remove("local_photos").commit()
                if (!saved) {
                    // SharedPreferences updates its in-memory map even when the disk write fails.
                    val undo = prefs.edit()
                    restoreSettings(JSONObject(previous.filterKeys(settingKeys::contains)), undo)
                    for (key in listOf(GENERATION_KEY, "photo_history")) undo.putString(key, previous[key] as? String)
                    for (key in listOf(HIDDEN, "local_photos")) {
                        undo.putStringSet(key, (previous[key] as? Set<*>)?.filterIsInstance<String>()?.toSet())
                    }
                    undo.commit()
                    error("Wiederherstellung konnte nicht gespeichert werden.")
                }
                committed = true
                for (value in oldUris) runCatching {
                    context.contentResolver.releasePersistableUriPermission(Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        } finally {
            if (!committed) next.deleteRecursively()
        }
    }

    companion object {
        const val HIDDEN = "hidden_photos"
        const val HISTORY = "photo-history.json"
        const val MAX_ARCHIVE = 4_000_000_000L
        private const val MAX_PHOTOS = 16000
        private const val MAX_MANIFEST = 5 * 1024 * 1024
        private const val FORMAT = "stillframe-1"
        private const val GENERATIONS = "frame-libraries"
        private const val GENERATION_KEY = "library_generation"
        val libraryLock = Any()
        private val flags = setOf("auto_start_on_boot", "calendar_enabled", "fill_default_migrated", "glass_overlay",
            "newest_uploads_first", "notes_enabled", "pan_zoom", "schedule_enabled", "schedule_weekdays_only",
            "show_clock", "show_date", "show_weather", "shuffle")
        private val numbers = mapOf("display_rotation" to 0..270, "image_padding_dp" to 0..32,
            "motion_seconds" to 1..300, "seconds" to 5..60, "sleep_timer_minutes" to 0..1440)
        private val strings = setOf("calendar_blacklist", "calendar_feed_url", "calendar_phone_hidden_titles",
            "image_scale_mode", "note_text", "phone_content", "schedule_end", "schedule_start", "selected_upload_album",
            "transition_style", "weather_country", "weather_postal")
        private val settingKeys = flags + numbers.keys + strings

        fun activeDirectory(root: File, prefs: SharedPreferences): File =
            prefs.getString(GENERATION_KEY, null)?.let { File(File(root, GENERATIONS), UUID.fromString(it).toString()) } ?: root

        fun cleanInactive(root: File, prefs: SharedPreferences) = synchronized(libraryLock) {
            val active = activeDirectory(root, prefs)
            File(root, GENERATIONS).listFiles()?.filter { it != active }?.forEach(File::deleteRecursively)
            if (active != root) {
                File(root, "upload-albums").deleteRecursively()
                AtomicFile(File(root, HISTORY)).delete()
            }
        }

        private fun restoreSettings(settings: JSONObject, editor: SharedPreferences.Editor) {
            for (key in settingKeys) {
                if (!settings.has(key)) { editor.remove(key); continue }
                val value = settings.get(key)
                when (key) {
                    in flags -> editor.putBoolean(key, value as Boolean)
                    in numbers -> {
                        require(value is Int && value in numbers.getValue(key))
                        if (key == "display_rotation") require(value % 90 == 0)
                        editor.putInt(key, value)
                    }
                    in strings -> { require(value is String && value.length <= 64000); editor.putString(key, value) }
                }
            }
        }

        private fun safeComponent(name: String) = name.isNotBlank() && name != "." && name != ".." &&
            name.toByteArray().size <= 255 && name.none { it == '/' || it == '\\' || it.isISOControl() }

        private fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Long {
            val buffer = ByteArray(65536)
            var size = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return size
                size += n
                require(size <= limit) { "Archiv zu groß oder zu wenig freier Speicher auf dem Fernseher." }
                output.write(buffer, 0, n)
            }
        }
    }
}

/** ZipInputStream stops before the central directory. Verify the HTTP body and ZIP end record too. */
private class ArchiveBody(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
    private val tail = ByteArray(22)
    override fun close() = Unit // The HTTP handler still needs the socket to send the result.
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return -1
        val count = super.read(buffer, offset, minOf(remaining, length.toLong()).toInt())
        require(count >= 0) { "Übertragung abgebrochen." }
        val keep = minOf(tail.size, count)
        tail.copyInto(tail, 0, keep)
        buffer.copyInto(tail, tail.size - keep, offset + count - keep, offset + count)
        remaining -= count
        return count
    }
    fun verifyEnd(entries: Int) {
        val buffer = ByteArray(65536)
        while (remaining > 0) read(buffer)
        val end = java.nio.ByteBuffer.wrap(tail).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        require(end.getInt(0) == 0x06054b50 && end.getShort(10).toInt() == entries && end.getShort(20).toInt() == 0) {
            "Die ZIP-Datei ist unvollständig."
        }
    }
}
