package io.github.ctxinf.cameraagent.adapters.mqtt

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import io.github.ctxinf.cameraagent.domain.CameraController
import io.github.ctxinf.cameraagent.domain.CameraState
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
import io.github.ctxinf.cameraagent.infrastructure.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MqttManager(
    private val context: Context,
    private val cameraController: CameraController,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job()),
) {

    private val _status = MutableStateFlow<MqttStatus>(
        if ((settingsRepository.mqttEnabled && !settingsRepository.manuallyExited && io.github.ctxinf.cameraagent.bootstrap.AgentService.isRunning)) MqttStatus.Connecting else MqttStatus.Disabled
    )
    val status: StateFlow<MqttStatus> = _status.asStateFlow()

    private var client: MqttClient? = null
    private var loopJob: Job? = null

    @Synchronized
    fun start() {
        if (!(settingsRepository.mqttEnabled && !settingsRepository.manuallyExited && io.github.ctxinf.cameraagent.bootstrap.AgentService.isRunning)) {
            _status.value = MqttStatus.Disabled
            return
        }
        if (loopJob?.isActive == true) return

        loopJob = scope.launch {
            runMqttLoop()
        }
    }

    @Synchronized
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        client?.disconnect()
        client = null
        _status.value = if ((settingsRepository.mqttEnabled && !settingsRepository.manuallyExited && io.github.ctxinf.cameraagent.bootstrap.AgentService.isRunning)) MqttStatus.Disconnected("Stopped") else MqttStatus.Disabled
    }

    @Synchronized
    fun restart() {
        stop()
        if ((settingsRepository.mqttEnabled && !settingsRepository.manuallyExited && io.github.ctxinf.cameraagent.bootstrap.AgentService.isRunning)) {
            start()
        }
    }

    private suspend fun runMqttLoop() {
        while (scope.isActive && (settingsRepository.mqttEnabled && !settingsRepository.manuallyExited && io.github.ctxinf.cameraagent.bootstrap.AgentService.isRunning)) {
            val host = settingsRepository.mqttHost.trim()
            val port = settingsRepository.mqttPort
            val clientId = settingsRepository.mqttClientId.ifBlank { "camera-agent-${Build.MODEL.take(6)}" }
            val username = settingsRepository.mqttUsername.ifBlank { null }
            val password = settingsRepository.mqttPassword.ifBlank { null }

            if (host.isEmpty()) {
                _status.value = MqttStatus.Disconnected("Broker Host 未配置")
                delay(5000)
                continue
            }

            _status.value = MqttStatus.Connecting
            val c = MqttClient(host, port, clientId, username, password, keepAliveSec = 60)
            client = c

            try {
                c.connect(timeoutMs = 8000)
                _status.value = MqttStatus.Connected("$host:$port")
                AppLog.i("MqttManager: Successfully connected to MQTT Broker $host:$port")

                // Publish HA discovery once connected
                publishHomeAssistantDiscovery(c)
                // Publish initial status
                publishStatus(c)

                // Ping & Periodic Publish loop
                var ticks = 0
                while (scope.isActive && c.isConnected && (settingsRepository.mqttEnabled && !settingsRepository.manuallyExited && io.github.ctxinf.cameraagent.bootstrap.AgentService.isRunning)) {
                    delay(5000)
                    ticks++

                    if (ticks % 6 == 0) { // Every 30 seconds
                        val pingOk = c.ping()
                        if (!pingOk) {
                            throw java.io.IOException("Ping response failed")
                        }
                        publishStatus(c)
                    }
                }
            } catch (e: Exception) {
                AppLog.w("MqttManager connection error: ${e.message}")
                _status.value = MqttStatus.Disconnected(e.message ?: "Connection error")
            } finally {
                c.disconnect()
            }

            if ((settingsRepository.mqttEnabled && !settingsRepository.manuallyExited && io.github.ctxinf.cameraagent.bootstrap.AgentService.isRunning)) {
                delay(10_000) // Reconnect delay
            }
        }

        if (!(settingsRepository.mqttEnabled && !settingsRepository.manuallyExited && io.github.ctxinf.cameraagent.bootstrap.AgentService.isRunning)) {
            _status.value = MqttStatus.Disabled
        }
    }

    suspend fun publishStatus(c: MqttClient? = client) {
        val currentClient = c ?: client ?: return
        if (!currentClient.isConnected) return

        try {
            val prefix = settingsRepository.mqttTopicPrefix.ifBlank { "camera_agent" }
            val topic = "$prefix/status"

            val state = cameraController.state.value
            val config = cameraController.config.value
            val ip = getIpAddress() ?: "127.0.0.1"
            val port = settingsRepository.httpPort
            val battery = getBatteryPercentage()

            val stateStr = when (state) {
                is CameraState.Disabled -> "Disabled"
                is CameraState.Closed -> "Closed"
                is CameraState.Opening -> "Opening"
                is CameraState.Active -> "Active"
                is CameraState.Retained -> "Retained"
                is CameraState.Closing -> "Closing"
                is CameraState.Failed -> "Failed"
            }

            val payload = buildJsonObject {
                put("camera_state", stateStr)
                put("facing", config.facing.name)
                put("width", if (state is CameraState.Active) state.width else config.targetWidth)
                put("height", if (state is CameraState.Active) state.height else config.targetHeight)
                put("fps", config.targetFps)
                put("active_streams", if (state is CameraState.Active) state.activeStreams else 0)
                put("battery", battery)
                put("ip", ip)
                put("http_port", port)
                put("camera_enabled", config.enabled)
                put("snapshot_url", "http://$ip:$port${settingsRepository.httpPathPrefix}/snapshot.jpg")
                put("stream_url", "http://$ip:$port${settingsRepository.httpPathPrefix}/video.mjpeg")
            }.toString()

            currentClient.publish(topic, payload, retain = true)
        } catch (e: Exception) {
            AppLog.w("MqttManager: Failed to publish status: ${e.message}")
        }
    }

    private suspend fun publishHomeAssistantDiscovery(c: MqttClient) {
        try {
            val prefix = settingsRepository.mqttTopicPrefix.ifBlank { "camera_agent" }
            val clientId = settingsRepository.mqttClientId.ifBlank { "camera_agent" }
            val stateTopic = "$prefix/status"

            val deviceObj = buildJsonObject {
                put("identifiers", clientId)
                put("name", "Android Camera Agent (${Build.MODEL})")
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("sw_version", "1.0.0")
            }

            // 1. Camera State Sensor
            val stateDiscoveryTopic = "homeassistant/sensor/${clientId}_state/config"
            val stateDiscoveryPayload = buildJsonObject {
                put("name", "Camera State")
                put("unique_id", "${clientId}_camera_state")
                put("state_topic", stateTopic)
                put("value_template", "{{ value_json.camera_state }}")
                put("icon", "mdi:camera")
                put("device", deviceObj)
            }.toString()
            c.publish(stateDiscoveryTopic, stateDiscoveryPayload, retain = true)

            // 2. Battery Sensor
            val batteryDiscoveryTopic = "homeassistant/sensor/${clientId}_battery/config"
            val batteryDiscoveryPayload = buildJsonObject {
                put("name", "Battery Level")
                put("unique_id", "${clientId}_battery")
                put("state_topic", stateTopic)
                put("value_template", "{{ value_json.battery }}")
                put("unit_of_measurement", "%")
                put("device_class", "battery")
                put("device", deviceObj)
            }.toString()
            c.publish(batteryDiscoveryTopic, batteryDiscoveryPayload, retain = true)

            // 3. Active Streams Sensor
            val streamsDiscoveryTopic = "homeassistant/sensor/${clientId}_streams/config"
            val streamsDiscoveryPayload = buildJsonObject {
                put("name", "Active Streams")
                put("unique_id", "${clientId}_streams")
                put("state_topic", stateTopic)
                put("value_template", "{{ value_json.active_streams }}")
                put("icon", "mdi:video-wireless")
                put("device", deviceObj)
            }.toString()
            c.publish(streamsDiscoveryTopic, streamsDiscoveryPayload, retain = true)

            AppLog.i("MqttManager: Published Home Assistant MQTT Discovery topics")
        } catch (e: Exception) {
            AppLog.w("MqttManager: Failed to publish HA discovery: ${e.message}")
        }
    }

    private fun getBatteryPercentage(): Int {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } catch (ignored: Exception) {
            -1
        }
    }

    private fun getIpAddress(): String? {
        if (settingsRepository.httpBindAddress != "0.0.0.0") return settingsRepository.httpBindAddress
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
}
