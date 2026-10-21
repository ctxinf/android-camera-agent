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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    val cameraAccess = io.github.ctxinf.cameraagent.application.CameraAccessController(coordinator, settingsRepository) {
        AgentService.isRunning
    }
    val cameraController: CameraController = cameraAccess

    var capabilities: List<CameraCapabilities> = emptyList()
        private set

    val httpServer: HttpServer = DefaultHttpServer(
        cameraController = cameraController,
        frameEncoder = frameEncoder,
        settingsRepository = settingsRepository,
        getHardwareLevel = { capabilities.firstOrNull()?.hardwareLevel },
    )

    val mqttManager: io.github.ctxinf.cameraagent.adapters.mqtt.MqttManager =
        io.github.ctxinf.cameraagent.adapters.mqtt.MqttManager(
            context = context,
            cameraController = cameraController,
            settingsRepository = settingsRepository,
            applyCommand = { command ->
                fun boolean(key: String): Boolean? = command[key]?.let {
                    require(it is kotlinx.serialization.json.JsonPrimitive && !it.isString) { "$key must be boolean" }
                    it.content.toBooleanStrictOrNull() ?: error("$key must be boolean")
                }
                val http = boolean("http_enabled")
                val mcp = boolean("mcp_enabled")
                val mode = command["camera_mode"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: error("Invalid camera_mode")
                }
                require(mode == null || mode in listOf("back", "front", "disabled")) { "Invalid camera_mode" }
                val rotation = command["rotation"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: error("Invalid rotation")
                }
                require(rotation == null || rotation in listOf(0, 90, 180, 270)) { "Invalid rotation" }
                val brightness = command["screen_brightness"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: error("Invalid brightness")
                }
                require(brightness == null || brightness in 0..100) { "Brightness must be 0..100" }
                require(command.isNotEmpty() && command.keys.all {
                    it in setOf("http_enabled", "mcp_enabled", "camera_mode", "rotation", "screen_brightness")
                }) { "Unknown or empty command" }
                if (mode != null || rotation != null) cameraAccess.updateLocal { config ->
                    config.copy(
                        enabled = mode?.let { it != "disabled" } ?: config.enabled,
                        facing = when (mode) {
                            "front" -> io.github.ctxinf.cameraagent.domain.LensFacing.FRONT
                            "back" -> io.github.ctxinf.cameraagent.domain.LensFacing.BACK
                            else -> config.facing
                        },
                        rotationOffset = rotation ?: config.rotationOffset,
                    )
                }
                http?.let(settingsRepository::setHttpEnabled)
                mcp?.let(settingsRepository::setMcpEnabled)
                if (http != null || mcp != null) refreshNetworkListener()
                brightness?.let {
                    withContext(Dispatchers.Main) { (context.applicationContext as CameraAgentApp).setScreenBrightness(it) }
                }
            },
            scope = appScope,
        )

    private val listenerMutex = Mutex()
    private var listenerPort: Int? = null
    private var listenerAddress: String? = null

    /** Serializes listener reconfiguration across startup, settings and shutdown. */
    suspend fun refreshNetworkListener() = withContext(Dispatchers.IO) {
        listenerMutex.withLock {
            val enabled = AgentService.isRunning && !settingsRepository.manuallyExited &&
                (settingsRepository.httpEnabled || settingsRepository.mcpEnabled)
            if (!enabled || listenerPort != settingsRepository.httpPort || listenerAddress != settingsRepository.httpBindAddress) {
                httpServer.stop()
                listenerPort = null
                listenerAddress = null
            }
            httpServer.closeDisabledConnections()
            if (enabled && !httpServer.isRunning) {
                httpServer.start(settingsRepository.httpPort)
                listenerPort = settingsRepository.httpPort
                listenerAddress = settingsRepository.httpBindAddress
            }
        }
    }

    init {
        AppLog.i("Initializing AppGraph...")
        refreshCapabilities()
    }

    fun refreshCapabilities(): List<CameraCapabilities> {
        capabilities = capabilitiesProbe.probe()
        AppLog.i("AppGraph initialized with ${capabilities.size} camera device(s)")
        return capabilities
    }
}
