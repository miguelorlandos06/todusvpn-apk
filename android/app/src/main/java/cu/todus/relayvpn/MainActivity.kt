package cu.todus.relayvpn

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Actividad Flutter: puente MethodChannel `todus.vpn/bridge`.
 *
 * Métodos:
 *  - autoLogin {phone, serverPhone} → {jwt}  : login_with_phone_only en Kotlin.
 *  - connect → arranca TodusVpnService.
 *  - disconnect → detiene el servicio.
 *  - getStatus → {state, lastError, log}.
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "todus.vpn/bridge"
        private const val REQ_NOTIFICATION = 41081
    }

    private var pendingConnect: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "autoLogin" -> {
                    val phone = call.argument<String>("phone") ?: ""
                    val serverPhone = call.argument<String>("serverPhone") ?: "5353974499"
                    Thread({
                        try {
                            val jwt = TodusAuth().loginWithPhoneOnly(phone)
                            getSharedPreferences("todusvpn", Context.MODE_PRIVATE).edit()
                                .putString("phone", normalizePhone(phone))
                                .putString("jwt", jwt)
                                .putString("server_phone", normalizePhone(serverPhone))
                                .apply()
                            runOnUiThread { result.success(mapOf("jwt" to jwt)) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(mapOf("jwt" to "", "error" to (e.message ?: "login fallido"))) }
                        }
                    }, "todus-auth").start()
                }
                "connect" -> handleConnect(result)
                "disconnect" -> {
                    stopVpnService()
                    result.success(true)
                }
                "getStatus" -> result.success(buildStatus())
                "clearLog" -> { AppLog.clear(); result.success(true) }
                else -> result.notImplemented()
            }
        }
    }

    private fun handleConnect(result: MethodChannel.Result) {
        when (TodusVpnService.state) {
            TodusVpnService.STATE_ACTIVE, TodusVpnService.STATE_CONNECTING -> {
                stopVpnService(); result.success(true)
            }
            else -> {
                pendingConnect = result
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    @Suppress("DEPRECATION")
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
                } else {
                    startVpnService()
                    pendingConnect?.success(true); pendingConnect = null
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION) {
            startVpnService()
            pendingConnect?.success(true); pendingConnect = null
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, TodusVpnService::class.java).apply { action = TodusVpnService.ACTION_START }
        ContextCompat.startForegroundService(this, intent)
    }
    private fun stopVpnService() {
        val intent = Intent(this, TodusVpnService::class.java).apply { action = TodusVpnService.ACTION_STOP }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun buildStatus(): Map<String, Any> = mutableMapOf(
        "state" to TodusVpnService.state,
        "lastError" to (TodusVpnService.lastError ?: ""),
        "log" to AppLog.snapshot(),
    )
}
