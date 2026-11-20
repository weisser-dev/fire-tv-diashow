package dev.weisser.googlephotoscarousel

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Serves a tiny Google Identity Services page to the Fire TV's external browser. */
class LocalGoogleAuthBridge(private val webClientId: String) {
    private val running = AtomicBoolean(false)
    private val accessToken = AtomicReference<String?>(null)
    private val nonce = UUID.randomUUID().toString().replace("-", "")
    private var server: ServerSocket? = null

    val url: String get() = "http://127.0.0.1:$PORT/oauth/$nonce"

    fun start() {
        check(running.compareAndSet(false, true)) { "Google-Anmeldeseite läuft bereits." }
        // Bind before launching the browser so its first request cannot race server startup.
        // The wildcard listener accepts both IPv4 and IPv6 loopback; every connection is
        // still checked below and non-loopback clients are rejected.
        val listener = try {
            ServerSocket(PORT)
        } catch (error: Exception) {
            running.set(false)
            throw error
        }
        server = listener
        Thread({
            listener.use {
                while (running.get()) {
                    val client = runCatching { listener.accept() }.getOrNull() ?: break
                    Thread({ handle(client) }, "photo-carousel-oauth-request").start()
                }
            }
            running.set(false)
        }, "photo-carousel-oauth-server").apply { isDaemon = true; start() }
    }

    suspend fun awaitAccessToken(onWaiting: () -> Unit): String {
        val deadline = System.currentTimeMillis() + 5 * 60 * 1000L
        while (System.currentTimeMillis() < deadline) {
            accessToken.get()?.let { return it }
            onWaiting()
            delay(500)
        }
        error("Die Google-Anmeldung ist abgelaufen. Bitte erneut versuchen.")
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            if (!client.inetAddress.isLoopbackAddress) {
                respond(client, 403, "text/plain; charset=utf-8", "Nur lokale Verbindungen sind erlaubt.")
                return
            }
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty().substringBefore('?')
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
            when {
                method == "GET" && path == "/oauth/$nonce" -> respond(client, 200, "text/html; charset=utf-8", page())
                method == "GET" && path == "/oauth/$nonce/status" -> respond(
                    client, 200, "application/json; charset=utf-8",
                    if (accessToken.get() == null) "{\"authorized\":false}" else "{\"authorized\":true}"
                )
                method == "POST" && path == "/oauth/$nonce/token" -> {
                    val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 16_384) ?: 0
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(body, offset, length - offset)
                        if (count <= 0) break
                        offset += count
                    }
                    val token = runCatching { JSONObject(String(body, 0, offset, StandardCharsets.UTF_8)).optString("access_token") }.getOrNull()
                    if (!token.isNullOrBlank()) {
                        accessToken.compareAndSet(null, token)
                        respond(client, 200, "application/json; charset=utf-8", "{\"ok\":true}")
                    } else respond(client, 400, "application/json; charset=utf-8", "{\"ok\":false}")
                }
                else -> respond(client, 404, "text/plain; charset=utf-8", "Nicht gefunden.")
            }
        }
    }

    private fun page(): String = """
        <!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Google Fotos verbinden · Photo Carousel</title><style>
        *{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:#10141b;color:#f5f2eb;font:20px system-ui,sans-serif;padding:28px}
        main{width:min(680px,100%);padding:36px;background:#1b222c;border:1px solid #394654;border-radius:24px;text-align:center}h1{font-size:clamp(28px,5vw,42px);margin:0 0 14px}p{line-height:1.55;color:#cbd2db}
        button{font:inherit;font-weight:700;padding:18px 26px;border:0;border-radius:14px;background:#e8c98c;color:#10141b;margin:12px;cursor:pointer}.status{min-height:2em;color:#42d9ff}
        </style><script src="https://accounts.google.com/gsi/client" async defer></script></head>
        <body><main><h1>Google Fotos verbinden</h1><p>Melde dich mit deinem Google-Konto an. Photo Carousel erhält nur Zugriff auf Fotos, die du anschließend im Google-Fotos-Picker auswählst.</p>
        <button id="login" disabled>Anmeldung wird geladen …</button><p class="status" id="status">Die Anmeldung läuft im Browser dieses Fernsehers.</p></main>
        <script>
        const statusNode=document.getElementById('status'), button=document.getElementById('login');
        function ready(){button.disabled=false;button.textContent='Mit Google anmelden';button.onclick=()=>{
          const client=google.accounts.oauth2.initTokenClient({client_id:'$webClientId',scope:'https://www.googleapis.com/auth/photospicker.mediaitems.readonly',prompt:'consent select_account',callback:async response=>{
            if(response.error){statusNode.textContent='Google-Anmeldung fehlgeschlagen: '+response.error;return}
            statusNode.textContent='Anmeldung erfolgreich. Zurück zu Photo Carousel …';
            try{const saved=await fetch('/oauth/$nonce/token',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({access_token:response.access_token})});if(!saved.ok)throw Error('Übergabe fehlgeschlagen')}
            catch(error){statusNode.textContent='Die Anmeldung konnte nicht an die App übergeben werden: '+error.message}
          }});client.requestAccessToken();};}
        const poll=setInterval(()=>{if(window.google?.accounts?.oauth2){clearInterval(poll);ready()}},100);
        setTimeout(()=>{if(button.disabled){button.textContent='Google-Anmeldung konnte nicht geladen werden';statusNode.textContent='Prüfe die Internetverbindung des Fernsehers und lade die Seite neu.'}},15000);
        </script></body></html>
    """.trimIndent()

    private fun respond(socket: Socket, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val label = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        val headers = "HTTP/1.1 $status $label\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(StandardCharsets.US_ASCII)); write(bytes); flush() }
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (line.size() < 8192) {
            val value = input.read()
            if (value < 0) return if (line.size() == 0) null else line.toString("UTF-8")
            if (value == '\n'.code) break
            if (value != '\r'.code) line.write(value)
        }
        return line.toString("UTF-8")
    }

    companion object { private const val PORT = 8765 }
}
