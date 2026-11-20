package cu.todus.relayvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Servicio en primer plano de toDusVPN.
 *
 * Mantiene viva la sesión XMPP con el servidor y un servidor SOCKS5 local.
 * Cada conexión SOCKS se túnel-ea al peer servidor mediante mensajes XMPP
 * (frames TDV2 + mux CONNECT/DATA/CLOSE), reemplazando el relay de objetos S3.
 *
 * El estado se expone estáticamente para que MainActivity lo reporte a Flutter.
 */
class TodusVpnService : Service() {

    companion object {
        const val ACTION_START = "cu.todus.relayvpn.START"
        const val ACTION_STOP = "cu.todus.relayvpn.STOP"
        const val CHANNEL_ID = "todusvpn_fg"

        const val STATE_STOPPED = "detenida"
        const val STATE_CONNECTING = "conectando"
        const val STATE_ACTIVE = "activa"
        const val STATE_ERROR = "error"

        @Volatile var state: String = STATE_STOPPED
        @Volatile var lastError: String = ""
        @Volatile var instance: TodusVpnService? = null
    }

    private var conn: TodusXMPPConn? = null
    private var socks: TodusSocksServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                state = STATE_CONNECTING
                startForeground(1, buildNotification("Conectando…"))
                Thread({ startVpn() }, "todus-vpn-start").start()
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        try {
            val prefs = getSharedPreferences("todusvpn", Context.MODE_PRIVATE)
            val phone = prefs.getString("phone", "") ?: ""
            val jwt = prefs.getString("jwt", "") ?: ""
            val serverPhone = prefs.getString("server_phone", "5353974499") ?: "5353974499"
            require(phone.isNotEmpty() && jwt.isNotEmpty()) { "faltan credenciales (phone/jwt)" }
            val peerJid = buildJid(normalizePhone(serverPhone))
            AppLog.append("[vpn] conectando XMPP como $phone → peer $peerJid")
            val c = TodusXMPPConn(phone, jwt, onFrame = { kind, data, from ->
                if (kind == TodusXMPPConn.FrameKind.DATA) {
                    val (id, flag, body) = decodeMux(data)
                    when (flag) {
                        FLAG_DATA -> socks?.writeToConn(id, body)
                        FLAG_CLOSE -> socks?.closeConn(id)
                    }
                }
            }, onControl = { text, _ -> AppLog.append("[ctrl] $text") })
            c.connect()
            conn = c
            AppLog.append("[vpn] XMPP conectado, iniciando SOCKS5 local")
            val s = TodusSocksServer(1080, object : TodusSocksServer.Callback {
                override fun onConnect(connId: Int, hostPort: String) {
                    AppLog.append("[socks] conn#$connId CONNECT $hostPort")
                    c.sendData(peerJid, encodeMux(connId, FLAG_CONNECT, hostPort.toByteArray(Charsets.UTF_8)))
                }
                override fun onData(connId: Int, data: ByteArray) {
                    c.sendData(peerJid, encodeMux(connId, FLAG_DATA, data))
                }
                override fun onClose(connId: Int) {
                    c.sendData(peerJid, encodeMux(connId, FLAG_CLOSE, ByteArray(0)))
                    AppLog.append("[socks] conn#$connId CLOSE")
                }
            })
            s.start()
            socks = s
            state = STATE_ACTIVE
            AppLog.append("[vpn] activa — SOCKS5 en 127.0.0.1:${s.localPort}")
            notify("toDusVPN activa")
        } catch (e: Exception) {
            Log.e("TodusVpnService", "startVpn error", e)
            lastError = e.message ?: "error desconocido"
            state = STATE_ERROR
            AppLog.append("[vpn] ERROR: $lastError")
            notify("toDusVPN: error — $lastError")
        }
    }

    private fun stopAll() {
        try { socks?.stop() } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
        socks = null
        conn = null
        state = STATE_STOPPED
        AppLog.append("[vpn] detenida")
    }

    override fun onDestroy() {
        stopAll()
        instance = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "toDusVPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("toDusVPN")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setOngoing(true)
        .build()

    private fun notify(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(1, buildNotification(text))
    }
}
