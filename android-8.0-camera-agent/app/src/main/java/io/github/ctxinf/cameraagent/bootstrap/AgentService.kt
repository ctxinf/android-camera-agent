package io.github.ctxinf.cameraagent.bootstrap

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.ctxinf.cameraagent.R
import io.github.ctxinf.cameraagent.adapters.ui.MainActivity
import io.github.ctxinf.cameraagent.domain.CameraState
import io.github.ctxinf.cameraagent.domain.LensFacing
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class AgentService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "camera_agent_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "io.github.ctxinf.cameraagent.START"
        const val ACTION_STOP = "io.github.ctxinf.cameraagent.STOP"
        const val ACTION_SWITCH_FACING = "io.github.ctxinf.cameraagent.SWITCH_FACING"

        var isRunning: Boolean = false
            private set
    }

    private val appGraph: AppGraph by lazy {
        (application as CameraAgentApp).appGraph
    }

    override fun onCreate() {
        super.onCreate()
        if (appGraph.settingsRepository.manuallyExited ||
            (!appGraph.settingsRepository.backgroundEnabled && !(application as CameraAgentApp).hasVisibleActivities)) {
            stopSelf()
            return
        }
        AppLog.i("AgentService onCreate()")
        isRunning = true
        createNotificationChannel()
        startForegroundServiceNotification()

        // Acquire power locks to ensure CPU & Wi-Fi stay responsive when screen is locked
        appGraph.powerPolicy.acquireWakeLock()
        appGraph.powerPolicy.acquireWifiLock()

        // Start HTTP Server
        lifecycleScope.launch {
            try {
                appGraph.cameraAccess.restore()
                appGraph.refreshNetworkListener()
                updateNotificationWithCurrentState()
            } catch (e: Exception) {
                AppLog.e("Failed to start HTTP server in AgentService", e)
                updateNotification("HTTP Server failed to start: ${e.message}")
            }
        }

        // Start MQTT Manager if enabled
        if (appGraph.settingsRepository.mqttEnabled) {
            appGraph.mqttManager.start()
        }

        // Live status observers for notification & MQTT
        lifecycleScope.launch {
            appGraph.coordinator.state.collect {
                updateNotificationWithCurrentState()
                if (appGraph.settingsRepository.mqttEnabled) {
                    appGraph.mqttManager.publishStatus()
                }
            }
        }
        lifecycleScope.launch {
            appGraph.coordinator.config.collect {
                updateNotificationWithCurrentState()
            }
        }
        lifecycleScope.launch {
            while (isRunning) {
                delay(10_000)
                updateNotificationWithCurrentState()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        AppLog.i("AgentService onStartCommand(action=${intent?.action})")
        when (intent?.action) {
            ACTION_STOP -> {
                appGraph.settingsRepository.setBackgroundEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SWITCH_FACING -> {
                lifecycleScope.launch {
                    val config = appGraph.coordinator.config.value
                    if (!config.enabled || appGraph.settingsRepository.frontCameraDisabled) return@launch
                    val newFacing = if (config.facing == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK
                    try {
                        appGraph.cameraAccess.updateLocal { if (it.enabled) it.copy(facing = newFacing) else it }
                    } catch (e: SecurityException) {
                        AppLog.w("Lens switch blocked by local policy", e)
                    }
                    updateNotificationWithCurrentState()
                }
            }
        }
        if (appGraph.settingsRepository.manuallyExited) {
            stopSelf()
            return START_NOT_STICKY
        }
        return if (appGraph.settingsRepository.backgroundEnabled) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AppLog.i("AgentService onTaskRemoved()")
        if (!appGraph.settingsRepository.backgroundEnabled || appGraph.settingsRepository.manuallyExited) {
            stopSelf()
            return
        }

        // When the task is cleared from Android recents, the OS will shortly terminate this process.
        // Direct synchronous calls to startForegroundService die with the process.
        // Instead, schedule an AlarmManager trigger to resurrect the ForegroundService 1 second later.
        try {
            val restartIntent = Intent(applicationContext, AgentService::class.java).apply {
                setPackage(packageName)
                action = ACTION_START
            }
            val flags = PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val restartPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(applicationContext, 1002, restartIntent, flags)
            } else {
                PendingIntent.getService(applicationContext, 1002, restartIntent, flags)
            }

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.let {
                val triggerAt = SystemClock.elapsedRealtime() + 1000L
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        it.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, restartPendingIntent)
                    } catch (_: SecurityException) {
                        it.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, restartPendingIntent)
                    }
                } else {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, restartPendingIntent)
                }
            }
        } catch (e: Exception) {
            AppLog.e("Failed to schedule service resurrection in onTaskRemoved", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.i("AgentService onDestroy()")
        isRunning = false

        appGraph.appScope.launch {
            try {
                appGraph.refreshNetworkListener()
                if (!isRunning) appGraph.cameraAccess.suspendHardware()
            } catch (e: Exception) {
                AppLog.w("Error stopping HTTP server", e)
            }
        }

        appGraph.mqttManager.stop()
        appGraph.powerPolicy.releaseAll()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Camera Agent Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Camera Agent background service running"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun getBatteryPercentage(): Int {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } catch (ignored: Exception) {
            -1
        }
    }

    private fun getIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (itf in interfaces) {
                if (!itf.isUp || itf.isLoopback) continue
                val addrs = Collections.list(itf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ignored: Exception) {}
        return null
    }

    private fun buildNotification(statusText: String, subText: String? = null): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val switchFacingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).apply { action = ACTION_SWITCH_FACING },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AgentService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val ip = getIpAddress() ?: "127.0.0.1"
        val port = appGraph.settingsRepository.httpPort
        val title = "Camera Agent: http://$ip:$port${appGraph.settingsRepository.httpPathPrefix}"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(io.github.ctxinf.cameraagent.R.drawable.ic_launcher_monochrome)
            .setContentIntent(openIntent)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC)
        }

        if (subText != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setSubText(subText)
        }

        // Action: Switch Facing
        val switchAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_rotate),
                "切换镜头",
                switchFacingIntent
            ).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Action.Builder(
                android.R.drawable.ic_menu_rotate,
                "切换镜头",
                switchFacingIntent
            ).build()
        }
        builder.addAction(switchAction)

        // Action: Stop Service
        val stopAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "停止服务",
                stopIntent
            ).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止服务",
                stopIntent
            ).build()
        }
        builder.addAction(stopAction)

        return builder.build()
    }

    private fun startForegroundServiceNotification() {
        val notification = buildNotification("Service initialized")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(statusText: String, subText: String? = null) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText, subText))
    }

    private fun updateNotificationWithCurrentState() {
        val state = appGraph.coordinator.state.value
        val config = appGraph.coordinator.config.value
        val battery = getBatteryPercentage()
        val batteryStr = if (battery >= 0) "$battery%" else "--"

        val camStateStr = when (state) {
            is CameraState.Disabled -> "已停用 (Disabled)"
            is CameraState.Closed -> "待机低功耗 (Standby)"
            is CameraState.Opening -> "打开中... (Opening)"
            is CameraState.Active -> "采集中 (Active)"
            is CameraState.Retained -> "预热保持 (Retained)"
            is CameraState.Closing -> "关闭中 (Closing)"
            is CameraState.Failed -> "错误 (Failed)"
        }

        val facingStr = if (config.facing == LensFacing.FRONT) "前摄" else "后摄"
        val activeStreams = (state as? CameraState.Active)?.activeStreams ?: 0
        val subText = if (activeStreams > 0) "$activeStreams 个流活跃" else null

        val statusText = "相机: $camStateStr | $facingStr | 电池: $batteryStr"
        updateNotification(statusText, subText)
    }
}
