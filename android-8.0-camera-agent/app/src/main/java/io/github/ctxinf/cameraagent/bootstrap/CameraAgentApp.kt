package io.github.ctxinf.cameraagent.bootstrap

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog

class CameraAgentApp : Application(), Application.ActivityLifecycleCallbacks {
    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.i("Application onCreate: Camera Agent initializing...")
        appGraph = AppGraph(this)
        registerActivityLifecycleCallbacks(this)
        applyTheme(appGraph.settingsRepository.themeMode)
        AppLog.i("Application onCreate completed.")
    }

    fun applyTheme(mode: String) {
        val nightMode = when (mode) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val activities = mutableSetOf<Activity>()
    private var started = 0
    val hasVisibleActivities get() = started > 0
    private val backgroundCheck = Runnable {
        if (!hasVisibleActivities && !appGraph.settingsRepository.backgroundEnabled)
            stopService(Intent(this, AgentService::class.java))
    }

    fun refreshBackgroundPolicy() {
        if (AgentService.isRunning) startService(Intent(this, AgentService::class.java))
        handler.removeCallbacks(backgroundCheck)
        if (!hasVisibleActivities) handler.post(backgroundCheck)
    }

    fun forceExit() {
        appGraph.settingsRepository.setManuallyExited(true) // synchronous; survives process death and reboot
        packageManager.setComponentEnabledSetting(android.content.ComponentName(this, BootReceiver::class.java),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP)
        handler.removeCallbacks(backgroundCheck)
        stopService(Intent(this, AgentService::class.java))
        activities.toList().forEach { it.finishAndRemoveTask() }
        handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 600)
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) { activities.add(activity) }
    override fun onActivityStarted(activity: Activity) {
        started++
        handler.removeCallbacks(backgroundCheck)
        if (activity !is io.github.ctxinf.cameraagent.adapters.ui.LaunchActivity &&
            !appGraph.settingsRepository.manuallyExited &&
            checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, AgentService::class.java))
        }
    }
    override fun onActivityStopped(activity: Activity) {
        started--
        handler.postDelayed(backgroundCheck, 500) // don't stop during transitions between our own pages
    }
    override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
    private var resumedActivity: Activity? = null
    private var requestedBrightness: Int? = null

    fun currentScreenBrightness(): Int {
        val value = resumedActivity?.window?.attributes?.screenBrightness ?: -1f
        return if (value >= 0f) (value * 100).toInt().coerceIn(0, 100)
        else (android.provider.Settings.System.getInt(contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255).coerceIn(0, 100)
    }

    fun setScreenBrightness(percent: Int) {
        requestedBrightness = percent
        activities.forEach { activity ->
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = percent / 100f }
        }
    }

    fun screenBrightnessOverride(): Float? = requestedBrightness?.let { it / 100f }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = activity
        requestedBrightness?.let { setScreenBrightness(it) }
    }
    override fun onActivityPaused(activity: Activity) { if (resumedActivity == activity) resumedActivity = null }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
}
