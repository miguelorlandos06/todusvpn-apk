package cu.todus.relayvpn

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Servidor SOCKS5 local (127.0.0.1). Cada conexión aceptada se túnel-ea
 * hacia el servidor toDusVPN mediante el transporte de mensajes XMPP:
 *
 *  1. handshake SOCKS5 (no-auth, CONNECT) → se obtiene host:port destino.
 *  2. se envía un frame mux CONNECT (conn-id nuevo) al peer servidor.
 *  3. se retransmite cada lectura del socket como frame mux DATA.
 *  4. al cerrar, se envía frame mux CLOSE.
 *
 * Las respuestas DATA del servidor se inyectan vía [writeToConn].
 */
class TodusSocksServer(
    private val port: Int,
    private val callback: Callback,
) {
    interface Callback {
        fun onConnect(connId: Int, hostPort: String)
        fun onData(connId: Int, data: ByteArray)
        fun onClose(connId: Int)
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val nextId = AtomicInteger(1)
    private val conns = java.util.Collections.synchronizedMap(HashMap<Int, Socket>())

    fun start() {
        running = true
        serverSocket = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", port)) }
        Thread({ acceptLoop() }, "todus-socks-accept").apply { isDaemon = true; start() }
        Log.i("TodusSocks", "SOCKS5 escuchando en 127.0.0.1:${serverSocket!!.localPort}")
    }

    val localPort: Int get() = serverSocket?.localPort ?: port

    /** Escribe datos recibidos del servidor (vía XMPP) al socket del cliente. */
    fun writeToConn(connId: Int, data: ByteArray): Boolean {
        val s = conns[connId] ?: return false
        return try {
            s.getOutputStream().write(data)
            s.getOutputStream().flush()
            true
        } catch (e: IOException) {
            Log.w("TodusSocks", "writeToConn #$connId err: ${e.message}")
            false
        }
    }

    fun closeConn(connId: Int) {
        val s = conns.remove(connId)
        try { s?.close() } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        synchronized(conns) {
            for ((_, s) in conns) try { s.close() } catch (_: Exception) {}
            conns.clear()
        }
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val client = try { ss.accept() } catch (e: Exception) {
                if (running) Log.w("TodusSocks", "accept err: ${e.message}")
                break
            }
            Thread({ handleClient(client) }, "todus-socks-conn").start()
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val inp = client.getInputStream()
            val out = client.getOutputStream()
            // SOCKS5 greeting: VER, NMETHODS, METHODS... → respond no-auth (0x00)
            val ver = inp.read()
            if (ver != 5) { client.close(); return }
            val nmethods = inp.read()
            val methods = ByteArray(nmethods)
            readFully(inp, methods)
            out.write(byteArrayOf(0x05, 0x00)); out.flush()
            // request: VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT
            if (inp.read() != 5) { client.close(); return }
            val cmd = inp.read()
            inp.read() // reserved
            val atyp = inp.read()
            val host: String = when (atyp) {
                1 -> { val b = ByteArray(4); readFully(inp, b); b.joinToString(".") { (it.toInt() and 0xff).toString() } }
                3 -> { val len = inp.read(); val b = ByteArray(len); readFully(inp, b); String(b) }
                4 -> { val b = ByteArray(16); readFully(inp, b); java.net.InetAddress.getByAddress(b).hostAddress ?: "" }
                else -> { client.close(); return }
            }
            val portHi = inp.read(); val portLo = inp.read()
            val port = (portHi and 0xff) shl 8 or (portLo and 0xff)
            if (cmd != 1) { // only CONNECT
                out.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush(); client.close(); return
            }
            // success reply
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()

            val connId = nextId.getAndIncrement()
            conns[connId] = client
            callback.onConnect(connId, "$host:$port")
            Log.i("TodusSocks", "conn#$connId CONNECT $host:$port")

            // relay client → server
            val buf = ByteArray(1500)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                callback.onData(connId, buf.copyOfRange(0, n))
            }
        } catch (e: Exception) {
            Log.w("TodusSocks", "handleClient err: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun readFully(inp: java.io.InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = inp.read(b, off, b.size - off)
            if (n <= 0) throw IOException("eof in readFully")
            off += n
        }
    }
}
