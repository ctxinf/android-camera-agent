package io.github.ctxinf.cameraagent.infrastructure.preferences

import android.content.Context
import android.content.SharedPreferences
import io.github.ctxinf.cameraagent.domain.CameraConfig
import io.github.ctxinf.cameraagent.domain.LensFacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface SettingsRepository {
    val backgroundEnabled: Boolean
    fun setBackgroundEnabled(enabled: Boolean)
    val manuallyExited: Boolean
    fun setManuallyExited(exited: Boolean)
    val frontCameraDisabled: Boolean
    fun setFrontCameraDisabled(disabled: Boolean)
    val httpEnabled: Boolean
    val mcpEnabled: Boolean
    fun setHttpEnabled(enabled: Boolean)
    fun setMcpEnabled(enabled: Boolean)
    val config: StateFlow<CameraConfig>
    val httpPathPrefix: String
    val httpPort: Int
    val dimTimeoutSec: Int
    val dimBrightness: Float
    val retentionGracePeriodSec: Int
    val authEnabled: Boolean
    val authUsername: String
    val authPassword: String
    val mqttEnabled: Boolean
    val mqttHost: String
    val mqttPort: Int
    val mqttUsername: String
    val mqttPassword: String
    val mqttClientId: String
    val mqttTopicPrefix: String
    val themeMode: String get() = "system"

    fun setThemeMode(mode: String) {}

    fun setHttpPathPrefix(prefix: String)
    fun setHttpPort(port: Int)
    fun setDimTimeoutSec(seconds: Int)
    fun setDimBrightness(brightness: Float)
    fun setRetentionGracePeriodSec(seconds: Int)
    fun setAuthEnabled(enabled: Boolean)
    fun setAuthUsername(username: String)
    fun setAuthPassword(password: String)
    fun setMqttEnabled(enabled: Boolean)
    fun setMqttHost(host: String)
    fun setMqttPort(port: Int)
    fun setMqttUsername(username: String)
    fun setMqttPassword(password: String)
    fun setMqttClientId(clientId: String)
    fun setMqttTopicPrefix(prefix: String)
    suspend fun updateConfig(config: CameraConfig)
}

class SharedPreferencesSettingsRepository(
    context: Context,
) : SettingsRepository {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    override val backgroundEnabled get() = prefs.getBoolean("background_enabled", true)
    override fun setBackgroundEnabled(enabled: Boolean) { prefs.edit().putBoolean("background_enabled", enabled).commit() }
    override val manuallyExited get() = prefs.getBoolean("manually_exited", false)
    override fun setManuallyExited(exited: Boolean) { prefs.edit().putBoolean("manually_exited", exited).commit() }
    override val frontCameraDisabled get() = prefs.getBoolean("front_camera_disabled", false)
    override fun setFrontCameraDisabled(disabled: Boolean) { prefs.edit().putBoolean("front_camera_disabled", disabled).commit() }
    override val httpEnabled get() = prefs.getBoolean("http_enabled", true)
    override val mcpEnabled get() = prefs.getBoolean("mcp_enabled", false)
    override fun setHttpEnabled(enabled: Boolean) { prefs.edit().putBoolean("http_enabled", enabled).apply() }
    override fun setMcpEnabled(enabled: Boolean) { prefs.edit().putBoolean("mcp_enabled", enabled).apply() }

    private val _config = MutableStateFlow(loadConfig())
    override val config: StateFlow<CameraConfig> = _config.asStateFlow()

    override val httpPathPrefix: String
        get() = io.github.ctxinf.cameraagent.domain.HttpPathPrefix.normalize(prefs.getString("http_path_prefix", ""))

    override fun setHttpPathPrefix(prefix: String) {
        prefs.edit().putString("http_path_prefix", io.github.ctxinf.cameraagent.domain.HttpPathPrefix.normalize(prefix)).apply()
    }

    override val httpPort: Int
        get() = prefs.getInt(KEY_HTTP_PORT, DEFAULT_HTTP_PORT)

    override val dimTimeoutSec: Int
        get() = prefs.getInt(KEY_DIM_TIMEOUT_SEC, DEFAULT_DIM_TIMEOUT_SEC)

    override val dimBrightness: Float
        get() = prefs.getFloat(KEY_DIM_BRIGHTNESS, DEFAULT_DIM_BRIGHTNESS)

    override val retentionGracePeriodSec: Int
        get() = prefs.getInt(KEY_RETENTION_GRACE_SEC, DEFAULT_RETENTION_GRACE_SEC)

    override val authEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTH_ENABLED, DEFAULT_AUTH_ENABLED)

    override val authUsername: String
        get() = prefs.getString(KEY_AUTH_USERNAME, DEFAULT_AUTH_USERNAME) ?: DEFAULT_AUTH_USERNAME

    override val authPassword: String
        get() = prefs.getString(KEY_AUTH_PASSWORD, DEFAULT_AUTH_PASSWORD) ?: DEFAULT_AUTH_PASSWORD

    override val mqttEnabled: Boolean
        get() = prefs.getBoolean(KEY_MQTT_ENABLED, DEFAULT_MQTT_ENABLED)

    override val mqttHost: String
        get() = prefs.getString(KEY_MQTT_HOST, DEFAULT_MQTT_HOST) ?: DEFAULT_MQTT_HOST

    override val mqttPort: Int
        get() = prefs.getInt(KEY_MQTT_PORT, DEFAULT_MQTT_PORT)

    override val mqttUsername: String
        get() = prefs.getString(KEY_MQTT_USERNAME, DEFAULT_MQTT_USERNAME) ?: DEFAULT_MQTT_USERNAME

    override val mqttPassword: String
        get() = prefs.getString(KEY_MQTT_PASSWORD, DEFAULT_MQTT_PASSWORD) ?: DEFAULT_MQTT_PASSWORD

    override val mqttClientId: String
        get() = prefs.getString(KEY_MQTT_CLIENT_ID, null) ?: run {
            val generated = "camera-agent-${android.os.Build.MODEL.take(6).filter { it.isLetterOrDigit() }}-${(1000..9999).random()}"
            setMqttClientId(generated)
            generated
        }

    override val mqttTopicPrefix: String
        get() = prefs.getString(KEY_MQTT_TOPIC_PREFIX, DEFAULT_MQTT_TOPIC_PREFIX) ?: DEFAULT_MQTT_TOPIC_PREFIX

    override val themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE

    override fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
    }

    override fun setHttpPort(port: Int) {
        prefs.edit().putInt(KEY_HTTP_PORT, port).apply()
    }

    override fun setDimTimeoutSec(seconds: Int) {
        prefs.edit().putInt(KEY_DIM_TIMEOUT_SEC, seconds).apply()
    }

    override fun setDimBrightness(brightness: Float) {
        prefs.edit().putFloat(KEY_DIM_BRIGHTNESS, brightness).apply()
    }

    override fun setRetentionGracePeriodSec(seconds: Int) {
        prefs.edit().putInt(KEY_RETENTION_GRACE_SEC, seconds).apply()
        _config.value = _config.value.copy(retentionGracePeriodSec = seconds)
    }

    override fun setAuthEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTH_ENABLED, enabled).apply()
    }

    override fun setAuthUsername(username: String) {
        prefs.edit().putString(KEY_AUTH_USERNAME, username).apply()
    }

    override fun setAuthPassword(password: String) {
        prefs.edit().putString(KEY_AUTH_PASSWORD, password).apply()
    }

    override fun setMqttEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MQTT_ENABLED, enabled).apply()
    }

    override fun setMqttHost(host: String) {
        prefs.edit().putString(KEY_MQTT_HOST, host).apply()
    }

    override fun setMqttPort(port: Int) {
        prefs.edit().putInt(KEY_MQTT_PORT, port).apply()
    }

    override fun setMqttUsername(username: String) {
        prefs.edit().putString(KEY_MQTT_USERNAME, username).apply()
    }

    override fun setMqttPassword(password: String) {
        prefs.edit().putString(KEY_MQTT_PASSWORD, password).apply()
    }

    override fun setMqttClientId(clientId: String) {
        prefs.edit().putString(KEY_MQTT_CLIENT_ID, clientId).apply()
    }

    override fun setMqttTopicPrefix(prefix: String) {
        prefs.edit().putString(KEY_MQTT_TOPIC_PREFIX, prefix).apply()
    }

    override suspend fun updateConfig(config: CameraConfig) {
        _config.value = config
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_FACING, config.facing.name)
            .putInt(KEY_TARGET_WIDTH, config.targetWidth)
            .putInt(KEY_TARGET_HEIGHT, config.targetHeight)
            .putInt(KEY_TARGET_FPS, config.targetFps)
            .putInt(KEY_JPEG_QUALITY, config.jpegQuality)
            .putInt(KEY_ROTATION_OFFSET, config.rotationOffset)
            .putInt(KEY_RETENTION_GRACE_SEC, config.retentionGracePeriodSec)
            .apply()
    }

    private fun loadConfig(): CameraConfig {
        val enabled = prefs.getBoolean(KEY_ENABLED, true)
        val facingName = prefs.getString(KEY_FACING, LensFacing.BACK.name) ?: LensFacing.BACK.name
        val requestedFacing = try { LensFacing.valueOf(facingName) } catch (_: Exception) { LensFacing.BACK }
        val facing = if (requestedFacing == LensFacing.FRONT && frontCameraDisabled) LensFacing.BACK else requestedFacing
        val targetWidth = prefs.getInt(KEY_TARGET_WIDTH, 1280)
        val targetHeight = prefs.getInt(KEY_TARGET_HEIGHT, 720)
        val targetFps = prefs.getInt(KEY_TARGET_FPS, 15)
        val jpegQuality = prefs.getInt(KEY_JPEG_QUALITY, 80)
        val rotationOffset = prefs.getInt(KEY_ROTATION_OFFSET, 0)
        val retentionGracePeriodSec = prefs.getInt(KEY_RETENTION_GRACE_SEC, DEFAULT_RETENTION_GRACE_SEC)
        return CameraConfig(
            enabled = enabled,
            facing = facing,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            targetFps = targetFps,
            jpegQuality = jpegQuality,
            rotationOffset = rotationOffset,
            retentionGracePeriodSec = retentionGracePeriodSec,
        )
    }

    companion object {
        private const val PREF_NAME = "camera_agent_preferences"
        private const val KEY_HTTP_PORT = "http_port"
        private const val KEY_DIM_TIMEOUT_SEC = "dim_timeout_sec"
        private const val KEY_DIM_BRIGHTNESS = "dim_brightness"
        private const val KEY_ENABLED = "camera_enabled"
        private const val KEY_FACING = "camera_facing"
        private const val KEY_TARGET_WIDTH = "target_width"
        private const val KEY_TARGET_HEIGHT = "target_height"
        private const val KEY_TARGET_FPS = "target_fps"
        private const val KEY_JPEG_QUALITY = "jpeg_quality"
        private const val KEY_ROTATION_OFFSET = "rotation_offset"
        private const val KEY_RETENTION_GRACE_SEC = "retention_grace_sec"
        private const val KEY_AUTH_ENABLED = "auth_enabled"
        private const val KEY_AUTH_USERNAME = "auth_username"
        private const val KEY_AUTH_PASSWORD = "auth_password"
        private const val KEY_MQTT_ENABLED = "mqtt_enabled"
        private const val KEY_MQTT_HOST = "mqtt_host"
        private const val KEY_MQTT_PORT = "mqtt_port"
        private const val KEY_MQTT_USERNAME = "mqtt_username"
        private const val KEY_MQTT_PASSWORD = "mqtt_password"
        private const val KEY_MQTT_CLIENT_ID = "mqtt_client_id"
        private const val KEY_MQTT_TOPIC_PREFIX = "mqtt_topic_prefix"
        private const val KEY_THEME_MODE = "theme_mode"

        const val DEFAULT_HTTP_PORT = 8080
        const val DEFAULT_DIM_TIMEOUT_SEC = 30
        const val DEFAULT_DIM_BRIGHTNESS = 0.01f
        const val DEFAULT_RETENTION_GRACE_SEC = 5
        const val DEFAULT_AUTH_ENABLED = false
        const val DEFAULT_AUTH_USERNAME = "admin"
        const val DEFAULT_AUTH_PASSWORD = "password"
        const val DEFAULT_MQTT_ENABLED = false
        const val DEFAULT_MQTT_HOST = ""
        const val DEFAULT_MQTT_PORT = 1883
        const val DEFAULT_MQTT_USERNAME = ""
        const val DEFAULT_MQTT_PASSWORD = ""
        const val DEFAULT_MQTT_TOPIC_PREFIX = "camera_agent"
        const val DEFAULT_THEME_MODE = "system"
    }
}
