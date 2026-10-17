package io.github.ctxinf.cameraagent.infrastructure.power

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog

interface PowerPolicy {
    fun acquireWakeLock()
    fun releaseWakeLock()
    fun acquireWifiLock()
    fun releaseWifiLock()
    fun releaseAll()
}

class AndroidPowerPolicy(
    context: Context,
) : PowerPolicy {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    override fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraAgent:PartialWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire() // Indefinite wake lock to prevent sleep during 24/7 background operation
                AppLog.i("PowerPolicy: PartialWakeLock acquired (indefinite)")
            }
        } catch (e: Exception) {
            AppLog.w("PowerPolicy: Failed to acquire wake lock", e)
        }
    }

    @Synchronized
    override fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                AppLog.i("PowerPolicy: PartialWakeLock released")
            }
        } catch (e: Exception) {
            AppLog.w("PowerPolicy: Failed to release wake lock", e)
        }
    }

    @Synchronized
    override fun acquireWifiLock() {
        try {
            if (wifiLock == null) {
                @Suppress("DEPRECATION")
                val lockType = WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wifiManager?.createWifiLock(lockType, "CameraAgent:WifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                AppLog.i("PowerPolicy: WifiLock acquired")
            }
        } catch (e: Exception) {
            AppLog.w("PowerPolicy: Failed to acquire wifi lock", e)
        }
    }

    @Synchronized
    override fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                AppLog.i("PowerPolicy: WifiLock released")
            }
        } catch (e: Exception) {
            AppLog.w("PowerPolicy: Failed to release wifi lock", e)
        }
    }

    @Synchronized
    override fun releaseAll() {
        releaseWakeLock()
        releaseWifiLock()
    }
}
