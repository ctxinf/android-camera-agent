package io.github.ctxinf.cameraagent.bootstrap

import android.content.Context
import io.github.ctxinf.cameraagent.adapters.http.DefaultHttpServer
import io.github.ctxinf.cameraagent.adapters.http.HttpServer
import io.github.ctxinf.cameraagent.camera.AlwaysClosePolicy
import io.github.ctxinf.cameraagent.camera.Camera2Backend
import io.github.ctxinf.cameraagent.camera.Camera2CapabilitiesProbe
import io.github.ctxinf.cameraagent.camera.CameraBackend
import io.github.ctxinf.cameraagent.camera.CameraCoordinator
import io.github.ctxinf.cameraagent.camera.GracePeriodRetentionPolicy
import io.github.ctxinf.cameraagent.camera.CaptureSessionPlanner
import io.github.ctxinf.cameraagent.camera.DefaultCaptureSessionPlanner
import io.github.ctxinf.cameraagent.camera.DefaultFrameEncoder
import io.github.ctxinf.cameraagent.camera.FrameEncoder
import io.github.ctxinf.cameraagent.camera.RetentionPolicy
import io.github.ctxinf.cameraagent.domain.CameraCapabilities
import io.github.ctxinf.cameraagent.domain.CameraController
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
import io.github.ctxinf.cameraagent.infrastructure.power.AndroidPowerPolicy
import io.github.ctxinf.cameraagent.infrastructure.power.PowerPolicy
import io.github.ctxinf.cameraagent.infrastructure.preferences.SettingsRepository
import io.github.ctxinf.cameraagent.infrastructure.preferences.SharedPreferencesSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal dependency injection container using constructor injection.
 */
class AppGraph(
    val context: Context,
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val settingsRepository: SettingsRepository = SharedPreferencesSettingsRepository(context)
    val powerPolicy: PowerPolicy = AndroidPowerPolicy(context)

    val capabilitiesProbe = Camera2CapabilitiesProbe(context)
    val backend: CameraBackend = Camera2Backend(context, capabilitiesProbe)
    val retentionPolicy: RetentionPolicy = GracePeriodRetentionPolicy(
        getGracePeriodMs = { (settingsRepository.config.value.retentionGracePeriodSec * 1000L).coerceAtLeast(0L) }
    )
    val sessionPlanner: CaptureSessionPlanner = DefaultCaptureSessionPlanner()
    val frameEncoder: FrameEncoder = DefaultFrameEncoder()

    val coordinator: CameraCoordinator = CameraCoordinator(
        backend = backend,
        parentScope = appScope,
        retentionPolicy = retentionPolicy,
        sessionPlanner = sessionPlanner,
        frameEncoder = frameEncoder,
        initialConfig = settingsRepository.config.value,
    )

    val cameraController: CameraController = coordinator

    var capabilities: List<CameraCapabilities> = emptyList()
        private set

    val httpServer: HttpServer = DefaultHttpServer(
        cameraController = coordinator,
        frameEncoder = frameEncoder,
        settingsRepository = settingsRepository,
        getHardwareLevel = { capabilities.firstOrNull()?.hardwareLevel },
    )

    val mqttManager: io.github.ctxinf.cameraagent.adapters.mqtt.MqttManager =
        io.github.ctxinf.cameraagent.adapters.mqtt.MqttManager(
            context = context,
            cameraController = coordinator,
            settingsRepository = settingsRepository,
            scope = appScope,
        )

    init {
        AppLog.i("Initializing AppGraph...")
        refreshCapabilities()
        if (settingsRepository.mqttEnabled) {
            mqttManager.start()
        }
    }

    fun refreshCapabilities(): List<CameraCapabilities> {
        capabilities = capabilitiesProbe.probe()
        AppLog.i("AppGraph initialized with ${capabilities.size} camera device(s)")
        return capabilities
    }
}
