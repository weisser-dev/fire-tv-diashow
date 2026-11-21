package dev.weisser.googlephotoscarousel

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** A temporary, token-protected LAN upload page for photos sent from a phone browser. */
class LocalPhotoUploadServer(
    private val filesDir: File,
    private val onPhotoUploaded: (File, String) -> Unit
) {
    private val token = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var uploadedCount = 0

    val uploadedPhotos: Int get() = synchronized(this) { uploadedCount }

    fun start(): String {
        check(running.compareAndSet(false, true)) { "Der Upload ist bereits geöffnet." }
        val ip = findLocalIpv4() ?: run {
            running.set(false)
            error("Keine WLAN-Adresse gefunden. Prüfe, ob der Fire TV mit demselben WLAN verbunden ist.")
        }
        val listener = try {
            ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
        } catch (error: Exception) {
            running.set(false)
            throw error
        }
        server = listener
        acceptThread = Thread({
            while (running.get()) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                Thread({ handle(socket) }, "photo-carousel-upload-request").apply { isDaemon = true; start() }
            }
        }, "photo-carousel-upload-server").apply { isDaemon = true; start() }
        return "http://$ip:${listener.localPort}/upload/$token"
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 120_000
            if (!client.inetAddress.isSiteLocalAddress || client.inetAddress.isLoopbackAddress) {
                respond(client, 403, "text/plain; charset=utf-8", "Nur Geräte im lokalen WLAN sind erlaubt.")
                return
            }
            val input = BufferedInputStream(client.getInputStream())
            val line = readLine(input) ?: return
            val parts = line.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val target = parts.getOrNull(1).orEmpty()
            val rawPath = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val headers = mutableMapOf<String, String>()
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                val separator = header.indexOf(':')
                if (separator > 0) headers[header.substring(0, separator).trim().lowercase(Locale.ROOT)] = header.substring(separator + 1).trim()
            }
            when {
                method == "GET" && rawPath == "/upload/$token" -> respond(client, 200, "text/html; charset=utf-8", uploadPage())
                method == "POST" && rawPath == "/api/upload/$token" -> receivePhoto(client, input, headers, query)
                else -> respond(client, 404, "text/plain; charset=utf-8", "Nicht gefunden.")
            }
        }
    }

    private fun receivePhoto(socket: Socket, input: BufferedInputStream, headers: Map<String, String>, query: Map<String, String>) {
        val length = headers["content-length"]?.toLongOrNull()
        if (length == null || length <= 0 || length > MAX_PHOTO_BYTES) {
            respond(socket, 413, "application/json; charset=utf-8", "{\"error\":\"Datei fehlt oder ist größer als 50 MB.\"}")
            return
        }
        val mime = headers["content-type"].orEmpty().substringBefore(';').lowercase(Locale.ROOT)
        val extFromMime = when (mime) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heif" -> "heic"
            else -> null
        }
        val suppliedName = query["name"].orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val requestedExt = suppliedName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val extension = requestedExt.takeIf { it in ALLOWED_EXTENSIONS } ?: extFromMime
        if (extension == null) {
            respond(socket, 415, "application/json; charset=utf-8", "{\"error\":\"Dieses Bildformat wird nicht unterstützt.\"}")
            return
        }
        val albumName = safeAlbumName(query["album"].orEmpty())
        val albumDir = File(File(filesDir, "upload-albums"), albumName)
        val baseName = suppliedName.substringBeforeLast('.', suppliedName)
            .replace(Regex("[^\\p{L}\\p{N}_ -]"), "_").trim().take(70).ifBlank { "Foto" }
        if (!albumDir.exists() && !albumDir.mkdirs()) {
            respond(socket, 500, "application/json; charset=utf-8", "{\"error\":\"Der Albumordner konnte nicht angelegt werden.\"}")
            return
        }
        var output = File(albumDir, "$baseName.$extension")
        var duplicate = 1
        while (output.exists()) output = File(albumDir, "$baseName-${duplicate++}.$extension")
        try {
            output.outputStream().buffered().use { stream ->
                val buffer = ByteArray(32 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) error("Die Übertragung wurde unterbrochen.")
                    stream.write(buffer, 0, read)
                    remaining -= read
                }
            }
            synchronized(this) { uploadedCount++ }
            onPhotoUploaded(output, albumName)
            respond(socket, 200, "application/json; charset=utf-8", JSONObject().put("ok", true).put("name", output.name).toString())
        } catch (error: Exception) {
            output.delete()
            val message = JSONObject().put("error", error.localizedMessage ?: "Upload fehlgeschlagen.").toString()
            respond(socket, 400, "application/json; charset=utf-8", message)
        }
    }

    private fun uploadPage(): String {
        val knownAlbums = scan(filesDir).map { it.album }.distinct().joinToString("") { album ->
            "<option value=\"${album.replace("&", "&amp;").replace("\"", "&quot;")}\"></option>"
        }
        return """
        <!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta name="color-scheme" content="dark"><title>Fotos an Photo Carousel senden</title><style>
        *{box-sizing:border-box}body{margin:0;min-height:100vh;background:#10141b;color:#f5f2eb;font:17px system-ui,sans-serif;padding:22px;display:grid;place-items:center}
        main{width:min(680px,100%);background:#1b222c;border:1px solid #394654;border-radius:22px;padding:26px}h1{font-size:27px;margin:0 0 8px}p{line-height:1.5;color:#cbd2db}
        label{display:block;margin:18px 0 8px;font-weight:650}input,button{font:inherit;width:100%;padding:14px;border-radius:12px}input{background:#10141b;color:#fff;border:1px solid #526070}input[type=file]{padding:12px}
        button{margin-top:18px;border:0;background:#e8c98c;color:#10141b;font-weight:750}button:disabled{opacity:.55}.status{min-height:2em;color:#42d9ff;white-space:pre-line}.note{font-size:13px;color:#9da8b6}
        </style></head><body><main><h1>Fotos an Photo Carousel senden</h1>
        <p>Wähle ein Album und Bilder aus. Die Fotos werden direkt über dein WLAN auf den Fernseher übertragen. Große Bilder werden vorher auf maximal 1920 × 1920 Pixel verkleinert und als JPEG komprimiert.</p>
        <label for="album">Album auf dem Fernseher</label><input id="album" list="album-options" value="Erinnerungen" maxlength="40" autocomplete="off" placeholder="Albumname eingeben oder auswählen"><datalist id="album-options">$knownAlbums</datalist>
        <p class="note">Ein vorhandener Albumname ergänzt dieses Album; ein neuer Name legt ein neues Album an.</p>
        <label for="photos">Bilder auswählen</label><input id="photos" type="file" accept="image/*" multiple>
        <button id="send">Ausgewählte Bilder hochladen</button><p class="status" id="status">Diese Seite funktioniert nur, solange der Upload auf dem Fernseher geöffnet ist.</p>
        <p class="note">Der Upload-Link ist nur für diese Sitzung gültig. Keine Google-Anmeldung erforderlich.</p></main>
        <script>
        const input=document.getElementById('photos'), album=document.getElementById('album'), button=document.getElementById('send'), status=document.getElementById('status');
        async function prepare(file){
          let source=null,width=0,height=0,cleanup=()=>{};
          try{if('createImageBitmap' in window){source=await createImageBitmap(file);width=source.width;height=source.height;cleanup=()=>source.close();}}catch(_){source=null;}
          if(!source){
            try{const objectUrl=URL.createObjectURL(file);const image=await new Promise((resolve,reject)=>{const img=new Image();img.onload=()=>resolve(img);img.onerror=()=>reject(Error('Das Handy kann dieses Foto nicht decodieren.'));img.src=objectUrl;});source=image;width=image.naturalWidth;height=image.naturalHeight;cleanup=()=>URL.revokeObjectURL(objectUrl);}catch(_){return {blob:file,name:file.name,type:file.type||'application/octet-stream',original:true};}
          }
          const scale=Math.min(1,1920/width,1920/height);
          if(scale===1&&file.size<=8*1024*1024){cleanup();return {blob:file,name:file.name,type:file.type||'application/octet-stream'};}
          const canvas=document.createElement('canvas');canvas.width=Math.max(1,Math.round(width*scale));canvas.height=Math.max(1,Math.round(height*scale));
          const ctx=canvas.getContext('2d');if(!ctx){cleanup();return {blob:file,name:file.name,type:file.type||'application/octet-stream',original:true};}
          ctx.fillStyle='#fff';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.drawImage(source,0,0,canvas.width,canvas.height);cleanup();
          const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/jpeg',0.86));if(!blob)return {blob:file,name:file.name,type:file.type||'application/octet-stream',original:true};
          return {blob,name:file.name.replace(/\.[^.]+$/,'')+'.jpg',type:'image/jpeg'};
        }
        button.onclick=async()=>{const files=[...input.files];if(!files.length){status.textContent='Wähle zuerst mindestens ein Bild aus.';return}button.disabled=true;let done=0;const errors=[];
          for(const file of files){let prepared;try{status.textContent='Bereite '+file.name+' vor …';prepared=await prepare(file);}catch(error){errors.push(file.name+': Foto konnte am Handy nicht gelesen werden.');continue}
            try{status.textContent=(prepared.original?'Sende Original: ':'Sende ')+file.name+' …';const url='/api/upload/$token?album='+encodeURIComponent(album.value||'Erinnerungen')+'&name='+encodeURIComponent(prepared.name);let response;try{response=await fetch(url,{method:'POST',headers:{'Content-Type':prepared.type},body:prepared.blob});}catch(_){throw Error('WLAN-Upload fehlgeschlagen. Prüfe, ob beide Geräte im selben WLAN sind und der TV-Dialog noch offen ist.');}const result=await response.json();if(!response.ok)throw Error(result.error||'Übertragung fehlgeschlagen');done++;status.textContent=done+' von '+files.length+' Bildern übertragen: '+result.name;}catch(error){errors.push(file.name+': '+error.message)}}
          status.textContent=done+' von '+files.length+' Bildern übertragen.'+(errors.length?'\n'+errors.join('\n'):'');button.disabled=false;input.value='';};
        </script></body></html>
    """.trimIndent()
    }

    private fun respond(socket: Socket, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val label = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; 500 -> "Internal Server Error"; else -> "Not Found" }
        val headers = "HTTP/1.1 $status $label\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        BufferedOutputStream(socket.getOutputStream()).apply {
            write(headers.toByteArray(StandardCharsets.US_ASCII)); write(bytes); flush()
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = ByteArrayOutputStream()
        while (line.size() < 8192) {
            val value = input.read()
            if (value < 0) return if (line.size() == 0) null else line.toString("UTF-8")
            if (value == '\n'.code) break
            if (value != '\r'.code) line.write(value)
        }
        return line.toString("UTF-8")
    }

    private fun parseQuery(raw: String): Map<String, String> = raw.split('&').mapNotNull { pair ->
        val pieces = pair.split('=', limit = 2)
        if (pieces.isEmpty()) null else URLDecoder.decode(pieces[0], "UTF-8") to URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
    }.toMap()

    private fun safeAlbumName(raw: String): String = raw
        .replace(Regex("[\\p{Cntrl}/\\\\]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}_ -]"), "")
        .trim().trim('.').take(40).ifBlank { "Erinnerungen" }

    private fun findLocalIpv4(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        val addresses = Collections.list(interfaces).sortedBy { network ->
            when { network.name.startsWith("wlan", ignoreCase = true) -> 0; network.name.startsWith("eth", ignoreCase = true) -> 1; else -> 2 }
        }.flatMap { network ->
            if (!runCatching { network.isUp && !network.isLoopback }.getOrDefault(false)) emptyList()
            else Collections.list(network.inetAddresses).filterIsInstance<Inet4Address>()
        }
        return addresses.firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
    }

    companion object {
        private const val MAX_PHOTO_BYTES = 50L * 1024 * 1024
        private val ALLOWED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif")

        fun scan(filesDir: File): List<UploadedPhoto> {
            val root = File(filesDir, "upload-albums")
            return root.listFiles().orEmpty().filter { it.isDirectory }.flatMap { album ->
                album.listFiles().orEmpty().filter { it.isFile && it.length() > 0L }.map { file ->
                    UploadedPhoto(file.absolutePath, album.name, file.lastModified())
                }
            }.sortedByDescending { it.modifiedAt }
        }
    }
}

data class UploadedPhoto(val path: String, val album: String, val modifiedAt: Long)
