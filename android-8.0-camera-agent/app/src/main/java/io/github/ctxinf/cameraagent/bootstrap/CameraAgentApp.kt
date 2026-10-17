package io.github.ctxinf.cameraagent.bootstrap

import android.app.Application
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog

class CameraAgentApp : Application() {
    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.i("Application onCreate: Camera Agent initializing...")
        appGraph = AppGraph(this)
        AppLog.i("Application onCreate completed.")
    }
}
