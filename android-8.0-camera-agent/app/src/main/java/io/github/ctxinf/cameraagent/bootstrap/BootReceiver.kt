package io.github.ctxinf.cameraagent.bootstrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Intent.ACTION_BOOT_COMPLETED == intent?.action) {
            val settings = (context.applicationContext as CameraAgentApp).appGraph.settingsRepository
            if (!settings.backgroundEnabled || settings.manuallyExited) return
            AppLog.i("BootReceiver received ACTION_BOOT_COMPLETED, starting AgentService")
            val serviceIntent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
