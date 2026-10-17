package io.github.ctxinf.cameraagent.adapters.mcp

import android.os.Build
import io.github.ctxinf.cameraagent.adapters.http.DummyFrameGenerator
import io.github.ctxinf.cameraagent.domain.CameraController
import io.github.ctxinf.cameraagent.domain.CameraState
import io.github.ctxinf.cameraagent.domain.DeviceStatus
import io.github.ctxinf.cameraagent.domain.LensFacing
import io.github.ctxinf.cameraagent.domain.Snapshot
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
import io.github.ctxinf.cameraagent.infrastructure.preferences.SettingsRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

class McpHandler(
    private val cameraController: CameraController,
    private val settingsRepository: SettingsRepository,
    private val getHardwareLevel: () -> String? = { null },
    private val dummyFrameProvider: (() -> ByteArray)? = null,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * Handles an incoming MCP JSON-RPC string and returns the JSON-RPC response string.
     */
    fun handleRequest(requestBody: String): String {
        if (requestBody.isBlank()) {
            return buildErrorResponse(JsonNull, -32700, "Parse error: empty body")
        }

        val parsed: JsonElement = try {
            json.parseToJsonElement(requestBody)
        } catch (e: Exception) {
            return buildErrorResponse(JsonNull, -32700, "Parse error: ${e.message}")
        }

        if (parsed !is JsonObject) {
            return buildErrorResponse(JsonNull, -32600, "Invalid Request: expected JSON object")
        }

        val id = parsed["id"] ?: JsonNull
        val method = parsed["method"]?.jsonPrimitive?.content
            ?: return buildErrorResponse(id, -32600, "Invalid Request: missing method")
        val params = parsed["params"] as? JsonObject ?: JsonObject(emptyMap())

        AppLog.d("McpHandler: method=$method id=$id")

        return try {
            when (method) {
                "initialize" -> handleInitialize(id)
                "notifications/initialized" -> "" // Notification, no response body needed
                "ping" -> handlePing(id)
                "server/discover" -> handleDiscover(id)
                "tools/list" -> handleToolsList(id)
                "tools/call" -> handleToolsCall(id, params)
                else -> buildErrorResponse(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            AppLog.e("McpHandler internal error on $method: ${e.message}", e)
            buildErrorResponse(id, -32603, "Internal error: ${e.message}")
        }
    }

    private fun handleInitialize(id: JsonElement): String {
        val result = buildJsonObject {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") {
                putJsonObject("tools") {}
            }
            putJsonObject("serverInfo") {
                put("name", "camera-agent")
                put("version", "1.0.0")
            }
        }
        return buildSuccessResponse(id, result)
    }

    private fun handlePing(id: JsonElement): String {
        return buildSuccessResponse(id, buildJsonObject {})
    }

    private fun handleDiscover(id: JsonElement): String {
        val toolsArray = buildToolsArray()
        val result = buildJsonObject {
            put("protocolVersion", "2024-11-05")
            putJsonObject("serverInfo") {
                put("name", "camera-agent")
                put("version", "1.0.0")
            }
            put("tools", toolsArray)
        }
        return buildSuccessResponse(id, result)
    }

    private fun handleToolsList(id: JsonElement): String {
        val result = buildJsonObject {
            put("tools", buildToolsArray())
        }
        return buildSuccessResponse(id, result)
    }

    private fun buildToolsArray(): JsonArray {
        return buildJsonArray {
            // 1. get_snapshot
            add(buildJsonObject {
                put("name", "get_snapshot")
                put("description", "Capture a photo snapshot from the camera hardware. Returns JPEG image base64 encoded and size metadata.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("quality") {
                            put("type", "integer")
                            put("description", "JPEG quality from 10 to 100")
                            put("minimum", 10)
                            put("maximum", 100)
                        }
                    }
                }
            })

            // 2. get_status
            add(buildJsonObject {
                put("name", "get_status")
                put("description", "Get current device and camera telemetry, state, resolution, battery, and stream status.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {}
                }
            })

            // 3. set_camera_facing
            add(buildJsonObject {
                put("name", "set_camera_facing")
                put("description", "Switch camera lens direction between BACK and FRONT.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("facing") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add(JsonPrimitive("BACK"))
                                add(JsonPrimitive("FRONT"))
                            }
                            put("description", "Lens direction: BACK or FRONT")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("facing"))
                    }
                }
            })

            // 4. set_camera_enabled
            add(buildJsonObject {
                put("name", "set_camera_enabled")
                put("description", "Enable or suspend camera service hardware access.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("enabled") {
                            put("type", "boolean")
                            put("description", "True to enable camera access, false to suspend")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("enabled"))
                    }
                }
            })

            // 5. set_camera_config
            add(buildJsonObject {
                put("name", "set_camera_config")
                put("description", "Update camera configuration (resolution, target FPS, JPEG quality, retention grace period).")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("targetWidth") { put("type", "integer") }
                        putJsonObject("targetHeight") { put("type", "integer") }
                        putJsonObject("targetFps") { put("type", "integer") }
                        putJsonObject("jpegQuality") { put("type", "integer") }
                        putJsonObject("rotationOffset") { put("type", "integer") }
                        putJsonObject("retentionGracePeriodSec") { put("type", "integer") }
                    }
                }
            })

            // 6. warmup_camera
            add(buildJsonObject {
                put("name", "warmup_camera")
                put("description", "Pre-activate camera hardware for immediate responsiveness before capturing or streaming.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("durationSec") {
                            put("type", "integer")
                            put("description", "Number of seconds to keep camera warm (1-60)")
                            put("default", 5)
                        }
                    }
                }
            })
        }
    }

    private fun handleToolsCall(id: JsonElement, params: JsonObject): String {
        val toolName = params["name"]?.jsonPrimitive?.content
            ?: return buildErrorResponse(id, -32602, "Invalid params: missing tool name")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        return when (toolName) {
            "get_snapshot" -> executeGetSnapshot(id, arguments)
            "get_status" -> executeGetStatus(id)
            "set_camera_facing" -> executeSetFacing(id, arguments)
            "set_camera_enabled" -> executeSetEnabled(id, arguments)
            "set_camera_config" -> executeSetConfig(id, arguments)
            "warmup_camera" -> executeWarmup(id, arguments)
            else -> buildErrorResponse(id, -32601, "Unknown tool: $toolName")
        }
    }

    private fun executeGetSnapshot(id: JsonElement, args: JsonObject): String {
        val config = cameraController.config.value
        val isEnabled = config.enabled

        val (jpegBytes, width, height) = if (!isEnabled) {
            val bytes = dummyFrameProvider?.invoke() ?: DummyFrameGenerator.createDummyJpeg()
            Triple(bytes, 640, 480)
        } else {
            try {
                val snapshot: Snapshot = runBlocking {
                    cameraController.takeSnapshot()
                }
                Triple(snapshot.jpegBytes, snapshot.width, snapshot.height)
            } catch (e: Exception) {
                return buildToolErrorResponse(id, "Failed to capture snapshot: ${e.message}")
            }
        }

        val base64Data = Base64.getEncoder().encodeToString(jpegBytes)
        val result = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "Snapshot captured successfully: ${width}x$height, ${jpegBytes.size} bytes (enabled=$isEnabled)")
                })
                add(buildJsonObject {
                    put("type", "image")
                    put("data", base64Data)
                    put("mimeType", "image/jpeg")
                })
            }
            put("isError", false)
        }
        return buildSuccessResponse(id, result)
    }

    private fun executeGetStatus(id: JsonElement): String {
        val state = cameraController.state.value
        val config = cameraController.config.value

        val deviceStatus = DeviceStatus(
            cameraState = when (state) {
                is CameraState.Disabled -> "Disabled"
                is CameraState.Closed -> "Closed"
                is CameraState.Opening -> "Opening"
                is CameraState.Active -> "Active"
                is CameraState.Retained -> "Retained"
                is CameraState.Closing -> "Closing"
                is CameraState.Failed -> "Failed"
            },
            cameraId = (state as? CameraState.Active)?.cameraId ?: (state as? CameraState.Retained)?.cameraId,
            facing = config.facing,
            width = (state as? CameraState.Active)?.width ?: config.targetWidth,
            height = (state as? CameraState.Active)?.height ?: config.targetHeight,
            fps = config.targetFps,
            jpegQuality = config.jpegQuality,
            activeStreams = (state as? CameraState.Active)?.activeStreams ?: 0,
            lastFrameTimestampNs = null,
            lastError = (state as? CameraState.Failed)?.reason,
            cameraEnabled = config.enabled,
            androidSdk = Build.VERSION.SDK_INT,
            hardwareLevel = getHardwareLevel(),
        )

        val jsonStr = json.encodeToString(DeviceStatus.serializer(), deviceStatus)
        val result = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", jsonStr)
                })
            }
            put("isError", false)
        }
        return buildSuccessResponse(id, result)
    }

    private fun executeSetFacing(id: JsonElement, args: JsonObject): String {
        val facingStr = args["facing"]?.jsonPrimitive?.content?.uppercase()
            ?: return buildErrorResponse(id, -32602, "Missing 'facing' argument")

        val facing = try {
            LensFacing.valueOf(facingStr)
        } catch (e: Exception) {
            return buildErrorResponse(id, -32602, "Invalid facing '$facingStr', must be BACK or FRONT")
        }

        runBlocking {
            val current = cameraController.config.value
            val updated = current.copy(facing = facing)
            val applied = cameraController.updateConfig(updated)
            settingsRepository.updateConfig(applied)
        }

        val result = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "Camera facing successfully updated to $facing")
                })
            }
            put("isError", false)
        }
        return buildSuccessResponse(id, result)
    }

    private fun executeSetEnabled(id: JsonElement, args: JsonObject): String {
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: return buildErrorResponse(id, -32602, "Missing or invalid 'enabled' boolean argument")

        runBlocking {
            cameraController.setEnabled(enabled)
            val current = cameraController.config.value.copy(enabled = enabled)
            val applied = cameraController.updateConfig(current)
            settingsRepository.updateConfig(applied)
        }

        val result = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "Camera enabled state set to $enabled")
                })
            }
            put("isError", false)
        }
        return buildSuccessResponse(id, result)
    }

    private fun executeSetConfig(id: JsonElement, args: JsonObject): String {
        var current = cameraController.config.value

        args["targetWidth"]?.jsonPrimitive?.intOrNull?.let { current = current.copy(targetWidth = it) }
        args["targetHeight"]?.jsonPrimitive?.intOrNull?.let { current = current.copy(targetHeight = it) }
        args["targetFps"]?.jsonPrimitive?.intOrNull?.let { current = current.copy(targetFps = it.coerceIn(1, 60)) }
        args["jpegQuality"]?.jsonPrimitive?.intOrNull?.let { current = current.copy(jpegQuality = it.coerceIn(10, 100)) }
        args["rotationOffset"]?.jsonPrimitive?.intOrNull?.let { current = current.copy(rotationOffset = it) }
        args["retentionGracePeriodSec"]?.jsonPrimitive?.intOrNull?.let {
            current = current.copy(retentionGracePeriodSec = it.coerceIn(0, 3600))
        }

        val applied = runBlocking {
            val res = cameraController.updateConfig(current)
            settingsRepository.updateConfig(res)
            res
        }

        val configStr = json.encodeToString(io.github.ctxinf.cameraagent.domain.CameraConfig.serializer(), applied)
        val result = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "Camera config updated: $configStr")
                })
            }
            put("isError", false)
        }
        return buildSuccessResponse(id, result)
    }

    private fun executeWarmup(id: JsonElement, args: JsonObject): String {
        if (!cameraController.config.value.enabled) {
            return buildToolErrorResponse(id, "Cannot warmup: camera service is suspended")
        }

        val durationSec = args["durationSec"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 60) ?: 5
        val durationMs = durationSec * 1000L

        try {
            runBlocking {
                cameraController.warmup(durationMs)
            }
        } catch (e: Exception) {
            return buildToolErrorResponse(id, "Failed to warmup camera: ${e.message}")
        }

        val result = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "Camera successfully activated and warming up for ${durationSec}s")
                })
            }
            put("isError", false)
        }
        return buildSuccessResponse(id, result)
    }

    private fun buildSuccessResponse(id: JsonElement, result: JsonElement): String {
        val resp = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }
        return json.encodeToString(JsonObject.serializer(), resp)
    }

    private fun buildToolErrorResponse(id: JsonElement, message: String): String {
        val result = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", message)
                })
            }
            put("isError", true)
        }
        return buildSuccessResponse(id, result)
    }

    private fun buildErrorResponse(id: JsonElement, code: Int, message: String): String {
        val resp = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }
        return json.encodeToString(JsonObject.serializer(), resp)
    }
}
