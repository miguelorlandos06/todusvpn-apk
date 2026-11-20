package cu.todus.relayvpn

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * toDusVPN client — Kotlin port of todus-lib (Go).
 *
 * Implements:
 *  - loginWithPhoneOnly: HTTP auth bypass (POST /v2/auth/token with the
 *    hardcoded 32-char secret as password) → JWT.
 *  - XMPP TLS connect + SASL PLAIN handshake + resource bind + presence.
 *  - Message-based transport: TDV2/TDU2 frames carried base64 in XMPP
 *    message bodies (replaces the S3-object relay).
 *  - Mux (conn-id + flag) for TCP CONNECT/DATA/CLOSE.
 *
 * Reproduces the exact stanzas and protobuf payload from todus-sdk.
 */
object TodusConst {
    const val XMPP_HOST = "im.todus.cu"
    const val XMPP_PORT = 1756
    const val AUTH_URL = "https://auth.todus.cu/v2/auth/token"
    const val PHONE_ONLY_SECRET = "fake1234567890abcdef12345678" // first 32 chars of UUID w/o dashes
    const val KEEPALIVE_SEC = 30L
    private val JWT_RE: Pattern = Pattern.compile("eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+")

    fun extractJwt(body: String): String {
        val m = JWT_RE.matcher(body)
        return if (m.find()) m.group() else ""
    }
}

// ---------- protobuf wire helpers ----------
private fun varint(v: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    var x = v
    while (x > 0x7f) {
        out.write(((x and 0x7f) or 0x80).toInt())
        x = x ushr 7
    }
    out.write((x and 0x7f).toInt())
    return out.toByteArray()
}

private fun sf(fieldNumber: Int, value: String): ByteArray {
    val data = value.toByteArray(Charsets.UTF_8)
    val tag = byteArrayOf(((fieldNumber shl 3) or 2).toByte())
    return tag + varint(data.size.toLong()) + data
}

private fun randToken(n: Int): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    val rnd = SecureRandom()
    val sb = StringBuilder(n)
    val b = ByteArray(n)
    rnd.nextBytes(b)
    for (i in 0 until n) sb.append(chars[(b[i].toInt() and 0xff) % chars.length])
    return sb.toString()
}

private fun md5Hex(s: String): String {
    val d = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder()
    for (b in d) sb.append(String.format("%02x", b))
    return sb.toString()
}

private fun escapeXml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("'", "&apos;")
    .replace("\"", "&quot;")

private fun unescapeXml(s: String): String = s
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")

/** Normaliza un teléfono cubano a 10 dígitos (53 + 8). */
fun normalizePhone(phone: String): String {
    val cleaned = phone.replace(Regex("[\\s+()\\-.]"), "")
    require(cleaned.all { it.isDigit() }) { "número inválido: $phone" }
    val cc = "53"
    val nat = 8
    return when {
        cleaned.length == nat && !cleaned.startsWith(cc) -> cc + cleaned
        cleaned.startsWith(cc) && cleaned.length == cc.length + nat -> cleaned
        else -> throw IllegalArgumentException("número inválido: $phone (se esperaban $nat dígitos o ${cc.length + nat} con prefijo $cc)")
    }
}

fun buildJid(phone: String): String = phone + "@" + TodusConst.XMPP_HOST

// ---------- framing (port of frame.go) ----------
private val FRAME_MAGIC = "TDV2".toByteArray()
private val UDP_MAGIC = "TDU2".toByteArray()
private const val FRAME_HEADER = 4 + 1 + 8 + 4 + 4
private const val UDP_HEADER = 4 + 1 + 8 + 2
private const val UDP_ITEM_HEADER = 2 + 1 + 1 + 4

fun encodeFrame(seq: Long, payload: ByteArray): ByteArray {
    val out = ByteArray(FRAME_HEADER + payload.size)
    System.arraycopy(FRAME_MAGIC, 0, out, 0, 4)
    out[4] = 2
    var i = 5
    for (k in 0 until 8) { out[i + k] = ((seq ushr ((7 - k) * 8)) and 0xff).toByte() }
    i += 8
    val len = payload.size
    out[i++] = ((len ushr 24) and 0xff).toByte()
    out[i++] = ((len ushr 16) and 0xff).toByte()
    out[i++] = ((len ushr 8) and 0xff).toByte()
    out[i++] = (len and 0xff).toByte()
    val crc = java.util.zip.CRC32().apply { update(payload) }.value
    out[i++] = ((crc ushr 24) and 0xff).toByte()
    out[i++] = ((crc ushr 16) and 0xff).toByte()
    out[i++] = ((crc ushr 8) and 0xff).toByte()
    out[i++] = (crc and 0xff).toByte()
    System.arraycopy(payload, 0, out, i, payload.size)
    return out
}

fun decodeFrame(raw: ByteArray, expectedSeq: Long): ByteArray {
    require(raw.size >= FRAME_HEADER) { "truncado" }
    require(String(raw.copyOfRange(0, 4)) == "TDV2" && raw[4].toInt() == 2) { "firma incorrecta" }
    var seq = 0L
    for (k in 0 until 8) { seq = (seq shl 8) or (raw[5 + k].toLong() and 0xff) }
    require(seq == expectedSeq) { "secuencia incorrecta" }
    var len = 0
    for (k in 0 until 4) { len = (len shl 8) or (raw[13 + k].toInt() and 0xff) }
    require(raw.size - FRAME_HEADER == len) { "longitud incorrecta" }
    val payload = raw.copyOfRange(21, raw.size)
    val crc = java.util.zip.CRC32().apply { update(payload) }.value
    var got = 0L
    for (k in 0 until 4) { got = (got shl 8) or (raw[17 + k].toLong() and 0xff) }
    require(got == crc) { "CRC incorrecto" }
    return payload
}

private fun readFrameSeq(raw: ByteArray): Long {
    var seq = 0L
    for (k in 0 until 8) { seq = (seq shl 8) or (raw[5 + k].toLong() and 0xff) }
    return seq
}

// ---------- mux (port of mux.go) ----------
const val FLAG_CONNECT: Byte = 0x01
const val FLAG_DATA: Byte = 0x02
const val FLAG_CLOSE: Byte = 0x03

fun encodeMux(connId: Int, flag: Byte, data: ByteArray): ByteArray {
    val out = ByteArray(5 + data.size)
    out[0] = ((connId ushr 24) and 0xff).toByte()
    out[1] = ((connId ushr 16) and 0xff).toByte()
    out[2] = ((connId ushr 8) and 0xff).toByte()
    out[3] = (connId and 0xff).toByte()
    out[4] = flag
    System.arraycopy(data, 0, out, 5, data.size)
    return out
}

fun decodeMux(payload: ByteArray): Triple<Int, Byte, ByteArray> {
    require(payload.size >= 5) { "mux truncado" }
    val id = ((payload[0].toInt() and 0xff) shl 24) or
        ((payload[1].toInt() and 0xff) shl 16) or
        ((payload[2].toInt() and 0xff) shl 8) or
        (payload[3].toInt() and 0xff)
    return Triple(id, payload[4], payload.copyOfRange(5, payload.size))
}

// ---------- HTTP auth ----------
class TodusAuth(private val client: OkHttpClient = OkHttpClient()) {
    /** login_with_phone_only: POST /v2/auth/token with sf(1,phone)+sf(2,SECRET). */
    fun loginWithPhoneOnly(phone: String): String {
        val norm = normalizePhone(phone)
        val body = (sf(1, norm) + sf(2, TodusConst.PHONE_ONLY_SECRET))
        val req = Request.Builder()
            .url(TodusConst.AUTH_URL)
            .header("content-type", "application/octet-stream")
            .header("user-agent", "ToDus 2.1.1")
            .post(body.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (resp.code == 401 || resp.code == 403) {
                throw TodusAuthException("login rechazado (HTTP ${resp.code}): ${raw.take(120)}")
            }
            if (resp.code != 200) {
                throw TodusAuthException("login falló (HTTP ${resp.code}): ${raw.take(120)}")
            }
            val jwt = TodusConst.extractJwt(raw)
            if (jwt.isEmpty()) throw TodusAuthException("token inválido recibido: ${raw.take(120)}")
            return jwt
        }
    }
}

class TodusAuthException(message: String) : Exception(message)

// ---------- XMPP session ----------
class TodusXMPPConn(
    val phone: String,
    val token: String,
    private val onFrame: (kind: FrameKind, data: ByteArray, from: String) -> Unit,
    private val onControl: ((String, String) -> Unit)? = null,
) {
    enum class FrameKind { DATA, UDP }

    private val peerJid: String get() = buildJid(phone)
    private var sock: javax.net.ssl.SSLSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Any()
    @Volatile private var running = false
    private val sendSeq = AtomicLong(0)
    private val seenSeqs = java.util.Collections.synchronizedSet(HashSet<Long>())
    private var keepaliveThread: Thread? = null

    fun connect(xmppPort: Int = TodusConst.XMPP_PORT) {
        val raw = java.net.Socket()
        raw.soTimeout = 15000
        raw.connect(InetSocketAddress(TodusConst.XMPP_HOST, xmppPort), 15000)
        // Trust all certs (toDus self-signed certs in some envs).
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, tm, SecureRandom())
        val s = ctx.socketFactory.createSocket(raw, TodusConst.XMPP_HOST, xmppPort, true) as javax.net.ssl.SSLSocket
        s.startHandshake()
        sock = s
        reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(s.outputStream, Charsets.UTF_8))
        handshake()
        send(stanzaPresence())
        running = true
        keepaliveThread = Thread({ keepaliveLoop() }, "todus-keepalive").apply { isDaemon = true; start() }
        Thread({ listenLoop() }, "todus-listen").apply { isDaemon = true; start() }
    }

    private fun send(s: String) {
        synchronized(writeLock) {
            writer?.write(s)
            writer?.flush()
        }
    }

    private fun recvAll(): String {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        try {
            // blocking read with socket soTimeout; returns -1 on EOF, throws
            // SocketTimeoutException on idle (handled → return what we have).
            val n = reader?.read(buf) ?: -1
            if (n > 0) sb.append(buf, 0, n)
            if (n < 0) return "__EOF__"
            while (reader?.ready() == true) {
                val m = reader?.read(buf) ?: -1
                if (m <= 0) break
                sb.append(buf, 0, m)
            }
        } catch (_: java.net.SocketTimeoutException) {
            // idle — return whatever was read (possibly "")
        }
        return sb.toString()
    }

    private fun handshake() {
        val authPhone = phone
        val authstr = Base64.getEncoder().encodeToString(
            (byteArrayOf(0) + authPhone.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + token.toByteArray(Charsets.UTF_8))
        )
        val sid = randToken(5)
        send(stanzaStreamOpen())
        var phase = "init"
        val start = System.currentTimeMillis()
        var noProgress = 0
        while (System.currentTimeMillis() - start < 30000) {
            val resp = recvAll()
            if (resp == "__EOF__") throw TodusAuthException("servidor cerró conexión durante handshake")
            if (resp.isEmpty()) {
                if (++noProgress >= 3) throw TodusAuthException("handshake atascado")
                continue
            }
            val prev = phase
            when (phase) {
                "init" -> if (resp.contains("<stream:features><es xmlns='x2'>") ||
                    (resp.startsWith("<?xml version='1.0'?><stream:stream") && resp.contains("<stream:features>"))) {
                    send("<ah xmlns='ah:ns' e='PLAIN'>$authstr</ah>")
                    phase = "auth_sent"
                }
                "auth_sent" -> when {
                    resp.contains("<ok xmlns='x2'/>") -> { send(stanzaStreamOpen()); phase = "restream" }
                    resp.contains("<not-authorized/>") -> throw TodusAuthException("token expirado / no autorizado")
                }
                "restream" -> if (resp.contains("<b1 xmlns='x4'/>")) {
                    send("<iq i='${sid}-1' t='set'><b1 xmlns='x4'><re>${md5Hex(authPhone)}_Android</re></b1></iq>")
                    phase = "bind_sent"
                }
                "bind_sent" -> if (resp.contains("t='result' i='${sid}-1'>")) return
                    else if (resp.contains("<not-authorized/>")) throw TodusAuthException("token expirado / no autorizado")
            }
            if (phase == prev) { if (++noProgress >= 3) throw TodusAuthException("handshake atascado: ${resp.take(120)}") } else noProgress = 0
        }
        throw TodusAuthException("handshake timeout (30s)")
    }

    private fun keepaliveLoop() {
        val pid = randToken(5)
        while (running) {
            try {
                Thread.sleep(TodusConst.KEEPALIVE_SEC * 1000)
                if (!running) break
                send("<iq i='$pid' t='get'><ping xmlns='urn:xmpp:ping'/></iq>")
            } catch (_: InterruptedException) { break }
            catch (e: Exception) { Log.w("TodusClient", "keepalive err: ${e.message}"); break }
        }
    }

    private fun listenLoop() {
        try {
            while (running) {
                val resp = recvAll()
                if (resp == "__EOF__") break
                if (resp.isEmpty()) continue
                for (inc in parseMessages(resp)) {
                    handleIncoming(inc)
                }
            }
        } catch (e: Exception) {
            Log.w("TodusClient", "listen ended: ${e.message}")
        }
        running = false
    }

    data class Incoming(val from: String, val id: String, val body: String)
    private val msgRe = Pattern.compile("(?s)<m\\s+([^>]*)>(.*?)</m>")
    private val bodyRe = Pattern.compile("(?s)<b>(.*?)</b>")
    private val fromRe = Pattern.compile("\\bf='([^']+)'")
    private val idRe = Pattern.compile("\\bi='([^']+)'")

    private fun parseMessages(buf: String): List<Incoming> {
        val out = ArrayList<Incoming>()
        val m = msgRe.matcher(buf)
        while (m.find()) {
            val attrs = m.group(1) ?: ""
            val inner = m.group(2) ?: ""
            val from = fromRe.matcher(attrs).let { if (it.find()) it.group(1) else "" }
            val id = idRe.matcher(attrs).let { if (it.find()) it.group(1) else "" }
            val body = bodyRe.matcher(inner).let { if (it.find()) unescapeXml(it.group(1)) else "" }
            out.add(Incoming(from, id, body))
        }
        return out
    }

    private fun handleIncoming(inc: Incoming) {
        if (inc.body.isEmpty()) return
        val body = inc.body
        try {
            when {
                body.startsWith("TDV2:") -> {
                    val raw = Base64.getDecoder().decode(body.substring(5))
                    val seq = readFrameSeq(raw)
                    val payload = decodeFrame(raw, seq)
                    if (seenSeqs.add(seq)) onFrame(FrameKind.DATA, payload, inc.from)
                }
                body.startsWith("TDU2:") -> {
                    val raw = Base64.getDecoder().decode(body.substring(5))
                    // minimal: pass the raw batch to onFrame; full decode omitted for brevity
                    if (true) onFrame(FrameKind.UDP, raw, inc.from)
                }
                else -> onControl?.invoke(body, inc.from)
            }
        } catch (e: Exception) {
            Log.w("TodusClient", "frame decode err: ${e.message}")
        }
    }

    /** Sends a private text message to toJID; returns the generated msg id. */
    fun sendMessage(toJid: String, body: String): String {
        val mid = md5Hex(randToken(16))
        send("<m o='$toJid' t='c' i='$mid' xmlns='jc'><k xmlns='x8'/><b>${escapeXml(body)}</b></m>")
        return mid
    }

    /** Sends a TDV2 data frame (base64) to the peer as one XMPP message. */
    fun sendData(peerJid: String, payload: ByteArray): Long {
        val seq = sendSeq.incrementAndGet()
        val frame = encodeFrame(seq, payload)
        sendMessage(peerJid, "TDV2:" + Base64.getEncoder().encodeToString(frame))
        return seq
    }

    fun sendControl(peerJid: String, text: String) { sendMessage(peerJid, text) }

    fun close() {
        running = false
        try { send("</stream:stream>") } catch (_: Exception) {}
        try { sock?.close() } catch (_: Exception) {}
    }

    private fun stanzaStreamOpen(): String =
        "<stream:stream xmlns='jc' o='${TodusConst.XMPP_HOST}' xmlns:stream='x1' v='1.0'>"
    private fun stanzaPresence(): String =
        "<p xmlns='jc'><status>Online</status><priority>5</priority>" +
        "<c ver='foVtX1ZDcopvf5CM63LcnVayPRs=' node='http://www.process-one.net/en/ejabberd/' hash='sha-1' xmlns='http://jabber.org/protocol/caps'/></p>"
}
